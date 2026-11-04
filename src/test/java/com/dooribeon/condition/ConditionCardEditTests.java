package com.dooribeon.condition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dooribeon.condition.place.*;
import com.dooribeon.global.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConditionCardEditTests {
    @Test
    void exactBuildingNeedsSelectionAndCardEditsSurviveChatWhileStaleAndInvalidWritesFail() {
        var extractor = mock(ConditionsExtractor.class);
        var search = mock(PlaceSearchClient.class);
        var rules = new ConditionsRules();
        var extraction = new ConditionsExtractionService(extractor, rules);
        var service = new ConditionConversationService(extractor, extraction, rules, new ConditionsMerger(extraction, rules), search);
        var input = new ConditionsExtraction.ExtractedPlace("토스", PlaceCondition.TargetType.FIXED_PLACE,
                "역삼동 아크플레이스 토스", ConditionsExtraction.Role.WORK, null, false,
                PlaceCondition.Transport.PUBLIC_TRANSIT, 60, null, false);
        when(extractor.extract(anyString())).thenReturn(new ConditionsExtraction(1000L, 50L, List.of(input), List.of(), List.of()));
        when(extractor.extractUpdate(anyString(), any(), anyString())).thenReturn(ConditionConversationServiceTests.noChange());
        var building = new PlaceLocation("building", "아크플레이스", "서울 강남구 테헤란로 142", 37.5, 127.03);
        var parking = new PlaceLocation("parking", "아크플레이스 주차장", building.address(), 37.5, 127.03);
        when(search.search(eq("역삼동 아크플레이스 토스"), any(), anyInt())).thenReturn(
                new PlaceSearchClient.SearchResult(List.of(building, parking), false));
        var first = service.start("보증금 1000만 원 월세 50만 원 회사 역삼동 아크플레이스 토스");
        var unselected = first.conditions().places().getFirst();
        assertThat(unselected.location()).isNull();
        assertThat(first.readyForRecommendation()).isFalse();
        verifyNoInteractions(search);
        var found = service.searchPlace(first.conversationId(), unselected.id(),
                new PlaceSearchRequest(unselected.query(), PlaceSearchClient.SearchType.KEYWORD, 1));
        assertThat(service.reply(first.conversationId(), "유지해줘").conditions().places().getFirst().location()).isNull();
        found = service.searchPlace(first.conversationId(), unselected.id(),
                new PlaceSearchRequest(unselected.query(), PlaceSearchClient.SearchType.KEYWORD, 1));
        first = service.selectPlace(first.conversationId(), unselected.id(),
                new PlaceSelectionRequest(found.searchId(), found.candidates().getFirst().candidateId()));
        var place = first.conditions().places().getFirst();
        assertThat(place.location()).isEqualTo(building);
        var edited = new PlaceCondition(place.id(), null, place.targetType(), place.query(), null,
                PlaceCondition.Importance.HIGH, PlaceCondition.Transport.WALK, 70, new VisitFrequency(VisitFrequency.Period.WEEK, 1));
        var selected = first;
        var values = new Conditions(2000L, 50L, List.of(edited));
        var saved = service.edit(first.conversationId(), new ConditionEditRequest(first.conditions(), values));
        assertThat(saved.conditions().places().getFirst().location()).isEqualTo(building);
        assertThat(saved.conditions().places().getFirst().label()).isNull();
        assertThat(service.reply(first.conversationId(), "유지해줘").conditions()).isEqualTo(saved.conditions());
        assertThatThrownBy(() -> service.edit(selected.conversationId(), new ConditionEditRequest(selected.conditions(), values)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.edit(selected.conversationId(),
                new ConditionEditRequest(saved.conditions(), new Conditions(-1L, 50L, values.places())))).isInstanceOf(ApiException.class);
    }

    @Test
    void sameBuildingNameInAnotherDongRequiresSelection() {
        var extractor = mock(ConditionsExtractor.class);
        var search = mock(PlaceSearchClient.class);
        var rules = new ConditionsRules();
        var extraction = new ConditionsExtractionService(extractor, rules);
        var service = new ConditionConversationService(extractor, extraction, rules, new ConditionsMerger(extraction, rules), search);
        var input = new ConditionsExtraction.ExtractedPlace("회사", PlaceCondition.TargetType.FIXED_PLACE,
                "역삼동 아크플레이스", null, null, false, null, null, null, false);
        when(extractor.extract(anyString())).thenReturn(new ConditionsExtraction(1000L, 50L, List.of(input), List.of(), List.of()));
        var other = new PlaceLocation("other", "아크플레이스", "다른 주소", 37.5, 127.03);
        when(search.search(anyString(), any(), anyInt())).thenReturn(
                new PlaceSearchClient.SearchResult(List.of(other), false));
        var first = service.start("테스트");
        var place = first.conditions().places().getFirst();
        assertThat(place.location()).isNull();
        var changed = new PlaceCondition(place.id(), "새 이름", place.targetType(), place.query(), null,
                PlaceCondition.Importance.HIGH, null, null, null);
        assertThatThrownBy(() -> service.edit(first.conversationId(), new ConditionEditRequest(first.conditions(),
                new Conditions(1000L, 50L, List.of(changed))))).isInstanceOf(ApiException.class);
        var renamed = new PlaceCondition(place.id(), "새 이름", place.targetType(), place.query(), null,
                place.importance(), place.transport(), place.maxMinutes(), place.frequency());
        assertThat(service.edit(first.conversationId(), new ConditionEditRequest(first.conditions(),
                new Conditions(1000L, 50L, List.of(renamed)))).conditions().places().getFirst().label()).isEqualTo("새 이름");
    }
    @Test
    void changingBudgetPreservesUnsupportedRequestsWithoutAReasonCode() {
        var extractor = mock(ConditionsExtractor.class);
        var rules = new ConditionsRules();
        var extraction = new ConditionsExtractionService(extractor, rules);
        var service = new ConditionConversationService(extractor, extraction, rules,
                new ConditionsMerger(extraction, rules), mock(PlaceSearchClient.class));
        when(extractor.extract(anyString())).thenReturn(new ConditionsExtraction(1000L, 50L,
                List.of(), List.of(), List.of("조용한 동네")));
        var first = service.start("보증금 1000만 원 월세 50만 원 조용한 동네");
        var saved = service.edit(first.conversationId(), new ConditionEditRequest(first.conditions(),
                new Conditions(2000L, 50L, List.of())));
        assertThat(saved.conditions().maxDeposit()).isEqualTo(2000L);
        assertThat(saved.issues()).anyMatch(issue -> issue.kind() == ConditionCheckResult.Kind.UNSUPPORTED
                && issue.message().contains("조용한 동네"));
    }

    @Test
    void nearbyFacilityCanBeEditedWithoutChoosingALocation() {
        var extractor = mock(ConditionsExtractor.class);
        var rules = new ConditionsRules();
        var extraction = new ConditionsExtractionService(extractor, rules);
        var service = new ConditionConversationService(extractor, extraction, rules,
                new ConditionsMerger(extraction, rules), mock(PlaceSearchClient.class));
        var input = new ConditionsExtraction.ExtractedPlace("러닝", PlaceCondition.TargetType.NEARBY_FACILITY,
                "공원", null, null, false, null, null, new VisitFrequency(VisitFrequency.Period.WEEK, 4), false);
        when(extractor.extract(anyString())).thenReturn(new ConditionsExtraction(1000L, 50L, List.of(input), List.of(), List.of()));
        when(extractor.extractUpdate(anyString(), any(), anyString())).thenReturn(ConditionConversationServiceTests.noChange());
        var first = service.start("보증금 1000만원 월세 50만원 새 동네 근처에서 러닝 주 4회");
        var place = first.conditions().places().getFirst();
        var edited = new PlaceCondition(place.id(), place.label(), place.targetType(), place.query(), null,
                PlaceCondition.Importance.HIGH, PlaceCondition.Transport.WALK, 15, new VisitFrequency(VisitFrequency.Period.WEEK, 3));
        var saved = service.edit(first.conversationId(), new ConditionEditRequest(first.conditions(),
                new Conditions(1000L, 50L, List.of(edited))));
        assertThat(saved.conditions().places().getFirst()).isEqualTo(edited);
        assertThat(service.reply(first.conversationId(), "유지해줘").conditions()).isEqualTo(saved.conditions());
        assertThat(saved.readyForRecommendation()).isTrue();
        var required = new PlaceCondition(place.id(), place.label(), place.targetType(), place.query(), null,
                PlaceCondition.Importance.REQUIRED, null, null, edited.frequency());
        var needsTime = service.edit(first.conversationId(), new ConditionEditRequest(saved.conditions(),
                new Conditions(1000L, 50L, List.of(required))));
        assertThat(needsTime.readyForRecommendation()).isFalse();
        assertThat(needsTime.nextQuestion()).contains("공원까지 걸어서 최대 몇 분");
        var timed = new ConditionsExtraction.ExtractedPlace(null, null, null, null, null, false,
                null, 15, null, false);
        when(extractor.extractUpdate(eq("15"), any(), anyString())).thenReturn(new ConditionsUpdate(
                ConditionConversationServiceTests.noChange().values(), Set.of(), List.of(
                    new ConditionsUpdate.PlaceUpdate(place.id(), ConditionsUpdate.Action.UPDATE,
                            Set.of("maxMinutes"), timed)), Set.of()));
        var answered = service.reply(first.conversationId(), "15");
        assertThat(answered.readyForRecommendation()).isTrue();
        assertThat(answered.conditions().places().getFirst().maxMinutes()).isEqualTo(15);
        assertThat(answered.conditions().places().getFirst().transport()).isEqualTo(PlaceCondition.Transport.WALK);
        var optional = new PlaceCondition(place.id(), place.label(), place.targetType(), place.query(), null,
                PlaceCondition.Importance.LOW, PlaceCondition.Transport.PUBLIC_TRANSIT, null, edited.frequency());
        var noTime = service.edit(first.conversationId(), new ConditionEditRequest(answered.conditions(),
                new Conditions(1000L, 50L, List.of(optional))));
        assertThat(noTime.readyForRecommendation()).isTrue();
        assertThat(service.reply(first.conversationId(), "유지해줘").conditions()).isEqualTo(noTime.conditions());
        assertThatThrownBy(() -> service.edit(first.conversationId(), new ConditionEditRequest(first.conditions(),
                saved.conditions()))).isInstanceOf(ApiException.class);
    }

}
