package com.dooribeon.recommend;

import com.dooribeon.recommend.CandidateFilterService.CandidateSearch;

import java.util.List;

/** 탐색 화면용: 추천 과정에서 걸러지는 동네들 (카카오 API 호출 없음) */
public record CandidatesResponse(
        int totalNeighborhoods,     // 전체 동네 수 (313)
        List<Spot> budgetPassed,    // 예산 필터 통과 동네
        List<Spot> candidates       // 그중 거점에서 가까운 후보 20곳
) {
    /** 지도에 찍을 동네 하나. id는 프론트 결과 화면과 같은 "구-동" 형식 */
    public record Spot(String id, String name, double lat, double lng) {
        static Spot of(CandidateNeighborhood c) {
            var n = c.neighborhood();
            return new Spot(n.gu() + "-" + n.dong(), n.dong(), n.lat(), n.lng());
        }
    }

    static CandidatesResponse of(CandidateSearch search) {
        return new CandidatesResponse(
                search.totalNeighborhoods(),
                search.budgetPassed().stream().map(Spot::of).toList(),
                search.candidates().stream().map(Spot::of).toList());
    }
}