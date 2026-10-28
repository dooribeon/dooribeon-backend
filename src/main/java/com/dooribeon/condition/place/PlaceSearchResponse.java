package com.dooribeon.condition.place;

import com.dooribeon.condition.PlaceLocation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlaceSearchResponse(UUID searchId, String placeId, String query, PlaceSearchClient.SearchType type,
                                  int page, boolean hasNext, Instant expiresAt, List<Candidate> candidates) {
    public PlaceSearchResponse { candidates = List.copyOf(candidates); }
    public record Candidate(UUID candidateId, PlaceLocation location) {}
}
