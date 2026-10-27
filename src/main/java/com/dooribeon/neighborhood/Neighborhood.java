package com.dooribeon.neighborhood;

/** 후보 동네 1개 — neighborhoods.csv 한 줄 */
public record Neighborhood(
        String gu,
        String dong,
        int contractCount,
        double lat,
        double lng
) {
    /** 동네를 구분하는 키 (예: "관악구 봉천동") */
    public String key() {
        return gu + " " + dong;
    }
}