package com.dooribeon.condition;

/** AI 호출 담당과 조건 관리 담당이 연결할 작은 인터페이스. */
public interface BudgetExtractor {
    BudgetExtraction extract(String text);

    BudgetUpdate extractUpdate(String text, Budget currentBudget, String lastQuestion);

    BudgetUpdate extractUpdate(String text, Budget currentBudget, String lastQuestion, String pendingUserText);
}
