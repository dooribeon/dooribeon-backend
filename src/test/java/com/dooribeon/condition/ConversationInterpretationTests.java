package com.dooribeon.condition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConversationInterpretationTests {
    private final ConditionsRules rules = new ConditionsRules();
    private final ConditionsExtractor extractor = mock(ConditionsExtractor.class);
    private final ConditionsExtractionService extraction = new ConditionsExtractionService(extractor, rules);
    private final ConditionsMerger merger = new ConditionsMerger(extraction, rules);

    private ConditionsExtraction values(Long deposit, Long rent) {
        return new ConditionsExtraction(deposit, rent, List.of(), List.of(), List.of());
    }

    @Test
    void mixedUnitsAndParkNeverConfirmAnAmbiguousDeposit() {
        for (String text : List.of("보증금 10000, 월세 50만 원", "보증금 10000, 월세 50.")) {
            var result = extraction.prepare(values(10000L, 50L), text, "").check(rules);
            assertThat(result.conditions().maxDeposit()).isNull();
            assertThat(result.conditions().maxRent()).isEqualTo(50);
            assertThat(result.readyForRecommendation()).isFalse();
            assertThat(result.issues()).extracting(ConditionCheckResult.Issue::path).containsExactly("maxDeposit");
        }
    }

    @Test
    void guessesAreComputedFromRawNumbersAndRemaindersAreNeverRounded() {
        var result = extraction.prepare(values(999L, 999L), "보증금 1000, 월세 500000", "").check(rules);
        assertThat(result.conditions()).isEqualTo(new Conditions(1000L, 50L, List.of()));
        assertThat(result.notices()).hasSize(2);
        var partial = extraction.prepare(values(999L, 999L), "보증금 1000, 월세 15000", "").check(rules);
        assertThat(partial.conditions().maxDeposit()).isEqualTo(1000);
        assertThat(partial.conditions().maxRent()).isNull();
        assertThat(partial.nextQuestion()).contains("만원 단위 정수");
    }

    @Test
    void bareBudgetReplyAppliesImmediatelyAndKeepsOtherBudget() {
        when(extractor.extract(anyString())).thenReturn(values(null, 50L));
        var service = new ConditionConversationService(extractor, extraction, rules, merger,
                mock(com.dooribeon.condition.place.PlaceSearchClient.class));
        var first = service.start("월세 50만 원");
        var result = service.reply(first.conversationId(), "1000");
        assertThat(result.conditions()).isEqualTo(new Conditions(1000L, 50L, List.of()));
        assertThat(result.readyForRecommendation()).isTrue();
        assertThat(result.notices()).anyMatch(n -> n.contains("1,000") && n.contains("수정"));
        verify(extractor, never()).extractUpdate(anyString(), any(), anyString());
    }

    @Test
    void stationCorrectionClearsLocationButKeepsOtherConditions() {
        var friend = new PlaceCondition("friend", "친구", PlaceCondition.TargetType.FIXED_PLACE,
                "강남역", new PlaceLocation("old", "강남역", "서울", 37.5, 127.0),
                PlaceCondition.Importance.HIGH, PlaceCondition.Transport.PUBLIC_TRANSIT, 60,
                new VisitFrequency(VisitFrequency.Period.MONTH, 2));
        var current = new ConditionsDraft(new Conditions(1000L, 50L, List.of(friend)), Set.of(), List.of(), List.of());
        var update = PersonalVisitRequest.update("친구를 홍대입구역에서 만나", current);
        var next = merger.merge(current, update, "친구를 홍대입구역에서 만나", "").draft().conditions();
        var actual = next.places().getFirst();
        assertThat(actual.query()).isEqualTo("홍대입구역");
        assertThat(actual.location()).isNull();
        assertThat(actual.id()).isEqualTo(friend.id());
        assertThat(actual.importance()).isEqualTo(friend.importance());
        assertThat(actual.frequency()).isEqualTo(friend.frequency());
        assertThat(actual.maxMinutes()).isEqualTo(60);
        assertThat(next.maxRent()).isEqualTo(50);
    }

    @Test
    void punctuationAndTypoStillRequireATravelTarget() {
        for (String text : List.of("회사랑 학교. 거기까지 40분", "회사랑 학교, 거기까지 40분", "회사랑 학교. 거기까징 40분")) {
            assertThat(AmbiguousTravelTarget.phrase(text, 2)).contains("40분");
        }
        assertThat(AmbiguousTravelTarget.phrase("회사와 학교 모두 40분", 2)).isNull();
    }
    @Test
    void ambiguousAmountKeepsOldValueAndCanBeResolvedByAnotherShortReply() {
        when(extractor.extract(anyString())).thenReturn(values(null, 50L));
        var service = new ConditionConversationService(extractor, extraction, rules, merger,
                mock(com.dooribeon.condition.place.PlaceSearchClient.class));
        var first = service.start("월세 50만 원");
        var ambiguous = service.reply(first.conversationId(), "10000");
        assertThat(ambiguous.nextQuestion()).contains("보증금", "단위");
        var resolved = service.reply(first.conversationId(), "2000");
        assertThat(resolved.conditions()).isEqualTo(new Conditions(2000L, 50L, List.of()));
        assertThat(resolved.readyForRecommendation()).isTrue();
    }

    @Test
    void runningTypeQuestionKeepsFrequencyAndDoesNotAskForAddress() {
        var running = new ConditionsExtraction.ExtractedPlace("러닝", null, null, null, null, false,
                null, null, new VisitFrequency(VisitFrequency.Period.WEEK, 4), false);
        var result = extraction.prepare(new ConditionsExtraction(1000L, 50L, List.of(running), List.of(), List.of()),
                "보증금 1000만 원 월세 50만 원. 러닝은 주 4회", "").check(rules);
        assertThat(result.nextQuestion()).contains("특정 장소", "이사할 동네");
        assertThat(result.issues()).noneMatch(i -> i.path().endsWith("location"));
        assertThat(result.conditions().places().getFirst().frequency().count()).isEqualTo(4);
    }

    @Test
    void shortTimeAnswerChangesOnlyTheRequestedPlace() {
        var work = new PlaceCondition("work", "회사", PlaceCondition.TargetType.FIXED_PLACE, "역삼역",
                null, PlaceCondition.Importance.REQUIRED, PlaceCondition.Transport.PUBLIC_TRANSIT, null, null);
        var friend = new PlaceCondition("friend", "친구", PlaceCondition.TargetType.FIXED_PLACE, "수원시청역",
                null, null, null, 90, null);
        var current = new ConditionsDraft(new Conditions(1000L, 50L, List.of(work, friend)), Set.of(), List.of(), List.of());
        var patch = new ConditionsExtraction.ExtractedPlace(null, null, null, null, null, false, null, 50, null, false);
        var update = new ConditionsUpdate(values(null, null), Set.of(),
                List.of(new ConditionsUpdate.PlaceUpdate("work", ConditionsUpdate.Action.UPDATE, Set.of("maxMinutes"), patch)), Set.of());
        var result = merger.merge(current, update, "50", "회사까지 편도로 최대 몇 분까지 괜찮으세요?").draft();
        assertThat(result.conditions().maxRent()).isEqualTo(50);
        assertThat(result.conditions().places().getFirst().maxMinutes()).isEqualTo(50);
        assertThat(result.conditions().places().getLast()).isEqualTo(friend);
    }

    @Test
    void correctionIsNeverOverwrittenByTheFirstNumberInTheSentence() {
        var result = extraction.prepare(values(2000L, 50L), "보증금 1000 아니 2000, 월세 50", "").check(rules);
        assertThat(result.conditions().maxDeposit()).isEqualTo(2000);
        var sentence = extraction.prepare(values(999L, 50L), "보증금 1000으로 해줘", "").check(rules);
        assertThat(sentence.conditions().maxDeposit()).isEqualTo(1000);
        assertThat(sentence.notices()).anyMatch(n -> n.contains("1,000"));
    }

    @Test
    void punctuationAndCorrectionsAnnounceTheFinalAppliedAmount() {
        var sentence = extraction.prepare(values(1000L, 50L), "보증금 1000, 월세 50.", "").check(rules);
        assertThat(sentence.notices()).anyMatch(n -> n.contains("월세") && n.contains("50만 원"));
        var correction = extraction.prepare(values(2000L, null), "아니 2000", "보증금은 얼마인가요?").check(rules);
        assertThat(correction.conditions().maxDeposit()).isEqualTo(2000);
        assertThat(correction.notices()).anyMatch(n -> n.contains("2,000만 원") && n.contains("만원으로"));
        var won = extraction.prepare(values(null, 50L), "아니 월세 500000", "").check(rules);
        assertThat(won.notices()).anyMatch(n -> n.contains("50만 원") && n.contains("원으로"));
        var explicit = extraction.prepare(values(2000L, null), "보증금 1000만원 아니 2000만원", "").check(rules);
        assertThat(explicit.notices()).isEmpty();
    }

    @Test
    void currentRentDoesNotReplaceTheDesiredMaximumInInitialOrFollowupInput() {
        for (String text : List.of(
                "지금 월세 80만원 내는데 이사하면 60만원 이하로 찾고 싶어",
                "지금 월세 80만원, 희망 월세 60만원",
                "희망 월세 60만원, 지금 월세 80만원")) {
            when(extractor.extract(text)).thenReturn(values(null, 60L));
            assertThat(extraction.parse(text).conditions().maxRent()).isEqualTo(60);
            var current = new ConditionsDraft(new Conditions(1000L, 50L, List.of()), Set.of(), List.of(), List.of());
            var update = new ConditionsUpdate(values(null, 60L), Set.of("maxRent"), List.of(), Set.of());
            var result = merger.merge(current, update, text, "").draft().conditions();
            assertThat(result).isEqualTo(new Conditions(1000L, 60L, List.of()));
        }
    }

    @Test
    void spacedLargeCurrencyUnitsAreNeverReadAsUnitlessSmallNumbers() {
        for (String text : List.of("보증금 1 억 원", "보증금 1억원", "보증금 1 억원")) {
            when(extractor.extract(text)).thenReturn(values(10000L, 50L));
            var result = extraction.parse(text);
            assertThat(result.conditions().maxDeposit()).isEqualTo(10000);
            assertThat(result.notices()).noneMatch(n -> n.contains("1만 원"));
            var current = new ConditionsDraft(new Conditions(1000L, 50L, List.of()), Set.of(), List.of(), List.of());
            var update = new ConditionsUpdate(values(10000L, null), Set.of("maxDeposit"), List.of(), Set.of());
            assertThat(merger.merge(current, update, text, "").draft().conditions())
                    .isEqualTo(new Conditions(10000L, 50L, List.of()));
        }
    }

    @Test
    void repeatedAmountsKeepClarificationWhenTheAiCannotChooseAMaximum() {
        String text = "월세 60만원 월세 80만원 중 고민이야";
        var unclear = new ConditionsExtraction(null, null, List.of(),
                List.of(new ConditionsExtraction.Clarification("maxRent", ConditionsExtraction.Reason.AMBIGUOUS_INPUT)),
                List.of());
        var checked = extraction.prepare(unclear, text, "").check(rules);
        assertThat(checked.conditions().maxRent()).isNull();
        assertThat(checked.readyForRecommendation()).isFalse();
        assertThat(checked.issues()).anyMatch(i -> i.path().equals("maxRent"));
    }

    @Test
    void koreanDesiredRentAndCurrentExpenseArePreservedRegardlessOfOrder() {
        for (String text : List.of("지금 월세 80만원 내는데 이사하면 육십만 원 이하로 찾고 싶어",
                "희망 월세는 육십만원, 지금 월세 80만원이야")) {
            var checked = extraction.prepare(values(null, 60L), text, "").check(rules);
            assertThat(checked.conditions().maxRent()).isEqualTo(60);
            var current = new ConditionsDraft(new Conditions(1000L, 50L, List.of()), Set.of(), List.of(), List.of());
            var update = new ConditionsUpdate(values(null, 60L), Set.of("maxRent"), List.of(), Set.of());
            assertThat(merger.merge(current, update, text, "").draft().conditions())
                    .isEqualTo(new Conditions(1000L, 60L, List.of()));
        }
    }

    @Test
    void amountInAClearRequestIsNeverReintroducedAndOtherBudgetIsKept() {
        var current = new ConditionsDraft(new Conditions(1000L, 50L, List.of()), Set.of(), List.of(), List.of());
        for (String text : List.of("월세 50만원 지워줘", "월세 50만원 조건을 지워줘", "월세 50만원 삭제해줘")) {
            var clear = new ConditionsUpdate(values(null, null), Set.of("maxRent"), List.of(), Set.of());
            var result = merger.merge(current, clear, text, "").draft().check(rules);
            assertThat(result.conditions()).isEqualTo(new Conditions(1000L, null, List.of()));
            assertThat(result.readyForRecommendation()).isFalse();
            assertThat(result.nextQuestion()).contains("월세");
            assertThat(result.notices()).isEmpty();
        }
    }

}
