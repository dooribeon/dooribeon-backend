package com.dooribeon.recommend;

import java.util.List;

/**
 * 추천 조건 (임시 — 팀원과 조건 JSON 형식 확정 후 맞춰서 수정)
 */
public record RecommendCondition(
        List<Place> places,      // 거점 (최대 3곳)
        int maxDeposit,          // 보증금 상한 (만원)
        int maxRent            // 월세 상한 (만원)
) {
    /** 거점 1곳 */
    public record Place(
            String label,          // "회사", "본가" ...
            double lat,
            double lng,
            double visitsPerWeek   // 주당 방문 횟수 (월 2회 → 0.5)
    ) {}
}