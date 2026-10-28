package com.dooribeon.condition;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConditionParseRequest(@NotBlank @Size(max = 10000) String text) {}
