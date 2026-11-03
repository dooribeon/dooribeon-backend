package com.dooribeon.condition;

import jakarta.validation.constraints.NotNull;

/** expected가 현재 조건과 다르면 오래된 카드의 저장을 거절한다. 위치는 이 요청으로 바꾸지 않는다. */
public record ConditionEditRequest(@NotNull Conditions expected, @NotNull Conditions values) {}
