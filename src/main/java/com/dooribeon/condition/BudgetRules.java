package com.dooribeon.condition;

import com.dooribeon.global.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class BudgetRules {
    private static final long MAX_SAFE_MONEY = 9007199254740991L;

    public ConditionParseResponse check(Budget budget) {
        if (budget == null) throw ApiException.invalidAiResponse();
        checkAmount(budget.jeonseMaxWon());
        checkAmount(budget.depositMaxWon());
        checkAmount(budget.monthlyRentMaxWon());
        if (budget.rentalType() == RentalType.JEONSE) {
            if (budget.depositMaxWon() != null || budget.monthlyRentMaxWon() != null) {
                throw ApiException.invalidAiResponse();
            }
        } else if (budget.jeonseMaxWon() != null) {
            throw ApiException.invalidAiResponse();
        }

        List<String> missing = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        if (budget.rentalType() == null) {
            addIfMissing(null, "rentalType", "전세와 월세 중 어떤 방식으로 찾고 있나요?", missing, questions);
        } else if (budget.rentalType() == RentalType.JEONSE) {
            addIfMissing(budget.jeonseMaxWon(), "jeonseMaxWon", "전세금은 최대 얼마까지 생각하고 있나요?", missing, questions);
        } else {
            addIfMissing(budget.depositMaxWon(), "depositMaxWon", "보증금은 최대 얼마까지 생각하고 있나요?", missing, questions);
            addIfMissing(budget.monthlyRentMaxWon(), "monthlyRentMaxWon", "관리비를 제외한 월세는 최대 얼마까지 생각하고 있나요?", missing, questions);
        }
        return new ConditionParseResponse(budget, missing.isEmpty(), List.copyOf(missing), List.copyOf(questions));
    }

    private void checkAmount(Long amount) {
        if (amount != null && (amount < 0 || amount > MAX_SAFE_MONEY)) {
            throw ApiException.invalidAiResponse();
        }
    }

    private void addIfMissing(Object value, String field, String question, List<String> missing, List<String> questions) {
        if (value == null) {
            missing.add("budget." + field);
            questions.add(question);
        }
    }
}
