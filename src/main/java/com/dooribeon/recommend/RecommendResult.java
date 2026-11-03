package com.dooribeon.recommend;

import com.dooribeon.condition.PlaceCondition.Importance;
import com.dooribeon.condition.PlaceCondition.Transport;

import java.util.List;

/** 추천 결과 동네 1곳 */
public record RecommendResult(
        int rank,
        String gu,
        String dong,
        double lat,
        double lng,
        int score,                  // Σ(편도 분 × 중요도 가중치), 작을수록 좋음
        int totalContracts,         // 이 동네 월세 계약 수 (2025)
        int budgetContracts,        // 그중 예산 맞는 계약 수
        int budgetPercent,          // 예산 맞는 비율 (%)
        int medianDeposit,          // 예산 안 계약 보증금 중간값 (만원)
        int medianRent,             // 예산 안 계약 월세 중간값 (만원)
        List<PlaceRoute> routes     // 거점별 경로
) {
    /** 거점 1곳까지의 경로 */
    public record PlaceRoute(
            String placeId,         // 조건의 거점 id (프론트에서 조건과 맞춰볼 때 사용)
            String label,           // "회사", "본가" ...
            Importance importance,  // 중요도 (미입력은 MEDIUM)
            Transport transport,    // 계산에 쓴 이동수단
            int minutes,            // 편도 시간
            Integer maxMinutes,     // 사용자가 정한 편도 기준 (미설정이면 null)
            int transfers           // 환승 횟수 (도보는 0)
    ) {}
}