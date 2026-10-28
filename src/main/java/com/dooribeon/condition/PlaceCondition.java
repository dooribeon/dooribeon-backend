package com.dooribeon.condition;

/** 특정 거점 또는 주변 시설 요구 하나. id는 서버가 부여하고 위치는 검색·선택 후 채운다. */
public record PlaceCondition(
        String id,
        String label,
        TargetType targetType,
        String query,
        PlaceLocation location,
        Importance importance,
        Transport transport,
        Integer maxMinutes,
        VisitFrequency frequency
) {
    public enum TargetType { FIXED_PLACE, NEARBY_FACILITY }
    public enum Importance { REQUIRED, HIGH, MEDIUM, LOW }
    public enum Transport { PUBLIC_TRANSIT, WALK }

    public PlaceCondition withImportance(Importance value) {
        return new PlaceCondition(id, label, targetType, query, location, value,
                transport, maxMinutes, frequency);
    }
}
