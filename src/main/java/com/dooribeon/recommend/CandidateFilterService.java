package com.dooribeon.recommend;

import com.dooribeon.global.util.GeoUtils;
import com.dooribeon.neighborhood.Neighborhood;
import com.dooribeon.neighborhood.NeighborhoodRepository;
import com.dooribeon.neighborhood.RentContract;
import com.dooribeon.neighborhood.RentContractRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CandidateFilterService {

    private static final int MIN_TOTAL_CONTRACTS = 30;     // 전체 계약 최소 30건 (비율을 믿을 수 있는 최소 표본)
    private static final double MIN_BUDGET_RATIO = 0.1;    // 예산 맞는 계약 비율 최소 10%
    private static final int MAX_CANDIDATES = 20;          // 경로 API로 넘길 후보 수

    private final NeighborhoodRepository neighborhoodRepository;
    private final RentContractRepository rentContractRepository;

    public List<CandidateNeighborhood> findCandidates(RecommendCondition condition) {
        return neighborhoodRepository.findAll().stream()
                .map(n -> evaluate(n, condition))
                .filter(c -> c.totalContracts() >= MIN_TOTAL_CONTRACTS
                        && c.budgetRatio() >= MIN_BUDGET_RATIO)                        // ① 예산 필터
                .sorted(Comparator.comparingDouble(CandidateNeighborhood::weightedDistanceKm)) // ② 가까운 순
                .limit(MAX_CANDIDATES)
                .toList();
    }

    private CandidateNeighborhood evaluate(Neighborhood n, RecommendCondition condition) {
        List<RentContract> contracts = rentContractRepository.findByNeighborhood(n.key());

        int budgetCount = (int) contracts.stream()
                .filter(c -> c.deposit() <= condition.maxDeposit() && c.rent() <= condition.maxRent())
                .count();

        return new CandidateNeighborhood(n, contracts.size(), budgetCount, weightedDistanceKm(n, condition));
    }

    /** Σ (주당 방문 횟수 × 거점까지 직선거리) — 자주 가는 곳이 가까울수록 작아짐 */
    private double weightedDistanceKm(Neighborhood n, RecommendCondition condition) {
        return condition.places().stream()
                .mapToDouble(p -> p.visitsPerWeek()
                        * GeoUtils.distanceMeters(n.lat(), n.lng(), p.lat(), p.lng()) / 1000)
                .sum();
    }
}