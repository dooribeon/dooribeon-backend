package com.dooribeon.recommend;

import java.util.List;

/** 추천 결과 동네 1곳 */
public record RecommendResult(
        int rank,
        String gu,
        String dong,
        double lat,
        double lng,
        int weeklyMinutes,          // 일주일 이동 시간 (왕복, 방문 빈도 반영)
        int totalContracts,         // 이 동네 월세 계약 수 (2025)
        int budgetContracts,        // 그중 예산 맞는 계약 수
        int budgetPercent,          // 예산 맞는 비율 (%)
        List<PlaceRoute> routes     // 거점별 경로
) {
    /** 거점 1곳까지의 경로 */
    public record PlaceRoute(
            String label,           // "회사", "본가" ...
            int minutes,            // 편도 시간
            int transfers           // 환승 횟수
    ) {}
}