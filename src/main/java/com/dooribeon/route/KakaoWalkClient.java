package com.dooribeon.route;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
public class KakaoWalkClient {

    private final RestClient restClient;

    public KakaoWalkClient(RestClient.Builder builder,
                           @Value("${kakao.rest-key}") String restKey) {
        this.restClient = builder
                .baseUrl("https://dapi.kakao.com")
                .defaultHeader("Authorization", "KakaoAK " + restKey)
                .build();
    }

    /** 출발 → 도착 도보 경로. 경로가 없으면 빈 값 */
    public Optional<WalkInfo> getWalk(double startLat, double startLng, double endLat, double endLng) {
        KakaoWalkResponse response = restClient.get()
                .uri(uri -> uri.path("/v2/routing/walk")
                        .queryParam("start_x", startLng)   // x = 경도
                        .queryParam("start_y", startLat)   // y = 위도
                        .queryParam("end_x", endLng)
                        .queryParam("end_y", endLat)
                        .build())
                .retrieve()
                .body(KakaoWalkResponse.class);

        if (response == null || !"OK".equals(response.status()) || response.route() == null) {
            return Optional.empty();
        }

        WalkProperties p = response.route().properties();
        return Optional.of(new WalkInfo(p.totalDistance(), (int) Math.round(p.totalTime() / 60.0)));
    }

    // ---- 카카오 응답 모양 그대로 (필요한 필드만) ----
    record KakaoWalkResponse(String status, WalkRoute route) {}
    record WalkRoute(WalkProperties properties) {}
    record WalkProperties(int totalDistance, int totalTime) {}
}