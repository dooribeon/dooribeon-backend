package com.dooribeon.recommend;

import com.dooribeon.neighborhood.Neighborhood;

/** 필터를 통과한 후보 동네 */
public record CandidateNeighborhood(
        Neighborhood neighborhood,
        int totalContracts,            // 전체 계약 수
        int budgetContracts,           // 그중 예산까지 맞는 계약 수
        int medianDeposit,             // 예산 맞는 계약의 보증금 중간값 (만원)
        int medianRent,                // 예산 맞는 계약의 월세 중간값 (만원)
        double weightedDistanceKm      // 중요도 반영 직선거리
) {
    /** 예산 맞는 계약 비율 (0 ~ 1) */
    public double budgetRatio() {
        return totalContracts == 0 ? 0 : (double) budgetContracts / totalContracts;
    }
}