package com.dooribeon.condition.place;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** 좌표를 직접 받지 않고 서버가 반환한 후보 번호만 받는다. */
public record PlaceSelectionRequest(@NotNull UUID searchId, @NotNull UUID candidateId) {}
