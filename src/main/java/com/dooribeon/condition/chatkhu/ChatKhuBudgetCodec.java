package com.dooribeon.condition.chatkhu;

import com.dooribeon.global.ApiException;
import com.dooribeon.condition.Budget;
import com.dooribeon.condition.BudgetExtraction;
import com.dooribeon.condition.AmountUnitPolicy;
import com.dooribeon.condition.BudgetField;
import com.dooribeon.condition.BudgetUpdate;
import com.dooribeon.condition.RentalType;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 외부 응답을 그대로 신뢰하지 않고, JSON 구조·정수·키를 엄격히 읽는다. */
@Component
public class ChatKhuBudgetCodec {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final String systemPrompt;
    private final Object outputSchema;
    private final String updatePrompt;
    private final Object updateSchema;

    public ChatKhuBudgetCodec() throws IOException {
        systemPrompt = new ClassPathResource("ai/budget-system-prompt.txt").getContentAsString(StandardCharsets.UTF_8);
        outputSchema = mapper.readValue(new ClassPathResource("ai/budget-output.schema.json")
                .getContentAsString(StandardCharsets.UTF_8), Object.class);
        updatePrompt = new ClassPathResource("ai/budget-update-system-prompt.txt").getContentAsString(StandardCharsets.UTF_8);
        updateSchema = mapper.readValue(new ClassPathResource("ai/budget-update.schema.json")
                .getContentAsString(StandardCharsets.UTF_8), Object.class);
    }

    public String requestJson(String text, String model) {
        return request(text, model, systemPrompt, outputSchema, "dooribeon_budget");
    }

    public String updateRequestJson(String text, String model, Budget currentBudget, String lastQuestion) {
        return updateRequestJson(text, model, currentBudget, lastQuestion, "");
    }

    public String updateRequestJson(String text, String model, Budget currentBudget, String lastQuestion, String pendingUserText) {
        String context = mapper.writeValueAsString(Map.of("currentBudget", currentBudget,
                "lastQuestion", lastQuestion, "userAnswer", text, "pendingUserText", pendingUserText));
        return request(context, model, systemPrompt + "\n\n" + updatePrompt, updateSchema, "dooribeon_budget_update");
    }

    private String request(String text, String model, String prompt, Object schema, String name) {
        return mapper.writeValueAsString(Map.of(
                "model", model,
                "stream", false,
                "messages", List.of(Map.of("role", "system", "content", prompt), Map.of("role", "user", "content", text)),
                "response_format", Map.of("type", "json_schema", "json_schema", Map.of(
                        "name", name, "strict", true, "schema", schema))));
    }

    public BudgetExtraction readResponse(String body) {
        Map<?, ?> result = responseContent(body);
        if (!result.keySet().equals(Set.of("budget", "needsAmountUnitConfirmation"))) throw ApiException.invalidAiResponse();
        Budget budget = readBudget(result.get("budget"));
        boolean confirm = unitConfirmation(result);
        if (confirm) AmountUnitPolicy.validateUnconfirmed(budget);
        return new BudgetExtraction(budget, confirm);
    }

    public BudgetUpdate readUpdateResponse(String body) {
        Map<?, ?> result = responseContent(body);
        if (!result.keySet().equals(Set.of("budget", "updatedFields", "needsAmountUnitConfirmation"))
                || !(result.get("updatedFields") instanceof List<?> fields)) throw ApiException.invalidAiResponse();
        Set<BudgetField> updated = new HashSet<>();
        for (Object field : fields) {
            if (!(field instanceof String name)) throw ApiException.invalidAiResponse();
            try {
                if (!updated.add(BudgetField.valueOf(name))) throw ApiException.invalidAiResponse();
            } catch (IllegalArgumentException exception) {
                throw ApiException.invalidAiResponse();
            }
        }
        Budget budget = readBudget(result.get("budget"));
        boolean confirm = unitConfirmation(result);
        if (confirm) {
            AmountUnitPolicy.validateUnconfirmed(budget);
            if (budget.rentalType() != null || !updated.isEmpty()) throw ApiException.invalidAiResponse();
        }
        return new BudgetUpdate(budget, updated, confirm);
    }

    private boolean unitConfirmation(Map<?, ?> result) {
        if (result.get("needsAmountUnitConfirmation") instanceof Boolean confirm) return confirm;
        throw ApiException.invalidAiResponse();
    }

    private Map<?, ?> responseContent(String body) {
        Map<?, ?> envelope = object(readJson(body));
        if (!(envelope.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            throw ApiException.invalidAiResponse();
        }
        Map<?, ?> choice = object(choices.getFirst());
        Map<?, ?> message = object(choice.get("message"));
        if (!"stop".equals(choice.get("finish_reason")) || message.get("refusal") != null
                || !(message.get("content") instanceof String content) || content.isBlank()) {
            throw ApiException.invalidAiResponse();
        }
        return object(readJson(content));
    }

    private Budget readBudget(Object value) {
        Map<?, ?> budget = object(value);
        if (!budget.keySet().equals(Set.of("rentalType", "jeonseMaxWon", "depositMaxWon", "monthlyRentMaxWon"))) {
            throw ApiException.invalidAiResponse();
        }
        RentalType rentalType = null;
        if (budget.get("rentalType") != null) {
            if (!(budget.get("rentalType") instanceof String name)) throw ApiException.invalidAiResponse();
            try { rentalType = RentalType.valueOf(name); }
            catch (IllegalArgumentException exception) { throw ApiException.invalidAiResponse(); }
        }
        return new Budget(rentalType, amount(budget.get("jeonseMaxWon")),
                amount(budget.get("depositMaxWon")), amount(budget.get("monthlyRentMaxWon")));
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) throw ApiException.invalidAiResponse();
        try { return mapper.readValue(json, Object.class); }
        catch (RuntimeException exception) { throw ApiException.invalidAiResponse(); }
    }

    private Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> object) return object;
        throw ApiException.invalidAiResponse();
    }

    private Long amount(Object value) {
        if (value == null) return null;
        if (value instanceof Integer amount) return amount.longValue();
        if (value instanceof Long amount) return amount;
        if (value instanceof BigInteger amount) {
            try { return amount.longValueExact(); }
            catch (ArithmeticException exception) { throw ApiException.invalidAiResponse(); }
        }
        throw ApiException.invalidAiResponse();
    }
}
