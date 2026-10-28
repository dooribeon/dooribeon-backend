package com.dooribeon.condition;

import java.util.List;

/** 입력 검사 결과. 질문과 추천 입력 준비 여부는 서버가 결정한다. */
public record ConditionCheckResult(
        Conditions conditions,
        List<Issue> issues,
        List<String> notices
) {
    public ConditionCheckResult {
        issues = List.copyOf(issues);
        notices = List.copyOf(notices);
    }

    /** 입력 준비 검사이며 실제 경로·데이터 조회 성공이나 추천 결과를 보장하지 않는다. */
    public boolean readyForRecommendation() {
        return issues.isEmpty();
    }

    public String nextQuestion() {
        return issues.isEmpty() ? null : issues.getFirst().message();
    }

    /** path는 공통 조건 객체를 기준으로 한 경로다. */
    public record Issue(String path, Kind kind, String message) {}

    public enum Kind { MISSING, INVALID, NEEDS_CONFIRMATION, UNSUPPORTED }
}
