package com.dooribeon.condition.chatkhu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dooribeon.condition.ConditionsExtraction.Reason;
import com.dooribeon.condition.ConditionsExtraction.Role;
import com.dooribeon.condition.VisitFrequency;
import com.dooribeon.global.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChatKhuConditionsCodecTests {
    private final ChatKhuConditionsCodec codec = new ChatKhuConditionsCodec();
    private final JsonMapper mapper = JsonMapper.builder().build();

    ChatKhuConditionsCodecTests() throws Exception {}

    static String company() {
        return """
                {"maxDeposit":1000,"maxRent":50,"places":[{
                "label":"회사","targetType":"FIXED_PLACE","query":"여의도역","role":"WORK",
                "importance":null,"importanceSpecified":false,"transport":"PUBLIC_TRANSIT","maxMinutes":40,
                "frequency":{"period":"MONTH","count":2},"frequencyUnclear":false}],
                "clarifications":[],"unhandledRequests":[]}
                """;
    }

    static String emptyPlaces(String deposit, String rent, String clarifications) {
        return """
                {"maxDeposit":%s,"maxRent":%s,"places":[],"clarifications":%s,"unhandledRequests":[]}
                """.formatted(deposit, rent, clarifications);
    }

    @Test
    void requestUsesNewSchemaAndKeepsUserTextSeparateFromInstructions() {
        var request = mapper.readTree(codec.requestJson("보증금 천만 원, 월세 50만 원", "test-model"));
        assertThat(request.get("messages").get(1).get("content").asText())
                .isEqualTo("보증금 천만 원, 월세 50만 원");
        assertThat(request.get("response_format").get("json_schema").get("name").asText())
                .isEqualTo("dooribeon_conditions");
        assertThat(request.get("response_format").get("json_schema").get("strict").booleanValue()).isTrue();
        var properties = request.get("response_format").get("json_schema").get("schema").get("properties");
        assertThat(properties.has("maxDeposit")).isTrue();
        assertThat(properties.has("budget")).isFalse();
        var placeProperties = properties.get("places").get("items").get("properties");
        assertThat(placeProperties.has("location")).isFalse();
        assertThat(placeProperties.has("id")).isFalse();
        assertThat(placeProperties.has("frequencyText")).isFalse();
    }

    @Test
    void initialAndFollowupWireSchemasUseProviderCompatibleConstraintsAndNullableEnums() {
        var current = new com.dooribeon.condition.ConditionsDraft(
                new com.dooribeon.condition.Conditions(null, 50L, List.of()), java.util.Set.of(), List.of(), List.of());
        for (String request : List.of(codec.requestJson("월세 50만 원", "test-model"),
                codec.updateRequestJson("천만 원", current, "보증금은 얼마인가요?", "test-model"))) {
            var schema = mapper.readTree(request).get("response_format").get("json_schema").get("schema");
            assertThat(assertCompatibleSchema(schema)).isLessThanOrEqualTo(16);
            var place = schema.has("$defs") ? schema.path("$defs").path("extractedPlace")
                    : schema.path("properties").path("places").path("items");
            for (String field : List.of("targetType", "role", "importance", "transport")) {
                var alternatives = place.path("properties").path(field).path("anyOf");
                assertThat(alternatives.size()).isEqualTo(2);
                assertThat(alternatives.get(0).path("type").asString()).isEqualTo("string");
                assertThat(alternatives.get(0).path("enum").isArray()).isTrue();
                assertThat(alternatives.get(1).path("type").asString()).isEqualTo("null");
            }
        }
    }

    private int assertCompatibleSchema(tools.jackson.databind.JsonNode node) {
        int unions = 0;
        if (node.isObject()) {
            assertThat(node.has("minimum")).isFalse();
            assertThat(node.has("maximum")).isFalse();
            // ChatKHU rejects enum together with type: ["string", "null"].
            if (node.has("enum")) {
                assertThat(node.path("type").asString()).isEqualTo("string");
                for (var value : node.get("enum")) assertThat(value.isString()).isTrue();
            }
            if (node.path("type").isArray() || node.has("anyOf")) unions++;
            if (node.has("$ref")) assertThat(node.get("$ref").asString()).isEqualTo("#/$defs/extractedPlace");
            for (var child : node) unions += assertCompatibleSchema(child);
        } else if (node.isArray()) {
            for (var child : node) unions += assertCompatibleSchema(child);
        }
        return unions;
    }

    @Test
    void readsManWonRoleAndOriginalMonthlyFrequency() {
        var result = codec.readResponse(ChatKhuBudgetCodecTests.envelope(company()));
        assertThat(result.maxDeposit()).isEqualTo(1000L);
        assertThat(result.maxRent()).isEqualTo(50L);
        var place = result.places().getFirst();
        assertThat(place.role()).isEqualTo(Role.WORK);
        assertThat(place.importance()).isNull();
        assertThat(place.frequency()).isEqualTo(new VisitFrequency(VisitFrequency.Period.MONTH, 2));
    }

    @Test
    void budgetOnlyAndMissingValuesAreValidExtractions() {
        var result = codec.readResponse(ChatKhuBudgetCodecTests.envelope(emptyPlaces("0", "null", "[]")));
        assertThat(result.maxDeposit()).isZero();
        assertThat(result.maxRent()).isNull();
        assertThat(result.places()).isEmpty();
    }

    @Test
    void decimalsNumericStringsBooleansNegativeAndOversizedAmountsAreRejected() {
        for (String value : List.of("50.5", "50.0", "1e2", "\"50\"", "true", "-1",
                "9007199254740992", "9999999999999999999999999")) {
            assertThatThrownBy(() -> codec.readResponse(ChatKhuBudgetCodecTests.envelope(
                    emptyPlaces("1000", value, "[]")))).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void invalidTimeAndFrequencyCannotBeSilentlyCoerced() {
        for (String value : List.of("40.5", "\"40\"", "0", "-1", "2147483648")) {
            assertThatThrownBy(() -> codec.readResponse(ChatKhuBudgetCodecTests.envelope(
                    company().replace("\"maxMinutes\":40", "\"maxMinutes\":" + value))))
                    .isInstanceOf(ApiException.class);
        }
        for (String value : List.of("2.5", "\"2\"", "0", "null", "true")) {
            assertThatThrownBy(() -> codec.readResponse(ChatKhuBudgetCodecTests.envelope(
                    company().replace("\"count\":2", "\"count\":" + value))))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void missingExtraDuplicateKeysAndAiCoordinatesAreRejected() {
        for (String content : List.of(
                company().replace("\"query\":\"여의도역\",", ""),
                company().replace("\"query\":\"여의도역\",", "\"query\":\"여의도역\",\"location\":null,"),
                company().replace("\"role\":\"WORK\",", "\"role\":\"WORK\",\"lat\":37.5,"),
                company().replace("\"label\":\"회사\",", "\"label\":\"회사\",\"id\":\"ai-id\","),
                company().replace("\"maxRent\":50", "\"maxRent\":50,\"maxRent\":50"),
                company().replace("\"places\":", "\"preferences\":[],\"places\":"),
                company() + "{}", "```json\n" + company() + "\n```")) {
            assertThatThrownBy(() -> codec.readResponse(ChatKhuBudgetCodecTests.envelope(content)))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void conflictingMetadataAndUnknownEnumsAreRejected() {
        for (String content : List.of(
                company().replace("\"importance\":null", "\"importance\":\"HIGH\""),
                company().replace("\"frequencyUnclear\":false", "\"frequencyUnclear\":true"),
                company().replace("\"role\":\"WORK\"", "\"role\":\"OTHER\""),
                company().replace("\"transport\":\"PUBLIC_TRANSIT\"", "\"transport\":\"CAR\""),
                company().replace("\"period\":\"MONTH\"", "\"period\":\"YEAR\""),
                company().replace("\"importanceSpecified\":false", "\"importanceSpecified\":\"false\""))) {
            assertThatThrownBy(() -> codec.readResponse(ChatKhuBudgetCodecTests.envelope(content)))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void confirmationCannotCoexistWithUnconfirmedMoneyAndFieldMustMatchReason() {
        String unit = "[{\"field\":\"budget\",\"reason\":\"AMOUNT_UNIT_REQUIRED\"}]";
        var result = codec.readResponse(ChatKhuBudgetCodecTests.envelope(emptyPlaces("null", "null", unit)));
        assertThat(result.clarifications().getFirst().reason()).isEqualTo(Reason.AMOUNT_UNIT_REQUIRED);
        for (String content : List.of(emptyPlaces("1000", "null", unit),
                emptyPlaces("null", "50", unit),
                emptyPlaces("null", "null", unit.replace("\"budget\"", "\"maxRent\"")),
                emptyPlaces("null", "null", unit.replace("AMOUNT_UNIT_REQUIRED", "UNKNOWN")),
                emptyPlaces("null", "null", "[" + unit.substring(1, unit.length() - 1) + ","
                        + unit.substring(1, unit.length() - 1) + "]"))) {
            assertThatThrownBy(() -> codec.readResponse(ChatKhuBudgetCodecTests.envelope(content)))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void fractionalWonAndNetRentConfirmationsPreserveOnlyOtherClearMoney() {
        for (String reason : List.of("INTEGER_MANWON_REQUIRED", "NET_RENT_REQUIRED")) {
            String questions = "[{\"field\":\"maxRent\",\"reason\":\"" + reason + "\"}]";
            assertThat(codec.readResponse(ChatKhuBudgetCodecTests.envelope(emptyPlaces("1000", "null", questions)))
                    .maxDeposit()).isEqualTo(1000L);
            assertThatThrownBy(() -> codec.readResponse(ChatKhuBudgetCodecTests.envelope(
                    emptyPlaces("1000", "50", questions)))).isInstanceOf(ApiException.class);
        }
        String placeAmbiguity = "[{\"field\":\"conditions\",\"reason\":\"AMBIGUOUS_INPUT\"}]";
        assertThat(codec.readResponse(ChatKhuBudgetCodecTests.envelope(emptyPlaces("1000", "50", placeAmbiguity)))
                .maxRent()).isEqualTo(50L);
    }

    @Test
    void updateContextKeepsIdsButNeverSendsSelectedMapCoordinates() {
        var p = new com.dooribeon.condition.PlaceCondition("server-id", "회사",
                com.dooribeon.condition.PlaceCondition.TargetType.FIXED_PLACE, "여의도역",
                new com.dooribeon.condition.PlaceLocation("provider-id", "검색결과", "외부 주소", 37.52, 126.92),
                null, null, null, null);
        var draft = new com.dooribeon.condition.ConditionsDraft(
                new com.dooribeon.condition.Conditions(1000L, 50L, List.of(p)), java.util.Set.of(), List.of(), List.of());
        var request = mapper.readTree(codec.updateRequestJson("이름 지워줘", draft, "검색결과 (외부 주소)까지 몇 분인가요?", "test-model"));
        String context = request.get("messages").get(1).get("content").asString();
        assertThat(context).contains("server-id").doesNotContain("provider-id", "외부 주소", "37.52", "126.92");
        String badPatch = "{\"values\":" + emptyPlaces("1000.5", "null", "[]")
                + ",\"updatedFields\":[\"maxDeposit\"],\"placeUpdates\":[],\"resolvedIssueIds\":[]}";
        assertThatThrownBy(() -> codec.readUpdateResponse(ChatKhuBudgetCodecTests.envelope(badPatch)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void malformedTruncatedOrRefusedProviderEnvelopesAreFailures() {
        for (String body : List.of("{}", "[]", "null", "{", "{\"choices\":[]}",
                ChatKhuBudgetCodecTests.envelope(company()).replace("\"stop\"", "\"length\""),
                mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                        "message", Map.of("refusal", "no", "content", company()))))))) {
            assertThatThrownBy(() -> codec.readResponse(body)).isInstanceOf(ApiException.class);
        }
    }
}
