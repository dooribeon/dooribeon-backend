package com.dooribeon.recommend;

import com.dooribeon.condition.ConditionCheckResult.Issue;
import com.dooribeon.condition.PlaceCondition.Importance;
import com.dooribeon.condition.PlaceCondition.Transport;
import java.util.List;

/** 동네 하나의 조건 검사 결과. 추천 순위나 주간 이동 점수가 아니다. */
public record RecommendationCheckResponse(
        Status status,
        boolean rankingApplied,
        List<Issue> issues,
        String nextQuestion,
        List<String> notices,
        NeighborhoodCheck neighborhood,
        List<PlaceCheck> places
) {
    public RecommendationCheckResponse {
        issues = List.copyOf(issues);
        notices = List.copyOf(notices);
        places = List.copyOf(places);
    }

    public enum Status {
        INPUT_REQUIRED, NO_CONTRACT_DATA, NO_BUDGET_MATCH,
        REQUIRED_TIME_EXCEEDED, REQUIRED_ROUTE_UNAVAILABLE, CHECK_PASSED
    }

    public enum RouteStatus { FOUND, NOT_FOUND }

    public record NeighborhoodCheck(String key, double lat, double lng,
                                    int totalContracts, int budgetContracts) {}

    /** seconds는 제공자 원본 시간, minutes는 화면용 올림 값. 시간 상한이 없거나 경로가 없으면 withinLimit은 null. */
    public record PlaceCheck(String placeId, String label, Importance importance, Transport transport,
                             Integer maxMinutes, RouteStatus status, Integer seconds,
                             Integer minutes, Boolean withinLimit) {}
}
