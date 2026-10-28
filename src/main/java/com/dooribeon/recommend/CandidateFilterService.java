package com.dooribeon.recommend;

import com.dooribeon.condition.Conditions;
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

    public List<CandidateNeighborhood> findCandidates(Conditions conditions) {
        return neighborhoodRepository.findAll().stream()
                .map(n -> evaluate(n, conditions))
                .filter(c -> c.totalContracts() >= MIN_TOTAL_CONTRACTS
                        && c.budgetRatio() >= MIN_BUDGET_RATIO)                        // ① 예산 필터
                .sorted(Comparator.comparingDouble(CandidateNeighborhood::weightedDistanceKm)) // ② 가까운 순
                .limit(MAX_CANDIDATES)
                .toList();
    }

    private CandidateNeighborhood evaluate(Neighborhood n, Conditions conditions) {
        List<RentContract> contracts = rentContractRepository.findByNeighborhood(n.key());

        List<RentContract> inBudget = contracts.stream()
                .filter(c -> c.deposit() <= conditions.maxDeposit() && c.rent() <= conditions.maxRent())
                .toList();

        return new CandidateNeighborhood(n, contracts.size(), inBudget.size(),
                median(inBudget.stream().map(RentContract::deposit).toList()),
                median(inBudget.stream().map(RentContract::rent).toList()),
                weightedDistanceKm(n, conditions));
    }

    /** 중간값 — 정렬해서 가운데. 비어 있으면 0 */
    private int median(List<Integer> values) {
        if (values.isEmpty()) return 0;
        List<Integer> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    /** Σ (중요도 가중치 × 거점까지 직선거리) — 중요한 곳이 가까울수록 작아짐 */
    private double weightedDistanceKm(Neighborhood n, Conditions conditions) {
        return conditions.places().stream()
                .mapToDouble(p -> ImportancePolicy.of(p.importance()).getWeight()
                        * GeoUtils.distanceMeters(n.lat(), n.lng(),
                        p.location().lat(), p.location().lng()) / 1000)
                .sum();
    }
}