package com.dooribeon.condition;

import jakarta.validation.constraints.NotNull;

public record PlaceDeleteRequest(@NotNull Conditions expected) {}
