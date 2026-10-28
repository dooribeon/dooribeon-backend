package com.dooribeon.condition.chatkhu;

import com.dooribeon.condition.ConditionsExtraction;
import com.dooribeon.condition.ConditionsDraft;
import com.dooribeon.condition.ConditionsUpdate;
import java.util.HashSet;
import com.dooribeon.condition.ConditionsExtraction.Clarification;
import com.dooribeon.condition.ConditionsExtraction.ExtractedPlace;
import com.dooribeon.condition.ConditionsExtraction.Reason;
import com.dooribeon.condition.ConditionsExtraction.Role;
import com.dooribeon.condition.PlaceCondition.Importance;
import com.dooribeon.condition.PlaceCondition.TargetType;
import com.dooribeon.condition.PlaceCondition.Transport;
import com.dooribeon.condition.VisitFrequency;
import com.dooribeon.global.ApiException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 전체 조건의 최초 요청·응답. 소수 자동 버림이나 AI가 만든 좌표를 허용하지 않는다. */
@Component
public class ChatKhuConditionsCodec {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final String prompt;
    private final Object schema;
    private final String updatePrompt;
    private final Object updateSchema;

    public ChatKhuConditionsCodec() throws IOException {
        prompt = new ClassPathResource("ai/conditions-system-prompt.txt").getContentAsString(StandardCharsets.UTF_8);
        schema = mapper.readValue(new ClassPathResource("ai/conditions-output.schema.json")
                .getContentAsString(StandardCharsets.UTF_8), Object.class);
        updatePrompt = new ClassPathResource("ai/conditions-update-system-prompt.txt").getContentAsString(StandardCharsets.UTF_8);
        updateSchema = mapper.readValue(new ClassPathResource("ai/conditions-update.schema.json")
                .getContentAsString(StandardCharsets.UTF_8), Object.class);
    }

    public String requestJson(String text, String model) {
        if (text == null || text.isBlank() || text.length() > 10000) {
            throw new IllegalArgumentException("추출할 문장은 1~10000자로 입력해 주세요.");
        }
        return mapper.writeValueAsString(Map.of("model", model, "stream", false,
                "messages", List.of(Map.of("role", "system", "content", prompt),
                        Map.of("role", "user", "content", text)),
                "response_format", Map.of("type", "json_schema", "json_schema",
                        Map.of("name", "dooribeon_conditions", "strict", true, "schema", schema))));
    }

    public ConditionsExtraction readResponse(String body) {
        return readExtraction(responseContent(body));
    }

    public String updateRequestJson(String text, ConditionsDraft current, String question, String model) {
        // 선택 위치의 외부 지도 응답·좌표는 AI에 다시 보내지 않는다.
        var aiConditions = new com.dooribeon.condition.Conditions(current.conditions().maxDeposit(),
                current.conditions().maxRent(), current.conditions().places().stream().map(p ->
                new com.dooribeon.condition.PlaceCondition(p.id(), p.label(), p.targetType(), p.query(), null,
                        p.importance(), p.transport(), p.maxMinutes(), p.frequency())).toList());
        String context = mapper.writeValueAsString(Map.of("currentConditions", aiConditions,
                "pendingInputs", current.pendingInputs().stream().map(p -> new ConditionsDraft.PendingInput(
                        p.id(), p.issue(), p.reason(), p.sourceText(), withoutMapDetails(p.question(), current))).toList(),
                "lastQuestion", withoutMapDetails(question, current),
                "userAnswer", text));
        return mapper.writeValueAsString(Map.of("model", model, "stream", false,
                "messages", List.of(Map.of("role", "system", "content", prompt + "\n" + updatePrompt),
                        Map.of("role", "user", "content", context)),
                "response_format", Map.of("type", "json_schema", "json_schema",
                        Map.of("name", "dooribeon_conditions_update", "strict", true, "schema", updateSchema))));
    }

    private String withoutMapDetails(String question, ConditionsDraft current) {
        if (question == null) return "";
        String result = question;
        for (var place : current.conditions().places()) {
            if (place.location() == null) continue;
            String replacement = place.label() == null ? "선택한 장소" : place.label();
            String address = place.location().address();
            String name = place.location().placeName();
            if (address != null && !address.isBlank()) result = result.replace(address, replacement);
            if (name != null && !name.isBlank()) result = result.replace(name, replacement);
        }
        return result;
    }

    public ConditionsUpdate readUpdateResponse(String body) {
        Map<?, ?> result = responseContent(body);
        exactKeys(result, "values", "updatedFields", "placeUpdates", "resolvedIssueIds");
        ConditionsExtraction values = readExtraction(object(result.get("values")));
        Set<String> fields = stringSet(result.get("updatedFields"));
        if (!Set.of("maxDeposit", "maxRent").containsAll(fields)) throw invalid();
        List<ConditionsUpdate.PlaceUpdate> updates = new ArrayList<>();
        for (Object value : array(result.get("placeUpdates"))) {
            Map<?, ?> item = object(value);
            exactKeys(item, "id", "action", "updatedFields", "values");
            updates.add(new ConditionsUpdate.PlaceUpdate(text(item.get("id"), false),
                    enumValue(item.get("action"), ConditionsUpdate.Action.class, false),
                    stringSet(item.get("updatedFields")),
                    item.get("values") == null ? null : readPlace(object(item.get("values")))));
        }
        return new ConditionsUpdate(values, fields, updates, stringSet(result.get("resolvedIssueIds")));
    }

    private Set<String> stringSet(Object value) {
        Set<String> values = new HashSet<>();
        for (Object element : array(value)) if (!values.add(text(element, false))) throw invalid();
        return values;
    }

    private Map<?, ?> responseContent(String body) {
        Map<?, ?> envelope = object(readJson(body));
        List<?> choices = array(envelope.get("choices"));
        if (choices.isEmpty()) throw invalid();
        Map<?, ?> choice = object(choices.getFirst());
        Map<?, ?> message = object(choice.get("message"));
        if (!"stop".equals(choice.get("finish_reason")) || message.get("refusal") != null
                || !(message.get("content") instanceof String content)) throw invalid();
        return object(readJson(content));
    }

    private ConditionsExtraction readExtraction(Map<?, ?> result) {
        exactKeys(result, "maxDeposit", "maxRent", "places", "clarifications", "unhandledRequests");
        Long deposit = amount(result.get("maxDeposit"));
        Long rent = amount(result.get("maxRent"));
        List<ExtractedPlace> places = new ArrayList<>();
        for (Object value : array(result.get("places"))) places.add(readPlace(object(value)));
        List<Clarification> clarifications = new ArrayList<>();
        for (Object value : array(result.get("clarifications"))) {
            Map<?, ?> item = object(value);
            exactKeys(item, "field", "reason");
            String field = text(item.get("field"), false);
            if (!Set.of("maxDeposit", "maxRent", "budget", "conditions").contains(field)) throw invalid();
            var clarification = new Clarification(field, enumValue(item.get("reason"), Reason.class, false));
            checkClarification(clarification, deposit, rent);
            if (clarifications.contains(clarification)) throw invalid();
            clarifications.add(clarification);
        }
        List<String> unhandled = new ArrayList<>();
        for (Object value : array(result.get("unhandledRequests"))) unhandled.add(text(value, false));
        return new ConditionsExtraction(deposit, rent, places, clarifications, unhandled);
    }

    private ExtractedPlace readPlace(Map<?, ?> place) {
        exactKeys(place, "label", "targetType", "query", "role", "importance", "importanceSpecified",
                "transport", "maxMinutes", "frequency", "frequencyUnclear");
        Importance importance = enumValue(place.get("importance"), Importance.class, true);
        boolean specified = bool(place.get("importanceSpecified"));
        if (importance != null && !specified) throw invalid();
        VisitFrequency frequency = null;
        if (place.get("frequency") != null) {
            Map<?, ?> f = object(place.get("frequency"));
            exactKeys(f, "period", "count");
            frequency = new VisitFrequency(enumValue(f.get("period"), VisitFrequency.Period.class, false),
                    positiveInt(f.get("count"), false));
        }
        boolean unclear = bool(place.get("frequencyUnclear"));
        if (unclear && frequency != null) throw invalid();
        return new ExtractedPlace(text(place.get("label"), true),
                enumValue(place.get("targetType"), TargetType.class, true), text(place.get("query"), true),
                enumValue(place.get("role"), Role.class, true), importance, specified,
                enumValue(place.get("transport"), Transport.class, true), positiveInt(place.get("maxMinutes"), true),
                frequency, unclear);
    }

    private void checkClarification(Clarification c, Long deposit, Long rent) {
        boolean valid = switch (c.reason()) {
            case AMOUNT_UNIT_REQUIRED -> c.field().equals("budget") && deposit == null && rent == null;
            case INTEGER_MANWON_REQUIRED -> (c.field().equals("maxDeposit") && deposit == null)
                    || (c.field().equals("maxRent") && rent == null);
            case NET_RENT_REQUIRED -> c.field().equals("maxRent") && rent == null;
            case UNSUPPORTED_RENTAL_TYPE -> c.field().equals("conditions") && deposit == null && rent == null;
            case AMBIGUOUS_INPUT -> c.field().equals("conditions")
                    || (c.field().equals("budget") && deposit == null && rent == null)
                    || (c.field().equals("maxDeposit") && deposit == null)
                    || (c.field().equals("maxRent") && rent == null);
        };
        if (!valid) throw invalid();
    }

    private Long amount(Object value) {
        Long n = integer(value, true);
        if (n != null && (n < 0 || n > 9007199254740991L)) throw invalid();
        return n;
    }

    private Integer positiveInt(Object value, boolean nullable) {
        Long n = integer(value, nullable);
        if (n == null) return null;
        if (n <= 0 || n > Integer.MAX_VALUE) throw invalid();
        return n.intValue();
    }

    private Long integer(Object value, boolean nullable) {
        if (value == null && nullable) return null;
        if (value instanceof Integer n) return n.longValue();
        if (value instanceof Long n) return n;
        if (value instanceof BigInteger n) {
            try { return n.longValueExact(); }
            catch (ArithmeticException e) { throw invalid(); }
        }
        throw invalid();
    }

    private String text(Object value, boolean nullable) {
        if (value == null && nullable) return null;
        if (value instanceof String s && !s.isBlank() && s.length() <= 10000) return s;
        throw invalid();
    }

    private boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        throw invalid();
    }

    private <E extends Enum<E>> E enumValue(Object value, Class<E> type, boolean nullable) {
        if (value == null && nullable) return null;
        try { return Enum.valueOf(type, text(value, false)); }
        catch (IllegalArgumentException e) { throw invalid(); }
    }

    private void exactKeys(Map<?, ?> value, String... keys) {
        if (!value.keySet().equals(Set.of(keys))) throw invalid();
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) throw invalid();
        try { return mapper.readValue(json, Object.class); }
        catch (RuntimeException e) { throw invalid(); }
    }

    private Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw invalid();
    }

    private List<?> array(Object value) {
        if (value instanceof List<?> list) return list;
        throw invalid();
    }

    private ApiException invalid() {
        return new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE",
                "AI 답변이 약속한 조건 형식과 달라요. 다시 시도해 주세요.");
    }
}
