package com.dooribeon.route;

public record RouteInfo(
        int totalMinutes, // 총 소요 시간 (앞뒤 도보 포함)
        int transfers, // 환승 횟수
        int transferWalkMinutes, // 환승할 때 걷는 시간
        double boardLat, // 처음 타는 정류장 위도
        double boardLng, // 처음 타는 정류장 경도
        double alightLat, // 마지막 내리는 정류장 위도
        double alightLng // 마지막 내리는 정류장 경도
) {
}
