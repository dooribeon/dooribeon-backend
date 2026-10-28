package com.dooribeon.condition;

import java.util.List;

/** 한 번 추출하는 API도 대화 API와 같은 조건·검사 형식을 사용한다. */
public record ConditionResultResponse(Conditions conditions, boolean readyForRecommendation,
                                      List<ConditionCheckResult.Issue> issues, String nextQuestion, List<String> notices) {
    public static ConditionResultResponse of(ConditionCheckResult result) {
        return new ConditionResultResponse(result.conditions(), result.readyForRecommendation(),
                result.issues(), result.nextQuestion(), result.notices());
    }
}
