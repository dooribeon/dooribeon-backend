package com.dooribeon.condition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.dooribeon.condition.place.*;
import com.dooribeon.global.ApiException;

class PersonalPlaceFlowTests {
    private static final String VISIT = "친구를 수원에서 자주만나";
    private static ConditionsExtraction.ExtractedPlace place(String label, String query, ConditionsExtraction.Role role) {
        return new ConditionsExtraction.ExtractedPlace(label, PlaceCondition.TargetType.FIXED_PLACE, query, role,
                null, false, role == null ? null : PlaceCondition.Transport.PUBLIC_TRANSIT, role == null ? null : 60, null, false);
    }

    @Test
    void screenshotFollowupsAddOneFriendAndPreserveWorkHomeAndOptionalFrequency() {
        var extractor = mock(ConditionsExtractor.class);
        var search = mock(PlaceSearchClient.class);
        var rules = new ConditionsRules();
        var extraction = new ConditionsExtractionService(extractor, rules);
        var service = new ConditionConversationService(extractor, extraction, rules, new ConditionsMerger(extraction, rules), search);
        when(extractor.extract(anyString())).thenReturn(new ConditionsExtraction(2000L, 70L,
                List.of(place("여의도 회사", "여의도역", ConditionsExtraction.Role.WORK), place("수원 본가", "수원역", null)), List.of(), List.of()));
        var current = service.start("보증금 2000만 원 월세 70만 원 회사와 본가");
        for (var p : current.conditions().places()) {
            when(search.search(eq(p.query()), any(), anyInt())).thenReturn(new PlaceSearchClient.SearchResult(
                    List.of(new PlaceLocation(p.id(), p.query(), p.query() + " 주소", 37.5, 127.0)), false));
            var found = service.searchPlace(current.conversationId(), p.id(), new PlaceSearchRequest(p.query(), PlaceSearchClient.SearchType.KEYWORD, 1));
            current = service.selectPlace(current.conversationId(), p.id(), new PlaceSelectionRequest(found.searchId(), found.candidates().getFirst().candidateId()));
        }
        assertThat(current.readyForRecommendation()).isTrue();
        var original = current.conditions();
        for (var text : List.of(VISIT, "수원에서 친구를 만나", VISIT)) {
            current = service.reply(current.conversationId(), text);
            assertThat(current.conditions().places()).hasSize(3);
            assertThat(current.conditions().places().subList(0, 2)).containsExactlyElementsOf(original.places());
            var friend = current.conditions().places().getLast();
            assertThat(friend.targetType()).isEqualTo(PlaceCondition.TargetType.FIXED_PLACE);
            assertThat(friend.query()).isNull();
            assertThat(friend.location()).isNull();
            assertThat(friend.frequency()).isNull();
            assertThat(current.issues()).noneMatch(i -> i.kind() == ConditionCheckResult.Kind.NEEDS_CONFIRMATION);
            if (text.contains("자주")) assertThat(current.notices()).anyMatch(n -> n.contains("‘자주’"));
        }
        verify(extractor, never()).extractUpdate(anyString(), any(), anyString());
    }

    @Test
    void repairsOnlyTheMatchingPendingVisitAndDoesNotConsumeUnrelatedBudgetQuestions() {
        var rules = new ConditionsRules();
        var extraction = new ConditionsExtractionService(mock(ConditionsExtractor.class), rules);
        var friend = new PlaceCondition("friend", "수원 친구", null, null, null, null, null, null, null);
        var same = ConditionsDraft.PendingInput.create(new ConditionCheckResult.Issue("conditions", ConditionCheckResult.Kind.NEEDS_CONFIRMATION, "확인"),
                ConditionsExtraction.Reason.AMBIGUOUS_INPUT, VISIT, "");
        var money = ConditionsDraft.PendingInput.create(new ConditionCheckResult.Issue("budget", ConditionCheckResult.Kind.NEEDS_CONFIRMATION, "단위"),
                ConditionsExtraction.Reason.AMOUNT_UNIT_REQUIRED, "2000", "");
        var old = new ConditionsDraft(new Conditions(null, 70L, List.of(friend)), Set.of(), List.of(same, money), List.of());
        var updated = new ConditionsMerger(extraction, rules).merge(old, PersonalVisitRequest.update(VISIT, old), VISIT, "").draft();
        assertThat(updated.pendingInputs()).containsExactly(money);
        assertThat(updated.conditions().places()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("friend");
            assertThat(p.targetType()).isEqualTo(PlaceCondition.TargetType.FIXED_PLACE);
        });
        for (var text : List.of("친구를 수원에서 만나고 조용한 곳이 좋아", "친구 둘을 수원에서 만나", "친구를 수원에서 주 2회 만나")) {
            assertThat(PersonalVisitRequest.parse(text)).isNull();
        }
    }

    @Test
    void cardAdditionUsesServerIdsKeepsConditionsAndRequiresSelectedAddressWithoutAi() {
        var extractor = mock(ConditionsExtractor.class);
        var rules = new ConditionsRules();
        var extraction = new ConditionsExtractionService(extractor, rules);
        var search = mock(PlaceSearchClient.class);
        var service = new ConditionConversationService(extractor, extraction, rules, new ConditionsMerger(extraction, rules), search);
        when(extractor.extract(anyString())).thenReturn(new ConditionsExtraction(0L, 70L, List.of(), List.of(), List.of()));
        var first = service.start("보증금 0원 월세 70만 원");
        var added = service.addPlace(first.conversationId(), new PlaceAddRequest(first.conditions()));
        assertThat(added.conditions().maxDeposit()).isZero();
        assertThat(added.conditions().places()).singleElement().satisfies(p -> {
            assertThat(UUID.fromString(p.id())).isNotNull();
            assertThat(p.label()).isNull();
            assertThat(p.location()).isNull();
            assertThat(p.importance()).isNull();
        });
        assertThat(added.readyForRecommendation()).isFalse();
        assertThatThrownBy(() -> service.addPlace(first.conversationId(), new PlaceAddRequest(first.conditions())))
                .isInstanceOf(ApiException.class);
        var p = added.conditions().places().getFirst();
        when(search.search(anyString(), any(), anyInt())).thenReturn(new PlaceSearchClient.SearchResult(
                List.of(new PlaceLocation("station", "수원역", "수원 주소", 37.2, 127.0)), false));
        var found = service.searchPlace(first.conversationId(), p.id(), new PlaceSearchRequest("수원역", PlaceSearchClient.SearchType.KEYWORD, 1));
        var selected = service.selectPlace(first.conversationId(), p.id(), new PlaceSelectionRequest(found.searchId(), found.candidates().getFirst().candidateId()));
        assertThat(selected.readyForRecommendation()).isTrue();
        assertThat(selected.conditions().places().getFirst().id()).isEqualTo(p.id());
        verify(extractor, never()).extractUpdate(anyString(), any(), anyString());
    }

    @Test
    void personalVisitRecognizesOtherRelationsButNeverErasesAKnownFrequencyOrGuessesBetweenFriends() {
        for (var text : List.of("연인을 수원에서 자주만나", "잠실역에서 애인을 만나", "본가에 수원에서 자주가")) {
            assertThat(PersonalVisitRequest.parse(text)).isNotNull();
        }
        for (var text : List.of("친구를 차로수원에서 만나", "친구를 수원에서 만나고 주차도 필요해")) {
            assertThat(PersonalVisitRequest.parse(text)).isNull();
        }
        var friend = new PlaceCondition("friend", "수원 친구", PlaceCondition.TargetType.FIXED_PLACE, null, null,
                null, null, null, new VisitFrequency(VisitFrequency.Period.WEEK, 1));
        var old = new ConditionsDraft(new Conditions(2000L, 70L, List.of(friend)), Set.of(), List.of(), List.of());
        var rules = new ConditionsRules();
        var extraction = new ConditionsExtractionService(mock(ConditionsExtractor.class), rules);
        var merged = new ConditionsMerger(extraction, rules).merge(old, PersonalVisitRequest.update(VISIT, old), VISIT, "").draft();
        assertThat(merged.conditions().places()).containsExactly(friend);
        var unclear = new ConditionsExtraction.ExtractedPlace(null, null, null, null, null, false, null, null, null, true);
        var aiUpdate = new ConditionsUpdate(new ConditionsExtraction(null, null, List.of(), List.of(), List.of()), Set.of(),
                List.of(new ConditionsUpdate.PlaceUpdate("friend", ConditionsUpdate.Action.UPDATE, Set.of("frequency"), unclear)), Set.of());
        var kept = new ConditionsMerger(extraction, rules).merge(old, aiUpdate, "친구는 자주 만나고 싶어", "").draft();
        assertThat(kept.conditions().places()).containsExactly(friend);
        assertThat(kept.notices()).anyMatch(n -> n.contains("기존 주 1회"));
        var second = new PlaceCondition("second", "부산 친구", null, null, null, null, null, null, null);
        var two = new ConditionsDraft(new Conditions(2000L, 70L, List.of(friend, second)), Set.of(), List.of(), List.of());
        assertThat(PersonalVisitRequest.update(VISIT, two)).isNull();
    }

    @Test
    void ambiguousWordsAreQuotedAndAnUnspecifiedTravelTargetDoesNotSetEitherLimit() {
        var extractor = mock(ConditionsExtractor.class);
        var service = new ConditionsExtractionService(extractor, new ConditionsRules());
        String text = "보증금 1000만 원 월세 50만 원. 회사는 역삼역이고 교회는 창훈대교회야. 거기까지 30분 이내였으면 좋겠어.";
        var work = place("회사", "역삼역", ConditionsExtraction.Role.WORK);
        var church = place("교회", "창훈대교회", null);
        when(extractor.extract(text)).thenReturn(new ConditionsExtraction(1000L, 50L, List.of(work, church), List.of(), List.of()));
        var checked = service.parse(text);
        assertThat(checked.conditions().places()).allMatch(p -> p.maxMinutes() == null);
        assertThat(checked.nextQuestion()).contains("거기까지 30분", "어느 장소");
        var oldPlace = new PlaceCondition("work", "회사", PlaceCondition.TargetType.FIXED_PLACE, "역삼역", null,
                PlaceCondition.Importance.REQUIRED, PlaceCondition.Transport.PUBLIC_TRANSIT, 60, null);
        var oldChurch = new PlaceCondition("church", "교회", PlaceCondition.TargetType.FIXED_PLACE, "창훈대교회", null, null, null, 90, null);
        var previous = new ConditionsDraft(new Conditions(1000L, 50L, List.of(oldPlace, oldChurch)), Set.of(), List.of(), List.of());
        var guessedTime = new ConditionsExtraction.ExtractedPlace(null, null, null, null, null, false, null, 30, null, false);
        var update = new ConditionsUpdate(new ConditionsExtraction(null, null, List.of(), List.of(), List.of()), Set.of(),
                List.of(new ConditionsUpdate.PlaceUpdate("work", ConditionsUpdate.Action.UPDATE, Set.of("maxMinutes"), guessedTime)), Set.of());
        var merged = new ConditionsMerger(service, new ConditionsRules()).merge(previous, update, "거기까지 30분 이내", "").draft();
        assertThat(merged.conditions().places()).containsExactly(oldPlace, oldChurch);
        assertThat(merged.check(new ConditionsRules()).nextQuestion()).contains("거기까지 30분", "어느 장소");
        var v = new ConditionsExtraction(1000L, 50L, List.of(), List.of(
                new ConditionsExtraction.Clarification("conditions", ConditionsExtraction.Reason.AMBIGUOUS_INPUT, "대충 적당히")), List.of());
        when(extractor.extract(anyString())).thenReturn(v);
        assertThat(service.parse("보증금 1000만 원 월세 50만 원 대충 적당히").nextQuestion()).contains("‘대충 적당히’");
        assertThatThrownBy(() -> service.parse("보증금 1000만 원 월세 50만 원 다른 문장")).isInstanceOf(ApiException.class);
    }
}
