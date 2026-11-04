package com.dooribeon.condition;

import jakarta.validation.constraints.NotNull;

/** 구버전 카드의 확인 문구는 선택 입력으로 받아 점진적인 프론트 교체를 지원한다. */
public record PlaceDeleteRequest(@NotNull Conditions expected, String confirmationText) {
    public PlaceDeleteRequest(Conditions expected) { this(expected, null); }
}
