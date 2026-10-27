package com.dooribeon.route;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 경로 클라이언트 확인용 임시 API. 추천 기능 만들면 삭제 */
@RestController
@RequestMapping("/api/test/route")
@RequiredArgsConstructor
public class RouteTestController {

    private final KakaoRouteClient kakaoRouteClient;
    private final KakaoWalkClient kakaoWalkClient;

    @GetMapping
    public List<RouteSummary> test(
            @RequestParam(defaultValue = "37.5964") double startLat,   // 경희대 (Colab 값으로 바꾸기)
            @RequestParam(defaultValue = "127.0526") double startLng,
            @RequestParam(defaultValue = "37.4998") double endLat,     // 땀땀 본점 (Colab 값으로 바꾸기)
            @RequestParam(defaultValue = "127.0288") double endLng) {

        return kakaoRouteClient.getRoutes(startLat, startLng, endLat, endLng).stream()
                .limit(3)
                .map(r -> {
                    WalkInfo first = kakaoWalkClient
                            .getWalk(startLat, startLng, r.boardLat(), r.boardLng())
                            .orElse(new WalkInfo(0, 0));
                    WalkInfo last = kakaoWalkClient
                            .getWalk(r.alightLat(), r.alightLng(), endLat, endLng)
                            .orElse(new WalkInfo(0, 0));

                    int walkMinutes = first.minutes() + last.minutes() + r.transferWalkMinutes();

                    return new RouteSummary(r.totalMinutes(), r.transfers(), walkMinutes,
                            first.distanceMeters(), last.distanceMeters());
                })
                .toList();
    }

    record RouteSummary(int totalMinutes, int transfers, int walkMinutes,
                        int firstWalkMeters, int lastWalkMeters) {}
}