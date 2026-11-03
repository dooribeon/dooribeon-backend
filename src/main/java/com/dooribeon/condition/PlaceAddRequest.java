package com.dooribeon.condition;

import jakarta.validation.constraints.NotNull;

/** 서버가 새 장소 id를 발급한다. 오래된 화면의 추가는 expected로 차단한다. */
public record PlaceAddRequest(@NotNull Conditions expected) {}
