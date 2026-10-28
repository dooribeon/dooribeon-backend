package com.dooribeon.condition.place;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PlaceSearchRequest(@NotBlank @Size(max = 200) String query,
                                 @NotNull PlaceSearchClient.SearchType type,
                                 @NotNull @Min(1) @Max(45) Integer page) {}
