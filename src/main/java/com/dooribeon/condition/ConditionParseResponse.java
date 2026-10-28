package com.dooribeon.condition;

import java.util.List;

/** budgetComplete는 필수 예산 완료만 뜻한다. 전체 추천 준비 완료를 뜻하지 않는다. */
public record ConditionParseResponse(
        Budget budget,
        boolean budgetComplete,
        List<String> missingFields,
        List<String> questions
) {}
