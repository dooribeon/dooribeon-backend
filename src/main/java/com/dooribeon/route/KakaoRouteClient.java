package com.dooribeon.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.dooribeon.global.ApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

@Component
public class KakaoRouteClient {

    private final RestClient restClient;
    private final String restKey;

    public KakaoRouteClient(@Qualifier("kakaoRoutingRestClient") RestClient client,
                            @Value("${kakao.rest-key}") String restKey) {
        this.restClient = client;
        this.restKey = restKey;
    }

    /** 출발 → 도착 대중교통 경로 목록 (카카오 추천순) */
    public List<RouteInfo> getRoutes(double startLat, double startLng, double endLat, double endLng) {
        KakaoTransitResponse response = request(startLat, startLng, endLat, endLng)
                .body(KakaoTransitResponse.class);

        if (response == null || !"OK".equals(response.status()) || response.routes() == null) {
            return List.of();   // 기존 순위 API의 동작. 새 검사는 아래 메서드에서 오류를 구분한다.
        }

        return response.routes().stream()
                .map(this::toRouteInfo)
                .toList();
    }

    /** 시간 제한 검사에는 분 반올림 전의 초를 사용한다. 경로 없음과 비정상 응답을 구분한다. */
    public OptionalInt getFastestTravelSeconds(double startLat, double startLng, double endLat, double endLng) {
        TimeResponse response = request(startLat, startLng, endLat, endLng).body(TimeResponse.class);
        if (response == null || response.status() == null) throw invalidResponse();
        if (Set.of("NO_RESULTS", "STARTNODES_NULL", "ENDNODES_NULL").contains(response.status())) {
            return OptionalInt.empty();
        }
        if ("EQUAL_POINTS".equals(response.status())) return OptionalInt.of(0);
        if (!"OK".equals(response.status()) || response.routes() == null || response.routes().isEmpty()) {
            throw invalidResponse();
        }
        return response.routes().stream().mapToInt(route -> {
            if (route == null || route.properties() == null) throw invalidResponse();
            return seconds(route.properties().totalTime());
        }).min();
    }

    private RestClient.ResponseSpec request(double startLat, double startLng, double endLat, double endLng) {
        if (restKey == null || restKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_NOT_CONFIGURED",
                    "경로 검색 키가 아직 설정되지 않았어요. 서버의 카카오 REST API 키를 확인해 주세요.");
        }
        return restClient.get()
                .uri(uri -> uri.path("/v2/routing/publictraffic")
                        .queryParam("start_x", startLng)   // x = 경도
                        .queryParam("start_y", startLat)   // y = 위도
                        .queryParam("end_x", endLng)
                        .queryParam("end_y", endLat)
                        .build())
                .header("Authorization", "KakaoAK " + restKey)
                .retrieve();
    }

    static int seconds(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalidResponse();
        }
        return value.intValue();
    }

    static ApiException invalidResponse() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "ROUTE_INVALID_RESPONSE",
                "경로 제공자의 시간 정보를 확인하지 못했어요. 다시 시도해 주세요.");
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoTransitResponse(String status, List<Route> routes) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Route(RouteProperties properties, List<Step> steps) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RouteProperties(int totalTime, int transfers) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Step(StepProperties properties, Path path) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StepProperties(String type, int time) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Path(List<List<Double>> points) {}

    // 시간 검사에는 불필요한 정류장·경로 모양을 요구하지 않는다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TimeResponse(String status, List<TimeRoute> routes) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TimeRoute(TimeProperties properties) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TimeProperties(JsonNode totalTime) {}
}
