package com.dooribeon.condition;

import java.util.List;

/** AI 최초 추출 결과. 서버 id·선택 위치·서비스 기본값은 아직 들어 있지 않다. */
public record ConditionsExtraction(
        Long maxDeposit,
        Long maxRent,
        List<ExtractedPlace> places,
        List<Clarification> clarifications,
        List<String> unhandledRequests
) {
    public ConditionsExtraction {
        places = List.copyOf(places);
        clarifications = List.copyOf(clarifications);
        unhandledRequests = List.copyOf(unhandledRequests);
    }

    public record ExtractedPlace(
            String label,
            PlaceCondition.TargetType targetType,
            String query,
            Role role,
            PlaceCondition.Importance importance,
            boolean importanceSpecified,
            PlaceCondition.Transport transport,
            Integer maxMinutes,
            VisitFrequency frequency,
            boolean frequencyUnclear
    ) {}

    public enum Role { WORK, SCHOOL }

    /** field는 maxDeposit, maxRent, budget, conditions 중 하나다. 질문 문구는 서버가 정한다. */
    public record Clarification(String field, Reason reason) {}

    public enum Reason {
        AMOUNT_UNIT_REQUIRED,
        INTEGER_MANWON_REQUIRED,
        NET_RENT_REQUIRED,
        UNSUPPORTED_RENTAL_TYPE,
        AMBIGUOUS_INPUT
    }
}
