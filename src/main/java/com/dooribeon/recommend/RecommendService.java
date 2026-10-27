package com.dooribeon.recommend;

import com.dooribeon.recommend.RecommendCondition.Place;
import com.dooribeon.recommend.RecommendResult.PlaceRoute;
import com.dooribeon.route.KakaoRouteClient;
import com.dooribeon.route.RouteInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendService {

    private static final int TOP_N = 5;

    private final CandidateFilterService candidateFilterService;
    private final KakaoRouteClient kakaoRouteClient;

    public List<RecommendResult> recommend(RecommendCondition condition) {
        List<CandidateNeighborhood> candidates = candidateFilterService.findCandidates(condition);

        List<Scored> scored = new ArrayList<>();
        for (CandidateNeighborhood c : candidates) {
            scoreNeighborhood(c, condition).ifPresent(scored::add);
        }

        scored.sort(Comparator.comparingDouble(Scored::weeklyMinutes));

        List<RecommendResult> results = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_N, scored.size()); i++) {
            results.add(toResult(i + 1, scored.get(i)));
        }
        return results;
    }

    /** 동네 1곳 : 거점마다 가장 빠른 경로를 찾고 일주일 이동 시간 계산. 경로 없는 거점이 있으면 제외 */
    private Optional<Scored> scoreNeighborhood(CandidateNeighborhood c, RecommendCondition condition) {
        var n = c.neighborhood();
        List<PlaceRoute> routes = new ArrayList<>();
        double weekly = 0;

        for (Place p : condition.places()) {
            Optional<RouteInfo> fastest = findFastestRoute(n.lat(), n.lng(), p.lat(), p.lng());
            if (fastest.isEmpty()) {
                log.info("경로 없음 : {} → {}", n.key(), p.label());
                return Optional.empty();
            }
            RouteInfo r = fastest.get();
            routes.add(new PlaceRoute(p.label(), r.totalMinutes(), r.transfers()));
            weekly += r.totalMinutes() * p.visitsPerWeek() * 2;   // 왕복
        }
        return Optional.of(new Scored(c, weekly, routes));
    }

    /** 카카오가 준 경로 중 총 시간이 가장 짧은 것 */
    private Optional<RouteInfo> findFastestRoute(double fromLat, double fromLng, double toLat, double toLng) {
        try {
            return kakaoRouteClient.getRoutes(fromLat, fromLng, toLat, toLng).stream()
                    .min(Comparator.comparingInt(RouteInfo::totalMinutes));
        } catch (Exception e) {
            log.warn("경로 조회 실패 : {}", e.getMessage());
            return Optional.empty();
        }
    }

    private RecommendResult toResult(int rank, Scored s) {
        var c = s.candidate();
        var n = c.neighborhood();
        return new RecommendResult(
                rank, n.gu(), n.dong(), n.lat(), n.lng(),
                (int) Math.round(s.weeklyMinutes()),
                c.totalContracts(), c.budgetContracts(),
                (int) Math.round(c.budgetRatio() * 100),
                s.routes());
    }

    private record Scored(CandidateNeighborhood candidate, double weeklyMinutes, List<PlaceRoute> routes) {}
}