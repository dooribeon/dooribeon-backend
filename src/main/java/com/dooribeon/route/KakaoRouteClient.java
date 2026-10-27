package com.dooribeon.route;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class KakaoRouteClient {

    private final RestClient restClient;

    public KakaoRouteClient(RestClient.Builder builder,
                            @Value("${kakao.rest-key}") String restKey) {
        this.restClient = builder
                .baseUrl("https://dapi.kakao.com")
                .defaultHeader("Authorization", "KakaoAK " + restKey)
                .build();
    }

    /** 출발 → 도착 대중교통 경로 목록 (카카오 추천순) */
    public List<RouteInfo> getRoutes(double startLat, double startLng, double endLat, double endLng) {
        KakaoTransitResponse response = restClient.get()
                .uri(uri -> uri.path("/v2/routing/publictraffic")
                        .queryParam("start_x", startLng)   // x = 경도
                        .queryParam("start_y", startLat)   // y = 위도
                        .queryParam("end_x", endLng)
                        .queryParam("end_y", endLat)
                        .build())
                .retrieve()
                .body(KakaoTransitResponse.class);

        if (response == null || !"OK".equals(response.status()) || response.routes() == null) {
            return List.of();   // 경로 없음
        }

        return response.routes().stream()
                .map(this::toRouteInfo)
                .toList();
    }

    private RouteInfo toRouteInfo(Route route) {
        List<Step> rides = route.steps().stream()
                .filter(s -> !"WALKING".equals(s.properties().type()))
                .toList();

        int transferWalkSeconds = route.steps().stream()
                .filter(s -> "WALKING".equals(s.properties().type()))
                .mapToInt(s -> s.properties().time())
                .sum();

        // path.points 는 [경도, 위도] 순서
        List<Double> board = rides.getFirst().path().points().getFirst();
        List<Double> alight = rides.getLast().path().points().getLast();

        return new RouteInfo(
                (int) Math.round(route.properties().totalTime() / 60.0),
                route.properties().transfers(),
                (int) Math.round(transferWalkSeconds / 60.0),
                board.get(1), board.get(0),
                alight.get(1), alight.get(0)
        );
    }

    // ---- 카카오 응답 모양 그대로 (필요한 필드만) ----
    record KakaoTransitResponse(String status, List<Route> routes) {}
    record Route(RouteProperties properties, List<Step> steps) {}
    record RouteProperties(int totalTime, int transfers) {}
    record Step(StepProperties properties, Path path) {}
    record StepProperties(String type, int time) {}
    record Path(List<List<Double>> points) {}
}