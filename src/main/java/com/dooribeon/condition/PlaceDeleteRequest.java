package com.dooribeon.condition;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PlaceDeleteRequest(@NotNull Conditions expected, @NotBlank String confirmationText) {}
