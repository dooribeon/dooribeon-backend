package com.dooribeon.recommend;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 후보 필터 확인용 임시 API. 추천 API 만들면 삭제 */
@RestController
@RequestMapping("/api/test/candidates")
@RequiredArgsConstructor
public class CandidateTestController {

    private final CandidateFilterService candidateFilterService;

    @GetMapping
    public List<CandidateView> test(
            @RequestParam(defaultValue = "2000") int maxDeposit,
            @RequestParam(defaultValue = "70") int maxRent) {

        RecommendCondition condition = new RecommendCondition(
                List.of(
                        new RecommendCondition.Place("회사(여의도역)", 37.5216, 126.9243, 5),
                        new RecommendCondition.Place("본가(수원역)", 37.2659, 127.0000, 0.5)
                ),
                maxDeposit, maxRent);

        return candidateFilterService.findCandidates(condition).stream()
                .map(c -> new CandidateView(
                        c.neighborhood().key(),
                        c.totalContracts(),
                        c.budgetContracts(),
                        Math.round(c.budgetRatio() * 100),
                        Math.round(c.weightedDistanceKm() * 10) / 10.0))
                .toList();
    }

    record CandidateView(String name, int totalContracts, int budgetContracts,
                         long budgetPercent, double weightedKm) {}
}