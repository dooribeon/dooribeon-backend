package com.dooribeon.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.dooribeon.global.ApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import tools.jackson.databind.JsonNode;

@Component
public class KakaoWalkClient {

    private final RestClient restClient;
    private final String restKey;

    public KakaoWalkClient(@Qualifier("kakaoRoutingRestClient") RestClient client,
                           @Value("${kakao.rest-key}") String restKey) {
        this.restClient = client;
        this.restKey = restKey;
    }

    /** 출발 → 도착 도보 경로. 경로가 없으면 빈 값 */
    public Optional<WalkInfo> getWalk(double startLat, double startLng, double endLat, double endLng) {
        KakaoWalkResponse response = request(startLat, startLng, endLat, endLng).body(KakaoWalkResponse.class);
        if (response == null || !"OK".equals(response.status()) || response.route() == null) {
            return Optional.empty();
        }
        WalkProperties p = response.route().properties();
        return Optional.of(new WalkInfo(p.totalDistance(), (int) Math.round(p.totalTime() / 60.0)));
    }

    /** 도보도 원본 초 단위로 비교한다. 실패를 0분으로 채우지 않는다. */
    public OptionalInt getTravelSeconds(double startLat, double startLng, double endLat, double endLng) {
        TimeResponse response = request(startLat, startLng, endLat, endLng).body(TimeResponse.class);
        if (response == null || response.status() == null) throw KakaoRouteClient.invalidResponse();
        if (Set.of("START_LINK_NOT_FOUND", "END_LINK_NOT_FOUND", "TOO_FAR_AWAY", "ROUTE_RESULT_NOT_FOUND")
                .contains(response.status())) return OptionalInt.empty();
        if ("SAME_POINT".equals(response.status())) return OptionalInt.of(0);
        if (!"OK".equals(response.status()) || response.route() == null || response.route().properties() == null) {
            throw KakaoRouteClient.invalidResponse();
        }
        return OptionalInt.of(KakaoRouteClient.seconds(response.route().properties().totalTime()));
    }

    private RestClient.ResponseSpec request(double startLat, double startLng, double endLat, double endLng) {
        if (restKey == null || restKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_NOT_CONFIGURED",
                    "경로 검색 키가 아직 설정되지 않았어요. 서버의 카카오 REST API 키를 확인해 주세요.");
        }
        return restClient.get()
                .uri(uri -> uri.path("/v2/routing/walk")
                        .queryParam("start_x", startLng)   // x = 경도
                        .queryParam("start_y", startLat)   // y = 위도
                        .queryParam("end_x", endLng)
                        .queryParam("end_y", endLat)
                        .build())
                .header("Authorization", "KakaoAK " + restKey)
                .retrieve();
    }

    // ---- 카카오 응답 모양 그대로 (필요한 필드만) ----
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoWalkResponse(String status, WalkRoute route) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WalkRoute(WalkProperties properties) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WalkProperties(int totalDistance, int totalTime) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TimeResponse(String status, TimeRoute route) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TimeRoute(TimeProperties properties) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TimeProperties(JsonNode totalTime) {}
}
