package com.dooribeon.condition;

import com.dooribeon.global.ApiException;
import java.util.List;
import java.util.regex.Pattern;

/** 단위 없는 짧은 답은 AI가 원 단위로 추측할 기회 없이 서버에서 확인한다. */
public final class AmountUnitPolicy {
    public static final String QUESTION = "금액 단위를 확인할게요. '100만 원' 또는 '1000000원'처럼 원 단위를 붙여 알려주세요.";
    private static final Pattern BARE_AMOUNT = Pattern.compile(
            "^\\s*[+-]?(?:[0-9][0-9,\\s.]*(?:[십백천만억조][0-9십백천만억조\\s.]*)?|[영공일이삼사오육칠팔구십백천만억조]+)"
            + "\\s*(?:정도)?\\s*(?:이야|야|이요|요|입니다)?[.!?]?\\s*$");
    private static final Pattern WON_AMOUNT = Pattern.compile("[0-9영공일이삼사오육칠팔구십백천만억조]\\s*원");
    private static final Pattern NO_DEPOSIT = Pattern.compile("무보증|보증금\\s*(?:은|이|도)?\\s*(?:없이|없)");

    private AmountUnitPolicy() {}

    public static boolean isBareAmountWithoutWon(String text) {
        return BARE_AMOUNT.matcher(text).matches();
    }

    public static Budget emptyBudget() { return new Budget(null, null, null, null); }

    public static BudgetExtraction enforce(String text, BudgetExtraction extraction) {
        if (unqualifiedMoney(text, extraction.budget())) {
            Budget budget = new Budget(extraction.budget().rentalType(), null, null, null);
            return new BudgetExtraction(budget, true);
        }
        return extraction;
    }

    public static boolean unqualifiedMoney(String text, Budget budget) {
        boolean hasAmount = budget.jeonseMaxWon() != null || budget.depositMaxWon() != null || budget.monthlyRentMaxWon() != null;
        if (!hasAmount || WON_AMOUNT.matcher(text).find()) return false;
        // 기존에 지원하던 '보증금 없이'는 숫자 단위 추측이 아닌 명시적인 보증금 없음이다.
        return !(Long.valueOf(0).equals(budget.depositMaxWon()) && budget.jeonseMaxWon() == null
                && budget.monthlyRentMaxWon() == null && NO_DEPOSIT.matcher(text).find());
    }

    public static ConditionParseResponse ask(ConditionParseResponse previous) {
        return new ConditionParseResponse(previous.budget(), false, previous.missingFields(), List.of(QUESTION));
    }

    public static void validateUnconfirmed(Budget budget) {
        if (budget.jeonseMaxWon() != null || budget.depositMaxWon() != null || budget.monthlyRentMaxWon() != null) {
            throw ApiException.invalidAiResponse();
        }
    }
}
