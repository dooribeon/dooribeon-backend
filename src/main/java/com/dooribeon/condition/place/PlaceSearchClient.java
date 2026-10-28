package com.dooribeon.condition.place;

import com.dooribeon.condition.PlaceLocation;
import java.util.List;

public interface PlaceSearchClient {
    SearchResult search(String query, SearchType type, int page);
    enum SearchType { KEYWORD, ADDRESS }
    record SearchResult(List<PlaceLocation> locations, boolean hasNext) {
        public SearchResult { locations = List.copyOf(locations); }
    }
}
