package com.dooribeon.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dooribeon.condition.ConditionsExtraction.Clarification;
import com.dooribeon.condition.ConditionsExtraction.ExtractedPlace;
import com.dooribeon.condition.ConditionsExtraction.Reason;
import com.dooribeon.condition.ConditionsExtraction.Role;
import com.dooribeon.condition.PlaceCondition.Importance;
import com.dooribeon.condition.PlaceCondition.TargetType;
import com.dooribeon.condition.PlaceCondition.Transport;
import com.dooribeon.global.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ConditionsExtractionServiceTests {
    private final ConditionsExtractor extractor = mock(ConditionsExtractor.class);
    private final ConditionsExtractionService service = new ConditionsExtractionService(extractor, new ConditionsRules());

    @Test
    void completeBudgetWithNoPlaceIsReady() {
        when(extractor.extract("보증금 1천만 원, 월세 50만 원"))
                .thenReturn(extraction(1000L, 50L, List.of(), List.of()));
        var result = service.parse("보증금 1천만 원, 월세 50만 원");
        assertThat(result.readyForRecommendation()).isTrue();
        assertThat(result.conditions().maxDeposit()).isEqualTo(1000L);
        assertThat(result.conditions().places()).isEmpty();
    }

    @Test
    void serverAssignsIdKeepsLocationUnselectedAndDefaultsWorkToRequired() {
        var place = place(Role.WORK, null, false, false);
        when(extractor.extract("회사 여의도역, 월세 50만 원"))
                .thenReturn(extraction(null, 50L, List.of(place), List.of()));
        var result = service.parse("회사 여의도역, 월세 50만 원");
        var actual = result.conditions().places().getFirst();
        assertThat(actual.id()).isNotBlank();
        assertThat(actual.query()).isEqualTo("여의도역");
        assertThat(actual.location()).isNull();
        assertThat(actual.importance()).isEqualTo(Importance.REQUIRED);
        assertThat(actual.frequency()).isEqualTo(new VisitFrequency(VisitFrequency.Period.MONTH, 2));
        assertThat(result.notices()).anyMatch(s -> s.contains("서비스 기본값"));
        assertThat(result.issues()).extracting(ConditionCheckResult.Issue::path)
                .contains("maxDeposit", "places[0].location");
        assertThat(result.readyForRecommendation()).isFalse();
    }

    @Test
    void userImportanceWinsEvenWhenExplicitlyCleared() {
        for (Importance importance : new Importance[] {Importance.LOW, null}) {
            when(extractor.extract("회사 중요도는 내가 정할게"))
                    .thenReturn(extraction(null, null, List.of(place(Role.WORK, importance, true, false)), List.of()));
            var result = service.parse("회사 중요도는 내가 정할게");
            assertThat(result.conditions().places().getFirst().importance()).isEqualTo(importance);
            assertThat(result.notices()).noneMatch(s -> s.contains("서비스 기본값"));
        }
    }

    @Test
    void vagueOptionalFrequencyIsNotMadeIntoAMandatoryQuestionOrNumber() {
        when(extractor.extract("친구는 종종 여의도역에서 만나"))
                .thenReturn(extraction(null, null, List.of(place(null, null, false, true)), List.of()));
        var result = service.parse("친구는 종종 여의도역에서 만나");
        assertThat(result.conditions().places().getFirst().frequency()).isNull();
        assertThat(result.notices()).anyMatch(s -> s.contains("방문 횟수"));
        assertThat(result.issues()).noneMatch(i -> i.path().contains("frequency"));
    }

    @Test
    void guessedUnitlessAmountsAreClearedEvenIfAiForgotConfirmation() {
        when(extractor.extract("보증금 1000 월세 50"))
                .thenReturn(extraction(1000L, 50L, List.of(), List.of()));
        var result = service.parse("보증금 1000 월세 50");
        assertThat(result.conditions().maxDeposit()).isNull();
        assertThat(result.conditions().maxRent()).isNull();
        assertThat(result.readyForRecommendation()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.nextQuestion()).contains("단위");
    }

    @Test
    void explicitNoDepositAndTimeNumbersAreNotInventedMoneyQuestions() {
        when(extractor.extract("무보증으로 찾아줘"))
                .thenReturn(extraction(0L, null, List.of(), List.of()));
        var result = service.parse("무보증으로 찾아줘");
        assertThat(result.conditions().maxDeposit()).isZero();
        assertThat(result.issues()).extracting(ConditionCheckResult.Issue::path).containsExactly("maxRent");

        when(extractor.extract("회사까지 대중교통 40분"))
                .thenReturn(extraction(null, null, List.of(place(Role.WORK, null, false, false)), List.of()));
        assertThat(service.parse("회사까지 대중교통 40분").issues())
                .noneMatch(i -> i.kind() == ConditionCheckResult.Kind.NEEDS_CONFIRMATION);
    }

    @Test
    void wonRemaindersAndFeesProduceSpecificQuestionsWithoutDuplicateMissingQuestions() {
        when(extractor.extract("월세 50만 5천 원"))
                .thenReturn(extraction(1000L, null, List.of(),
                        List.of(new Clarification("maxRent", Reason.INTEGER_MANWON_REQUIRED))));
        assertThat(service.parse("월세 50만 5천 원").nextQuestion()).contains("정수");
        when(extractor.extract("보증금 천만 원, 관리비 포함 60만 원"))
                .thenReturn(extraction(1000L, null, List.of(),
                        List.of(new Clarification("maxRent", Reason.NET_RENT_REQUIRED))));
        var result = service.parse("보증금 천만 원, 관리비 포함 60만 원");
        assertThat(result.issues()).hasSize(1);
        assertThat(result.nextQuestion()).contains("관리비를 제외");
    }

    @Test
    void jeonseAndOtherUnsupportedRequestsCannotBecomeReadyMonthlyConditions() {
        when(extractor.extract("전세 2억 원"))
                .thenReturn(extraction(null, null, List.of(),
                        List.of(new Clarification("conditions", Reason.UNSUPPORTED_RENTAL_TYPE))));
        var jeonse = service.parse("전세 2억 원");
        assertThat(jeonse.readyForRecommendation()).isFalse();
        assertThat(jeonse.issues()).hasSize(1);
        assertThat(jeonse.nextQuestion()).contains("월세");

        when(extractor.extract("보증금 천만 원, 월세 50만 원, 조용한 동네"))
                .thenReturn(new ConditionsExtraction(1000L, 50L, List.of(), List.of(), List.of("조용한 동네")));
        assertThat(service.parse("보증금 천만 원, 월세 50만 원, 조용한 동네").readyForRecommendation()).isFalse();
    }

    @Test
    void providerFailureIsNotAnEmptySuccessfulResult() {
        when(extractor.extract("월세 50만 원"))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_BUSY", "잠시 후 재시도"));
        assertThatThrownBy(() -> service.parse("월세 50만 원"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AI_BUSY"));
    }

    private ConditionsExtraction extraction(Long deposit, Long rent, List<ExtractedPlace> places,
                                            List<Clarification> clarifications) {
        return new ConditionsExtraction(deposit, rent, places, clarifications, List.of());
    }

    private ExtractedPlace place(Role role, Importance importance, boolean specified, boolean vagueFrequency) {
        return new ExtractedPlace("회사", TargetType.FIXED_PLACE, "여의도역", role, importance,
                specified, Transport.PUBLIC_TRANSIT, 40,
                vagueFrequency ? null : new VisitFrequency(VisitFrequency.Period.MONTH, 2), vagueFrequency);
    }
}
