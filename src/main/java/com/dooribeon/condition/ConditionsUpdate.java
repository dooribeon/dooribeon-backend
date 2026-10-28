package com.dooribeon.condition;

import java.util.List;
import java.util.Set;

/** 이번 답변의 변경분만 담는다. 목록에 없는 필드는 유지하고, 목록에 있는 null은 비운다. */
public record ConditionsUpdate(ConditionsExtraction values, Set<String> updatedFields,
                               List<PlaceUpdate> placeUpdates, Set<String> resolvedIssueIds) {
    public ConditionsUpdate {
        updatedFields = Set.copyOf(updatedFields);
        placeUpdates = List.copyOf(placeUpdates);
        resolvedIssueIds = Set.copyOf(resolvedIssueIds);
    }
    public record PlaceUpdate(String id, Action action, Set<String> updatedFields,
                              ConditionsExtraction.ExtractedPlace values) {
        public PlaceUpdate { updatedFields = Set.copyOf(updatedFields); }
    }
    public enum Action { UPDATE, REQUEST_DELETE }
}
