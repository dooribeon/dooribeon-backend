package com.dooribeon.condition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dooribeon.condition.ConditionsExtraction.ExtractedPlace;
import com.dooribeon.condition.ConditionsExtraction.Role;
import com.dooribeon.condition.PlaceCondition.*;
import com.dooribeon.global.ApiException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ConditionConversationServiceTests {
    final ConditionsExtractor extractor = mock(ConditionsExtractor.class);
    final ConditionsRules rules = new ConditionsRules();
    final ConditionsExtractionService extraction = new ConditionsExtractionService(extractor, rules);
    final ConditionsMerger merger = new ConditionsMerger(extraction, rules);
    final Clock clock = mock(Clock.class);
    final Instant now = Instant.parse("2026-10-28T00:00:00Z");

    ConditionConversationService service(int capacity) {
        when(clock.instant()).thenReturn(now);
        when(extractor.extract(anyString())).thenReturn(values(null, 50L));
        return new ConditionConversationService(extractor, extraction, rules, merger, mock(com.dooribeon.condition.place.PlaceSearchClient.class), clock, Duration.ofMinutes(30), capacity);
    }

    static ConditionsExtraction values(Long deposit, Long rent) {
        return new ConditionsExtraction(deposit, rent, List.of(), List.of(), List.of());
    }
    static ConditionsUpdate money(Long deposit, Long rent, String field, Set<String> resolved) {
        return new ConditionsUpdate(values(deposit, rent), Set.of(field), List.of(), resolved);
    }
    static ConditionsUpdate noChange() { return new ConditionsUpdate(values(null, null), Set.of(), List.of(), Set.of()); }
    static ExtractedPlace place(String label, Role role, Importance importance, boolean specified, Integer minutes) {
        return new ExtractedPlace(label, null, null, role, importance, specified, null, minutes, null, false);
    }
    static ConditionsUpdate placeUpdate(String id, Set<String> fields, ExtractedPlace value) {
        return new ConditionsUpdate(values(null, null), Set.of(),
                List.of(new ConditionsUpdate.PlaceUpdate(id, ConditionsUpdate.Action.UPDATE, fields, value)), Set.of());
    }

    @Test
    void followupKeepsRentAndZeroIsDifferentFromClearing() {
        var service = service(10);
        var first = service.start("월세 50만 원");
        when(extractor.extractUpdate(anyString(), any(), anyString()))
                .thenReturn(money(1000L, null, "maxDeposit", Set.of()))
                .thenReturn(money(0L, null, "maxDeposit", Set.of()))
                .thenReturn(money(null, null, "maxDeposit", Set.of()));
        var result = service.reply(first.conversationId(), "천만 원");
        assertThat(result.conditions()).isEqualTo(new Conditions(1000L, 50L, List.of()));
        assertThat(result.readyForRecommendation()).isTrue();
        assertThat(service.reply(first.conversationId(), "무보증").conditions().maxDeposit()).isZero();
        var cleared = service.reply(first.conversationId(), "보증금 지워줘");
        assertThat(cleared.conditions().maxDeposit()).isNull();
        assertThat(cleared.readyForRecommendation()).isFalse();
    }

    @Test
    void unitConfirmationKeepsOriginalContextAcrossUnrelatedReplyAndFailure() {
        var service = service(10);
        var first = service.start("월세 50만 원");
        var pending = service.reply(first.conversationId(), "1000000");
        assertThat(pending.conditions()).isEqualTo(first.conditions());
        assertThat(pending.nextQuestion()).contains("단위");
        verify(extractor, never()).extractUpdate(anyString(), any(), anyString());
        when(extractor.extractUpdate(anyString(), any(), anyString())).thenReturn(noChange());
        assertThat(service.reply(first.conversationId(), "잠깐만").readyForRecommendation()).isFalse();
        when(extractor.extractUpdate(eq("100만원"), any(), anyString())).thenThrow(new RuntimeException("provider failed"))
                .thenAnswer(inv -> {
                    ConditionsDraft draft = inv.getArgument(1);
                    var input = draft.pendingInputs().getFirst();
                    assertThat(input.sourceText()).isEqualTo("1000000");
                    assertThat(input.question()).contains("보증금");
                    return money(100L, null, "maxDeposit", Set.of(input.id()));
                });
        assertThatThrownBy(() -> service.reply(first.conversationId(), "100만원")).isInstanceOf(RuntimeException.class);
        var result = service.reply(first.conversationId(), "100만원");
        assertThat(result.conditions()).isEqualTo(new Conditions(100L, 50L, List.of()));
        assertThat(result.readyForRecommendation()).isTrue();
    }

    @Test
    void mergerKeepsLocationOnRenameClearsItOnMoveAndDoesNotReapplyClearedImportance() {
        var location = new PlaceLocation("kakao-1", "여의도역", "서울 영등포구", 37.52, 126.92);
        var original = new PlaceCondition("p1", "회사", TargetType.FIXED_PLACE, "여의도역", location,
                Importance.REQUIRED, Transport.PUBLIC_TRANSIT, 40, new VisitFrequency(VisitFrequency.Period.MONTH, 2));
        var state = new ConditionsDraft(new Conditions(1000L, 50L, List.of(original)), Set.of(), List.of(), List.of());
        var renamed = merger.merge(state, placeUpdate("p1", Set.of("label"), place(null, null, null, false, null)), "이름 지워줘", "").draft();
        assertThat(renamed.conditions().places().getFirst().location()).isEqualTo(location);
        var cleared = merger.merge(renamed, placeUpdate("p1", Set.of("importance"), place(null, null, null, true, null)), "필수는 아니야", "").draft();
        var roleAgain = merger.merge(cleared, placeUpdate("p1", Set.of("role"), place(null, Role.WORK, null, false, null)), "일하는 곳이야", "").draft();
        assertThat(roleAgain.conditions().places().getFirst().importance()).isNull();
        var movedValue = new ExtractedPlace(null, null, "강남역", null, null, false, null, null, null, false);
        var moved = merger.merge(roleAgain, placeUpdate("p1", Set.of("query"), movedValue), "강남역으로 옮겼어", "").draft();
        var actual = moved.conditions().places().getFirst();
        assertThat(actual.id()).isEqualTo("p1");
        assertThat(actual.location()).isNull();
        assertThat(actual.frequency()).isEqualTo(original.frequency());
        assertThat(actual.maxMinutes()).isEqualTo(40);
    }

    @Test
    void numericTimeAnswerDoesNotEnterMoneyUnitFlow() {
        var service = service(10);
        // 지도 선택 이후 상태를 첫 추출 결과로 공급하여 시간 질문의 라우팅만 검증한다.
        var extractionMock = mock(ConditionsExtractionService.class);
        var p = new PlaceCondition("p1", "회사", TargetType.FIXED_PLACE, "여의도역",
                new PlaceLocation(null, "여의도역", null, 37.52, 126.92), Importance.REQUIRED, Transport.PUBLIC_TRANSIT, null, null);
        var draft = new ConditionsDraft(new Conditions(1000L, 50L, List.of(p)), Set.of(), List.of(), List.of());
        when(extractionMock.extractDraft(anyString())).thenReturn(draft);
        service = new ConditionConversationService(extractor, extractionMock, rules, merger, mock(com.dooribeon.condition.place.PlaceSearchClient.class), clock, Duration.ofMinutes(30), 10);
        var first = service.start("시작");
        assertThat(first.nextQuestion()).contains("몇 분");
        when(extractor.extractUpdate(eq("40"), any(), anyString()))
                .thenReturn(placeUpdate("p1", Set.of("maxMinutes"), place(null, null, null, false, 40)));
        var result = service.reply(first.conversationId(), "40");
        assertThat(result.conditions().places().getFirst().maxMinutes()).isEqualTo(40);
        assertThat(result.readyForRecommendation()).isTrue();
    }

    @Test
    void deletionRequiresExactConfirmationAndLastPlaceCanBeRemoved() {
        var service = service(10);
        var p = new ExtractedPlace("회사", TargetType.FIXED_PLACE, "여의도역", Role.WORK, null, false, null, null, null, false);
        when(extractor.extract(anyString())).thenReturn(new ConditionsExtraction(1000L, 50L, List.of(p), List.of(), List.of()));
        var first = service.start("보증금 천만 원 월세 50만 원 회사 여의도역");
        String placeId = first.conditions().places().getFirst().id();
        when(extractor.extractUpdate(anyString(), any(), anyString())).thenReturn(new ConditionsUpdate(values(null, null), Set.of(),
                List.of(new ConditionsUpdate.PlaceUpdate(placeId, ConditionsUpdate.Action.REQUEST_DELETE, Set.of(), null)), Set.of()));
        var requested = service.reply(first.conversationId(), "회사 삭제해줘");
        assertThat(requested.conditions().places()).hasSize(1);
        assertThat(requested.deletionConfirmation().placeId()).isEqualTo(placeId);
        assertThat(service.reply(first.conversationId(), "응").conditions().places()).hasSize(1);
        var result = service.reply(first.conversationId(), "회사 삭제하기");
        assertThat(result.conditions().places()).isEmpty();
        assertThat(result.readyForRecommendation()).isTrue();
        verify(extractor, times(1)).extractUpdate(anyString(), any(), anyString());
    }

    @Test
    void invalidPatchDoesNotOverwriteAndExpiredConversationsDoNotCallAi() {
        var service = service(1);
        var first = service.start("월세 50만 원");
        when(extractor.extractUpdate(anyString(), any(), anyString()))
                .thenReturn(money(-1L, null, "maxDeposit", Set.of())).thenReturn(noChange());
        assertThatThrownBy(() -> service.reply(first.conversationId(), "-1만원")).isInstanceOf(ApiException.class);
        assertThat(service.reply(first.conversationId(), "그대로").conditions()).isEqualTo(first.conditions());
        assertThatThrownBy(() -> service.start("다른 대화")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("CONVERSATION_CAPACITY"));
        when(clock.instant()).thenReturn(now.plus(Duration.ofMinutes(30)));
        assertThatThrownBy(() -> service.reply(first.conversationId(), "천만 원")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("CONVERSATION_NOT_FOUND"));
        verify(extractor, times(2)).extractUpdate(anyString(), any(), anyString());
        assertThat(service.start("월세 50만 원").conversationId()).isNotEqualTo(first.conversationId());
    }

    @Test
    void invalidTargetAndUnconfirmedResolutionCannotSilentlyChangeState() {
        var state = new ConditionsDraft(new Conditions(1000L, 50L, List.of()), Set.of(), List.of(), List.of());
        assertThatThrownBy(() -> merger.merge(state,
                placeUpdate("unknown", Set.of("label"), place("회사", null, null, false, null)), "이름 변경", ""))
                .isInstanceOf(ApiException.class);
        var pending = ConditionsDraft.PendingInput.create(new ConditionCheckResult.Issue("budget",
                ConditionCheckResult.Kind.NEEDS_CONFIRMATION, "단위를 알려주세요"),
                ConditionsExtraction.Reason.AMOUNT_UNIT_REQUIRED, "월세 100", "");
        var waiting = new ConditionsDraft(state.conditions(), Set.of(), List.of(pending), List.of());
        assertThatThrownBy(() -> merger.merge(waiting,
                new ConditionsUpdate(values(null, null), Set.of(), List.of(), Set.of(pending.id())), "응", ""))
                .isInstanceOf(ApiException.class);
        var guessed = merger.merge(state, money(null, 100L, "maxRent", Set.of()), "월세 1000000", "").draft();
        assertThat(guessed.conditions()).isEqualTo(state.conditions());
        assertThat(guessed.check(rules).readyForRecommendation()).isFalse();
    }

    @Test
    void simultaneousRepliesCannotOverwriteEachOther() throws Exception {
        var service = service(10);
        var first = service.start("월세 50만 원");
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(extractor.extractUpdate(anyString(), any(), anyString())).thenAnswer(inv -> {
            entered.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("test timeout");
            return money(1000L, null, "maxDeposit", Set.of());
        });
        var response = CompletableFuture.supplyAsync(() -> service.reply(first.conversationId(), "천만 원"));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.reply(first.conversationId(), "이천만 원")).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo("CONVERSATION_BUSY"));
        } finally { finish.countDown(); }
        assertThat(response.get(5, TimeUnit.SECONDS).conditions().maxDeposit()).isEqualTo(1000L);
    }
}
