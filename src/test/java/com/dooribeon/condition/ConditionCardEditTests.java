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
    void exactBuildingRegistersAndCardEditsSurviveChatWhileStaleAndInvalidWritesFail() {
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
        when(search.search(eq("역삼동 아크플레이스"), any(), anyInt())).thenReturn(
                new PlaceSearchClient.SearchResult(List.of(building, parking), false,
                        Map.of(building, "서울 강남구 역삼동 736", parking, "서울 강남구 역삼동 736")));
        var first = service.start("보증금 1000만 원 월세 50만 원 회사 역삼동 아크플레이스 토스");
        var place = first.conditions().places().getFirst();
        assertThat(place.location()).isEqualTo(building);
        var edited = new PlaceCondition(place.id(), null, place.targetType(), place.query(), null,
                PlaceCondition.Importance.HIGH, PlaceCondition.Transport.WALK, 70, new VisitFrequency(VisitFrequency.Period.WEEK, 1));
        var values = new Conditions(2000L, 50L, List.of(edited));
        var saved = service.edit(first.conversationId(), new ConditionEditRequest(first.conditions(), values));
        assertThat(saved.conditions().places().getFirst().location()).isEqualTo(building);
        assertThat(saved.conditions().places().getFirst().label()).isNull();
        assertThat(service.reply(first.conversationId(), "유지해줘").conditions()).isEqualTo(saved.conditions());
        assertThatThrownBy(() -> service.edit(first.conversationId(), new ConditionEditRequest(first.conditions(), values)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.edit(first.conversationId(),
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
                new PlaceSearchClient.SearchResult(List.of(other), false, Map.of(other, "서울 강남구 삼성동 1")));
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
}
