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
            boolean frequencyUnclear,
            String areaHint
    ) {
        public ExtractedPlace {
            // 지역을 장소 이름 또는 업체 검색어에 다시 붙인 AI 응답도 같은 규칙으로 처리한다.
            if (areaHint != null && !areaHint.isBlank()) {
                for (String roleName : List.of("회사", "직장", "학교", "본가", "부모님 집", "여자친구", "남자친구", "친구", "애인", "연인", "교회")) {
                    String prefix = areaHint + " " + roleName;
                    String suffix = roleName + " " + areaHint;
                    if (prefix.equals(label) || suffix.equals(label)) label = roleName;
                    if (prefix.equals(query) || suffix.equals(query)) query = null;
                }
                if (areaHint.equals(query)) query = null;
            }
        }

        public ExtractedPlace(String label, PlaceCondition.TargetType targetType, String query, Role role,
                              PlaceCondition.Importance importance, boolean importanceSpecified,
                              PlaceCondition.Transport transport, Integer maxMinutes, VisitFrequency frequency,
                              boolean frequencyUnclear) {
            this(label, targetType, query, role, importance, importanceSpecified, transport, maxMinutes,
                    frequency, frequencyUnclear, null);
        }
    }

    public enum Role { WORK, SCHOOL }

    /** field는 maxDeposit, maxRent, budget, conditions 중 하나다. 질문 문구는 서버가 정한다. */
    public record Clarification(String field, Reason reason, String sourceText) {
        public Clarification(String field, Reason reason) { this(field, reason, null); }
    }

    public enum Reason {
        AMOUNT_UNIT_REQUIRED,
        INTEGER_MANWON_REQUIRED,
        NET_RENT_REQUIRED,
        UNSUPPORTED_RENTAL_TYPE,
        AMBIGUOUS_INPUT
    }
}
