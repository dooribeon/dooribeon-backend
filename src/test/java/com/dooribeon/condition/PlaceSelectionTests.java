package com.dooribeon.condition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dooribeon.condition.place.*;
import com.dooribeon.global.ApiException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlaceSelectionTests {
    @Test
    void oldSearchCannotApplyAfterAnotherSearchMessageOrExpiryAndCannotCrossConversations() {
        var extractor = mock(ConditionsExtractor.class);
        var places = mock(PlaceSearchClient.class);
        var rules = new ConditionsRules();
        var extraction = new ConditionsExtractionService(extractor, rules);
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-10-28T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        var service = new ConditionConversationService(extractor, extraction, rules,
                new ConditionsMerger(extraction, rules), places, clock, Duration.ofMinutes(30), 10);
        var p = new ConditionsExtraction.ExtractedPlace("친구", PlaceCondition.TargetType.FIXED_PLACE, "여의도역", null,
                null, false, null, null, null, false);
        when(extractor.extract(anyString())).thenReturn(new ConditionsExtraction(1000L, 50L, List.of(p), List.of(), List.of()));
        when(extractor.extractUpdate(anyString(), any(), anyString())).thenReturn(ConditionConversationServiceTests.noChange());
        when(places.search(anyString(), any(), anyInt())).thenReturn(new PlaceSearchClient.SearchResult(
                List.of(new PlaceLocation("k1", "여의도역", "서울 영등포구", 37.52, 126.92)), false));
        var first = service.start("보증금 천만 원 월세 50만 원 친구 여의도역");
        var other = service.start("보증금 천만 원 월세 50만 원 친구 여의도역");
        String id = first.conditions().places().getFirst().id();
        var request = new PlaceSearchRequest("여의도역", PlaceSearchClient.SearchType.KEYWORD, 1);
        var oldSearch = service.searchPlace(first.conversationId(), id, request);
        var selection = new PlaceSelectionRequest(oldSearch.searchId(), oldSearch.candidates().getFirst().candidateId());
        service.searchPlace(first.conversationId(), id, request);
        assertStale(() -> service.selectPlace(first.conversationId(), id, selection));
        assertThatThrownBy(() -> service.selectPlace(other.conversationId(), id, selection)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("PLACE_NOT_FOUND"));
        var currentSearch = service.searchPlace(first.conversationId(), id, request);
        var currentSelection = new PlaceSelectionRequest(currentSearch.searchId(), currentSearch.candidates().getFirst().candidateId());
        assertThat(service.reply(first.conversationId(), "그대로").conditions().places().getFirst().location()).isNull();
        assertStale(() -> service.selectPlace(first.conversationId(), id, currentSelection));
        var expiring = service.searchPlace(first.conversationId(), id, request);
        when(clock.instant()).thenReturn(now.plus(Duration.ofMinutes(5)));
        assertStale(() -> service.selectPlace(first.conversationId(), id,
                new PlaceSelectionRequest(expiring.searchId(), expiring.candidates().getFirst().candidateId())));
    }

    private void assertStale(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("STALE_PLACE_SEARCH"));
    }
}
