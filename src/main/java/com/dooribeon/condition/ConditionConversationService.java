package com.dooribeon.condition;

import com.dooribeon.global.ApiException;
import com.dooribeon.condition.place.PlaceSearchClient;
import com.dooribeon.condition.place.PlaceSearchRequest;
import com.dooribeon.condition.place.PlaceSearchResponse;
import com.dooribeon.condition.place.PlaceSelectionRequest;
import java.util.List;
import java.util.function.Function;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 첫 구현의 단일 서버용 임시 저장소. 마지막 성공 응답 후 30분 동안 보관한다. */
@Service
public class ConditionConversationService {
    private final ConditionsExtractor extractor;
    private final ConditionsExtractionService extraction;
    private final ConditionsRules rules;
    private final ConditionsMerger merger;
    private final PlaceSearchClient placeSearch;
    private final Clock clock;
    private final Duration lifetime;
    private final int capacity;
    private final ConcurrentHashMap<UUID, Conversation> conversations = new ConcurrentHashMap<>();

    @Autowired
    public ConditionConversationService(ConditionsExtractor extractor, ConditionsExtractionService extraction, ConditionsRules rules, ConditionsMerger merger, PlaceSearchClient placeSearch) {
        this(extractor, extraction, rules, merger, placeSearch, Clock.systemUTC(), Duration.ofMinutes(30), 1000);
    }

    ConditionConversationService(ConditionsExtractor extractor, ConditionsExtractionService extraction, ConditionsRules rules, ConditionsMerger merger, PlaceSearchClient placeSearch,
                                 Clock clock, Duration lifetime, int capacity) {
        this.extractor = extractor;
        this.extraction = extraction;
        this.rules = rules;
        this.merger = merger;
        this.placeSearch = placeSearch;
        this.clock = clock;
        this.lifetime = lifetime;
        this.capacity = capacity;
    }

    public ConditionConversationResponse start(String text) {
        checkCapacity();
        ConditionsDraft draft = extraction.extractDraft(text);
        return saveNew(draft);
    }

    private synchronized ConditionConversationResponse saveNew(ConditionsDraft draft) {
        checkCapacity();
        UUID id = UUID.randomUUID();
        Conversation conversation = new Conversation(draft, clock.instant());
        conversations.put(id, conversation);
        return response(id, conversation);
    }

    private synchronized void checkCapacity() {
        removeExpired();
        if (conversations.size() >= capacity) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONVERSATION_CAPACITY",
                    "지금은 새 대화를 시작할 수 없어요. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    public ConditionConversationResponse reply(UUID id, String text) {
        return withConversation(id, conversation -> {
            validateText(text);
            if (conversation.deletion != null) {
                if (text.strip().equals(conversation.deletion.confirmationText())) {
                    String deletedId = conversation.deletion.placeId();
                    var old = conversation.draft;
                    var places = old.conditions().places().stream().filter(p -> !p.id().equals(deletedId)).toList();
                    var specified = new java.util.HashSet<>(old.importanceSpecifiedIds());
                    specified.remove(deletedId);
                    var workOrSchool = new java.util.HashSet<>(old.workOrSchoolIds());
                    workOrSchool.remove(deletedId);
                    conversation.draft = new ConditionsDraft(new Conditions(old.conditions().maxDeposit(),
                            old.conditions().maxRent(), places), specified, old.pendingInputs(), java.util.List.of("장소를 삭제했어요."), workOrSchool);
                    conversation.deletion = null;
                } else if (text.strip().equals("삭제 취소")) {
                    conversation.deletion = null;
                }
                // 확인 대기 중 다른 입력으로 대상을 바꾸거나 AI가 삭제를 확정하지 못하게 한다.
                conversation.lastSuccess = clock.instant();
                return response(id, conversation);
            }
            ConditionCheckResult previous = conversation.draft.check(rules);
            String question = previous.nextQuestion() == null ? "" : previous.nextQuestion();
            ConditionsUpdate update;
            boolean moneyQuestion = !previous.issues().isEmpty()
                    && java.util.Set.of("maxDeposit", "maxRent", "budget").contains(previous.issues().getFirst().path());
            if (moneyQuestion && AmountUnitPolicy.isBareAmountWithoutWon(text)) {
                // 숫자만 답해도 시간·빈도 질문이면 이 경로로 들어오지 않는다.
                update = new ConditionsUpdate(new ConditionsExtraction(null, null, java.util.List.of(),
                        java.util.List.of(new ConditionsExtraction.Clarification("budget",
                                ConditionsExtraction.Reason.AMOUNT_UNIT_REQUIRED)), java.util.List.of()),
                        java.util.Set.of(), java.util.List.of(), java.util.Set.of());
            } else {
                update = extractor.extractUpdate(text, conversation.draft, question);
            }
            var merged = merger.merge(conversation.draft, update, text, question);
            DeletionConfirmation deletion = null;
            if (merged.deletionId() != null) {
                var place = merged.draft().conditions().places().stream()
                        .filter(p -> p.id().equals(merged.deletionId())).findFirst().orElseThrow();
                String name = displayName(place);
                String address = place.location() == null ? null : place.location().address();
                deletion = new DeletionConfirmation(place.id(), name, address, name + " 삭제하기");
            }
            // 호출·변경 검사 성공 후 한 번에 저장한다. 예외가 나면 원래 상태를 그대로 둔다.
            conversation.draft = merged.draft();
            conversation.deletion = deletion;
            // 조건 변경 후 이전 검색 화면에서 뒤늦게 선택해도 적용하지 않는다.
            conversation.search = null;
            conversation.lastSuccess = clock.instant();
            return response(id, conversation);
        });
    }

    /** 검색만으로 location을 확정하지 않는다. 한 대화의 마지막 검색 결과만 선택할 수 있다. */
    public PlaceSearchResponse searchPlace(UUID id, String placeId, PlaceSearchRequest request) {
        return withConversation(id, conversation -> {
            requireSelectablePlace(conversation, placeId);
            if (request == null || request.query() == null || request.query().isBlank()
                    || request.query().length() > 200 || request.type() == null || request.page() == null
                    || request.page() < 1 || request.page() > 45) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PLACE_SEARCH", "검색어·검색 방식·페이지를 확인해 주세요.");
            }
            var result = placeSearch.search(request.query(), request.type(), request.page());
            List<PlaceSearchResponse.Candidate> candidates = result.locations().stream()
                    .map(location -> new PlaceSearchResponse.Candidate(UUID.randomUUID(), location)).toList();
            var response = new PlaceSearchResponse(UUID.randomUUID(), placeId, request.query().strip(), request.type(),
                    request.page(), result.hasNext(), clock.instant().plus(Duration.ofMinutes(5)), candidates);
            // 현재 선택 위치는 유지한다. 새 위치는 사용자가 후보를 선택한 뒤 반영한다.
            conversation.search = response;
            conversation.lastSuccess = clock.instant();
            return response;
        });
    }

    public ConditionConversationResponse selectPlace(UUID id, String placeId, PlaceSelectionRequest request) {
        return withConversation(id, conversation -> {
            var oldPlace = requireSelectablePlace(conversation, placeId);
            var search = conversation.search;
            if (request == null || request.searchId() == null || request.candidateId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PLACE_SELECTION", "검색 번호와 후보 번호를 보내 주세요.");
            }
            if (search == null || !search.searchId().equals(request.searchId())
                    || !search.placeId().equals(placeId) || !clock.instant().isBefore(search.expiresAt())) {
                throw new ApiException(HttpStatus.CONFLICT, "STALE_PLACE_SEARCH", "검색 결과가 바뀌었거나 만료됐어요. 다시 검색해 주세요.");
            }
            var candidate = search.candidates().stream().filter(c -> c.candidateId().equals(request.candidateId()))
                    .findFirst().orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PLACE_SELECTION",
                            "이 검색 결과에 없는 후보예요. 반환된 후보 번호를 선택해 주세요."));
            var old = conversation.draft;
            var updatedPlace = new PlaceCondition(oldPlace.id(), oldPlace.label(), oldPlace.targetType(), search.query(),
                    candidate.location(), oldPlace.importance(), oldPlace.transport(), oldPlace.maxMinutes(), oldPlace.frequency());
            var places = old.conditions().places().stream().map(p -> p.id().equals(placeId) ? updatedPlace : p).toList();
            var draft = new ConditionsDraft(new Conditions(old.conditions().maxDeposit(), old.conditions().maxRent(), places),
                    old.importanceSpecifiedIds(), old.pendingInputs(), List.of("선택한 위치를 반영했어요."), old.workOrSchoolIds());
            if (draft.check(rules).issues().stream().anyMatch(issue -> issue.kind() == ConditionCheckResult.Kind.INVALID)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PLACE_SEARCH_INVALID_RESPONSE", "선택 위치를 확인하지 못했어요. 다시 검색해 주세요.");
            }
            conversation.draft = draft;
            conversation.search = null;
            conversation.lastSuccess = clock.instant();
            return response(id, conversation);
        });
    }

    private PlaceCondition requireSelectablePlace(Conversation conversation, String placeId) {
        if (conversation.deletion != null) {
            throw new ApiException(HttpStatus.CONFLICT, "PLACE_DELETE_PENDING", "먼저 장소 삭제를 확인하거나 '삭제 취소'를 입력해 주세요.");
        }
        var place = conversation.draft.conditions().places().stream().filter(p -> p.id().equals(placeId))
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "이 대화에 해당 장소가 없어요."));
        if (place.targetType() != PlaceCondition.TargetType.FIXED_PLACE) {
            throw new ApiException(HttpStatus.CONFLICT, "PLACE_TYPE_NOT_SUPPORTED", "특정 장소로 지정한 뒤 위치를 검색해 주세요.");
        }
        return place;
    }

    private <T> T withConversation(UUID id, Function<Conversation, T> action) {
        Conversation conversation = conversations.get(id);
        if (conversation == null) throw missingConversation();
        if (!conversation.lock.tryLock()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONVERSATION_BUSY",
                    "이 대화의 이전 요청을 처리 중이에요. 결과를 받은 뒤 다시 보내 주세요.");
        }
        try {
            if (conversations.get(id) != conversation || expired(conversation)) {
                conversations.remove(id, conversation);
                throw missingConversation();
            }
            return action.apply(conversation);
        } finally {
            conversation.lock.unlock();
        }
    }

    private void removeExpired() {
        conversations.forEach((id, conversation) -> {
            if (conversation.lock.tryLock()) {
                try {
                    if (expired(conversation)) conversations.remove(id, conversation);
                } finally {
                    conversation.lock.unlock();
                }
            }
        });
    }

    private boolean expired(Conversation conversation) {
        return !clock.instant().isBefore(conversation.lastSuccess.plus(lifetime));
    }

    private ApiException missingConversation() {
        return new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND",
                "대화가 없거나 만료됐어요. 새 대화를 시작해 주세요.");
    }

    private ConditionConversationResponse response(UUID id, Conversation conversation) {
        var checked = conversation.draft.check(rules);
        return ConditionConversationResponse.of(id, checked, conversation.deletion);
    }

    private String displayName(PlaceCondition place) {
        if (place.label() != null) return place.label();
        if (place.location() != null && place.location().placeName() != null) return place.location().placeName();
        if (place.location() != null && place.location().address() != null) return place.location().address();
        return place.query() == null ? "이 장소" : place.query();
    }

    private void validateText(String text) {
        if (text == null || text.isBlank() || text.length() > 10000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "답변은 1~10000자로 입력해 주세요.");
        }
    }

    public record DeletionConfirmation(String placeId, String displayName, String address, String confirmationText) {}

    private static class Conversation {
        final ReentrantLock lock = new ReentrantLock();
        ConditionsDraft draft;
        DeletionConfirmation deletion;
        PlaceSearchResponse search;
        Instant lastSuccess;

        Conversation(ConditionsDraft draft, Instant now) {
            this.draft = draft;
            this.lastSuccess = now;
        }
    }
}
