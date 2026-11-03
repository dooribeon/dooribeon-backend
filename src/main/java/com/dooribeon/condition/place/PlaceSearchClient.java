package com.dooribeon.condition.place;

import com.dooribeon.condition.PlaceLocation;
import java.util.List;

public interface PlaceSearchClient {
    SearchResult search(String query, SearchType type, int page);
    enum SearchType { KEYWORD, ADDRESS }
    record SearchResult(List<PlaceLocation> locations, boolean hasNext, java.util.Map<PlaceLocation, String> regionAddresses) {
        public SearchResult {
            locations = List.copyOf(locations);
            regionAddresses = java.util.Map.copyOf(regionAddresses);
        }
        public SearchResult(List<PlaceLocation> locations, boolean hasNext) {
            this(locations, hasNext, java.util.Map.of());
        }
    }
}
