package com.dooribeon.condition;

/** 금액이 없는 것과, 단위 확인 때문에 아직 반영하지 않은 것을 구분한다. */
public record BudgetExtraction(Budget budget, boolean needsAmountUnitConfirmation) {
    public BudgetExtraction(Budget budget) { this(budget, false); }
}
