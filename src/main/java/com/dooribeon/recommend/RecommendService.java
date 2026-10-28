package com.dooribeon.recommend;

import com.dooribeon.condition.Conditions;
import com.dooribeon.condition.PlaceCondition;
import com.dooribeon.condition.PlaceCondition.Importance;
import com.dooribeon.condition.PlaceCondition.Transport;
import com.dooribeon.recommend.RecommendResult.PlaceRoute;
import com.dooribeon.route.KakaoRouteClient;
import com.dooribeon.route.KakaoWalkClient;
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
    private final KakaoWalkClient kakaoWalkClient;

    public List<RecommendResult> recommend(Conditions conditions) {
        List<CandidateNeighborhood> candidates = candidateFilterService.findCandidates(conditions);

        List<Scored> scored = new ArrayList<>();
        for (CandidateNeighborhood c : candidates) {
            scoreNeighborhood(c, conditions).ifPresent(scored::add);
        }

        scored.sort(Comparator.comparingDouble(Scored::score));   // 점수 작은 순

        List<RecommendResult> results = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_N, scored.size()); i++) {
            results.add(toResult(i + 1, scored.get(i)));
        }
        return results;
    }

    /** 동네 1곳 : 거점마다 편도 시간 조회 → 시간 조건 검사 → 점수 합산. 경로가 없거나 시간 조건 밖이면 제외 */
    private Optional<Scored> scoreNeighborhood(CandidateNeighborhood c, Conditions conditions) {
        var n = c.neighborhood();
        List<PlaceRoute> routes = new ArrayList<>();
        double score = 0;

        for (PlaceCondition p : conditions.places()) {
            Transport transport = p.transport() != null ? p.transport() : Transport.PUBLIC_TRANSIT;
            ImportancePolicy policy = ImportancePolicy.of(p.importance());

            Optional<Trip> trip = findTrip(transport, n.lat(), n.lng(), p.location().lat(), p.location().lng());
            if (trip.isEmpty()) {
                log.info("경로 없음 : {} → {}", n.key(), name(p));
                return Optional.empty();
            }

            int minutes = trip.get().minutes();
            if (!policy.allows(minutes, p.maxMinutes())) {
                log.info("시간 초과 : {} → {} {}분 (기준 {}분 + 여유 {}분)",
                        n.key(), name(p), minutes, p.maxMinutes(), policy.getExtraMinutes());
                return Optional.empty();
            }

            routes.add(new PlaceRoute(name(p), Importance.valueOf(policy.name()), transport,
                    minutes, trip.get().transfers()));
            score += minutes * policy.getWeight();
        }
        return Optional.of(new Scored(c, score, routes));
    }

    /** 이동수단에 맞는 API로 편도 시간 조회. 대중교통은 가장 빠른 경로 */
    private Optional<Trip> findTrip(Transport transport,
                                    double fromLat, double fromLng, double toLat, double toLng) {
        try {
            if (transport == Transport.WALK) {
                return kakaoWalkClient.getWalk(fromLat, fromLng, toLat, toLng)
                        .map(w -> new Trip(w.minutes(), 0));
            }
            return kakaoRouteClient.getRoutes(fromLat, fromLng, toLat, toLng).stream()
                    .min(Comparator.comparingInt(RouteInfo::totalMinutes))
                    .map(r -> new Trip(r.totalMinutes(), r.transfers()));
        } catch (Exception e) {
            log.warn("경로 조회 실패 : {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 화면에 보여줄 거점 이름 */
    private String name(PlaceCondition p) {
        if (p.label() != null) return p.label();
        return p.location().placeName() != null ? p.location().placeName() : p.location().address();
    }

    private RecommendResult toResult(int rank, Scored s) {
        var c = s.candidate();
        var n = c.neighborhood();
        return new RecommendResult(
                rank, n.gu(), n.dong(), n.lat(), n.lng(),
                (int) Math.round(s.score()),
                c.totalContracts(), c.budgetContracts(),
                (int) Math.round(c.budgetRatio() * 100),
                s.routes());
    }

    private record Trip(int minutes, int transfers) {}

    private record Scored(CandidateNeighborhood candidate, double score, List<PlaceRoute> routes) {}
}