package com.dooribeon.condition.chatkhu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dooribeon.global.ApiException;
import com.dooribeon.condition.RentalType;
import com.dooribeon.condition.Budget;
import com.dooribeon.condition.BudgetField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChatKhuBudgetCodecTests {
    private final ChatKhuBudgetCodec codec = new ChatKhuBudgetCodec();
    private final JsonMapper mapper = JsonMapper.builder().build();

    ChatKhuBudgetCodecTests() throws Exception {}

    static String envelope(String content) {
        return JsonMapper.builder().build().writeValueAsString(Map.of("choices", List.of(Map.of(
                "finish_reason", "stop", "message", Map.of("content", content)))));
    }

    static String monthlyBudget() {
        return """
                {"budget":{"rentalType":"MONTHLY_RENT","jeonseMaxWon":null,
                "depositMaxWon":10000000,"monthlyRentMaxWon":500000},"needsAmountUnitConfirmation":false}
                """;
    }

    @Test
    void requestKeepsUserInputSeparateFromInstructionsAndUsesStrictSchema() {
        Map<?, ?> request = mapper.readValue(codec.requestJson("월세 60만 원", "test-model"), Map.class);
        assertThat(request.get("model")).isEqualTo("test-model");
        List<?> messages = (List<?>) request.get("messages");
        assertThat(((Map<?, ?>) messages.get(1)).get("content")).isEqualTo("월세 60만 원");
        Map<?, ?> format = (Map<?, ?>) request.get("response_format");
        assertThat(format.get("type")).isEqualTo("json_schema");
        assertThat(((Map<?, ?>) format.get("json_schema")).get("strict")).isEqualTo(true);
    }

    @Test
    void readsActualContentStringAndPreservesWonUnits() {
        var budget = codec.readResponse(envelope(monthlyBudget())).budget();
        assertThat(budget.rentalType()).isEqualTo(RentalType.MONTHLY_RENT);
        assertThat(budget.depositMaxWon()).isEqualTo(10000000L);
        assertThat(budget.monthlyRentMaxWon()).isEqualTo(500000L);
        assertThat(budget.jeonseMaxWon()).isNull();
    }

    @Test
    void floatStringBooleanAndOversizedMoneyAreRejected() {
        for (String amount : List.of("50.5", "\"50만 원\"", "true", "999999999999999999999999")) {
            String content = monthlyBudget().replace("500000", amount);
            assertThatThrownBy(() -> codec.readResponse(envelope(content))).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void wrongKeysTypeAndNonJsonContentAreRejected() {
        for (String content : List.of("{}", "[]", "```json\n{}\n```",
                monthlyBudget().replace("MONTHLY_RENT", "PURCHASE"),
                monthlyBudget().replace("\"jeonseMaxWon\":null,", ""),
                monthlyBudget().replace("\"jeonseMaxWon\":null,", "\"jeonseMaxWon\":null,\"managementFeeWon\":100000,"),
                monthlyBudget().replace("\"jeonseMaxWon\":null,", "\"jeonseMaxWon\":null,\"jeonseMaxWon\":null,"),
                monthlyBudget() + "{}")) {
            assertThatThrownBy(() -> codec.readResponse(envelope(content))).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void incompleteRefusedAndUnexpectedEnvelopesAreRejected() {
        for (String body : List.of("{}", "[]", "{\"choices\":[]}",
                envelope(monthlyBudget()).replace("\"stop\"", "\"length\""),
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"refusal\":\"거절\",\"content\":null}}]}")) {
            assertThatThrownBy(() -> codec.readResponse(body)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void updateRequestSendsCurrentBudgetQuestionAndAnswerAsSeparateDataFields() {
        var budget = new Budget(RentalType.MONTHLY_RENT, null, null, 500000L);
        Map<?, ?> request = mapper.readValue(codec.updateRequestJson("천만 원", "test-model", budget,
                "보증금은 최대 얼마까지 생각하고 있나요?"), Map.class);
        List<?> messages = (List<?>) request.get("messages");
        Map<?, ?> context = mapper.readValue((String) ((Map<?, ?>) messages.get(1)).get("content"), Map.class);
        assertThat(context.get("userAnswer")).isEqualTo("천만 원");
        assertThat(context.get("lastQuestion")).isEqualTo("보증금은 최대 얼마까지 생각하고 있나요?");
        assertThat(((Map<?, ?>) context.get("currentBudget")).get("monthlyRentMaxWon")).isEqualTo(500000);
        assertThat(((Map<?, ?>) context.get("currentBudget")).get("depositMaxWon")).isNull();
        Map<?, ?> format = (Map<?, ?>) request.get("response_format");
        assertThat(((Map<?, ?>) format.get("json_schema")).get("name")).isEqualTo("dooribeon_budget_update");
        assertThat(((Map<?, ?>) format.get("json_schema")).get("strict")).isEqualTo(true);
    }

    @Test
    void updateFieldsDistinguishExplicitClearingFromNoChange() {
        String unchanged = """
                {"budget":{"rentalType":null,"jeonseMaxWon":null,"depositMaxWon":null,"monthlyRentMaxWon":null},
                "updatedFields":[],"needsAmountUnitConfirmation":false}
                """;
        assertThat(codec.readUpdateResponse(envelope(unchanged)).updatedFields()).isEmpty();
        assertThat(codec.readUpdateResponse(envelope(unchanged.replace("[]", "[\"depositMaxWon\"]"))).updatedFields())
                .containsExactly(BudgetField.depositMaxWon);
    }

    @Test
    void updateSchemaRejectsMissingUnknownDuplicateOrNonStringFieldNames() {
        String template = """
                {"budget":{"rentalType":null,"jeonseMaxWon":null,"depositMaxWon":null,"monthlyRentMaxWon":null},
                "updatedFields":%s,"needsAmountUnitConfirmation":false}
                """;
        for (String fields : List.of("null", "{}", "[true]", "[\"managementFeeWon\"]",
                "[\"depositMaxWon\",\"depositMaxWon\"]")) {
            assertThatThrownBy(() -> codec.readUpdateResponse(envelope(template.formatted(fields))))
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> codec.readUpdateResponse(envelope(monthlyBudget()))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> codec.readResponse(envelope(template.formatted("[]")))).isInstanceOf(ApiException.class);
    }

    @Test
    void unitFlagIsRequiredAndMustBeBooleanForBothResponseTypes() {
        String extraction = monthlyBudget();
        String update = """
                {"budget":{"rentalType":null,"jeonseMaxWon":null,"depositMaxWon":null,"monthlyRentMaxWon":null},
                "updatedFields":[],"needsAmountUnitConfirmation":false}
                """;
        for (String value : List.of("null", "\"false\"", "0")) {
            assertThatThrownBy(() -> codec.readResponse(envelope(extraction.replace("false", value)))).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> codec.readUpdateResponse(envelope(update.replace("false", value)))).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> codec.readResponse(envelope(extraction.replace(",\"needsAmountUnitConfirmation\":false", ""))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> codec.readUpdateResponse(envelope(update.replace(",\"needsAmountUnitConfirmation\":false", ""))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void unconfirmedAmountsCannotBeCombinedWithGuessedMoneyOrChanges() {
        String uncertain = """
                {"budget":{"rentalType":"MONTHLY_RENT","jeonseMaxWon":null,"depositMaxWon":null,"monthlyRentMaxWon":null},
                "needsAmountUnitConfirmation":true}
                """;
        assertThat(codec.readResponse(envelope(uncertain)).needsAmountUnitConfirmation()).isTrue();
        assertThatThrownBy(() -> codec.readResponse(envelope(uncertain.replace("\"monthlyRentMaxWon\":null", "\"monthlyRentMaxWon\":1000000"))))
                .isInstanceOf(ApiException.class);
        String update = uncertain.replace("\"MONTHLY_RENT\"", "null")
                .replace("\"needsAmountUnitConfirmation\":true", "\"updatedFields\":[],\"needsAmountUnitConfirmation\":true");
        assertThat(codec.readUpdateResponse(envelope(update)).needsAmountUnitConfirmation()).isTrue();
        assertThatThrownBy(() -> codec.readUpdateResponse(envelope(update.replace("[]", "[\"depositMaxWon\"]"))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void confirmationRequestCarriesOriginalUnconfirmedInputAsData() {
        var budget = new Budget(RentalType.MONTHLY_RENT, null, 10000000L, 500000L);
        Map<?, ?> request = mapper.readValue(codec.updateRequestJson("100만 원", "test-model", budget,
                "", "월세를 1000000으로 바꿔줘"), Map.class);
        List<?> messages = (List<?>) request.get("messages");
        Map<?, ?> context = mapper.readValue((String) ((Map<?, ?>) messages.get(1)).get("content"), Map.class);
        assertThat(context.get("pendingUserText")).isEqualTo("월세를 1000000으로 바꿔줘");
        assertThat(context.get("userAnswer")).isEqualTo("100만 원");
    }
}
