package com.dooribeon.condition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class FamilyPlaceRequestTests {
    @Test
    void homeIntroductionsBecomeFixedPlacesWithMissingAddressesInsteadOfUnsupportedRequests() {
        var extractor = mock(ConditionsExtractor.class);
        var service = new ConditionsExtractionService(extractor, new ConditionsRules());
        var cases = java.util.Map.of("본가는 부산이야", "부산 본가",
                "부모님 집은 수원에 있어요", "수원 부모님 집",
                "수원 본가에 자주 가고 싶어", "수원 본가");
        for (var entry : cases.entrySet()) {
            String fragment = entry.getKey();
            String text = "보증금 1000만 원, 월세 50만 원. " + fragment;
            when(extractor.extract(text)).thenReturn(new ConditionsExtraction(1000L, 50L,
                    List.of(), List.of(), List.of(fragment)));
            var result = service.parse(text);
            assertThat(result.conditions().places()).hasSize(1);
            var home = result.conditions().places().getFirst();
            assertThat(home.label()).isEqualTo(entry.getValue());
            assertThat(home.targetType()).isEqualTo(PlaceCondition.TargetType.FIXED_PLACE);
            assertThat(home.query()).isNull();
            assertThat(home.location()).isNull();
            assertThat(home.importance()).isNull();
            assertThat(home.frequency()).isNull();
            assertThat(result.issues()).noneMatch(issue -> issue.kind() == ConditionCheckResult.Kind.UNSUPPORTED);
            assertThat(result.nextQuestion()).contains("위치");
        }
    }

    @Test
    void realUnsupportedRequirementsAreNotRemovedByHomeKeywords() {
        for (String text : List.of("본가 근처 조용한 동네", "본가까지 자가용 30분", "부모님 집 주변 치안",
                "본가는 부산이고 주차장이 있어야 해", "본가에 자주 가고 싶고 소음도 없어야 해")) {
            assertThat(FamilyPlaceRequest.recover(text)).isNull();
        }
    }

    @Test
    void homeAlreadyExtractedIsNotDuplicatedAndOtherUnsupportedRequirementsRemain() {
        var extractor = mock(ConditionsExtractor.class);
        var service = new ConditionsExtractionService(extractor, new ConditionsRules());
        var home = new ConditionsExtraction.ExtractedPlace("부산 본가", PlaceCondition.TargetType.FIXED_PLACE,
                null, null, null, false, null, null, null, false);
        String text = "보증금 1000만 원 월세 50만 원. 본가는 부산이야. 조용한 동네";
        when(extractor.extract(text)).thenReturn(new ConditionsExtraction(1000L, 50L,
                List.of(home), List.of(), List.of("본가는 부산이야", "조용한 동네")));
        var result = service.parse(text);
        assertThat(result.conditions().places()).hasSize(1);
        assertThat(result.issues().stream().filter(issue -> issue.kind() == ConditionCheckResult.Kind.UNSUPPORTED))
                .singleElement().satisfies(issue -> assertThat(issue.message()).contains("조용한 동네"));
    }
    @Test
    void anExistingHomeStatementRequestsConfirmationWithoutOverwritingOrDuplicatingTheHome() {
        var extractor = mock(ConditionsExtractor.class);
        var service = new ConditionsExtractionService(extractor, new ConditionsRules());
        var old = new PlaceCondition("home", "수원 본가", PlaceCondition.TargetType.FIXED_PLACE,
                "선택한 주소", new PlaceLocation("k1", "집", "선택한 주소", 37.2, 127.0),
                null, null, null, null);
        var values = new ConditionsExtraction(null, null, List.of(), List.of(), List.of("본가는 부산이야"));
        var prepared = service.prepare(values, "본가는 부산이야", "", List.of(old));
        assertThat(prepared.conditions().places()).isEmpty();
        assertThat(prepared.pendingInputs()).singleElement().satisfies(pending -> {
            assertThat(pending.issue().kind()).isEqualTo(ConditionCheckResult.Kind.NEEDS_CONFIRMATION);
            assertThat(pending.issue().message()).contains("본가", "주소");
        });
    }

}
