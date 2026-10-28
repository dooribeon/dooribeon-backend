package com.dooribeon.condition;

/** 장소 검색·사용자 선택 결과. 좌표만으로 검색 출처나 사용자 선택을 증명하지는 않는다. */
public record PlaceLocation(
        String placeId,
        String placeName,
        String address,
        Double lat,
        Double lng
) {}
