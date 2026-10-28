package com.dooribeon.condition;

import com.dooribeon.condition.ConditionCheckResult.Kind;
import com.dooribeon.condition.ConditionsExtraction.ExtractedPlace;
import com.dooribeon.condition.ConditionsExtraction.Reason;
import com.dooribeon.condition.PlaceCondition.Importance;
import com.dooribeon.condition.PlaceCondition.TargetType;
import com.dooribeon.global.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 변경 목록에 있는 값만 반영한다. 전체 검사가 끝날 때까지 저장된 대화를 건드리지 않는다. */
@Component
public class ConditionsMerger {
    private static final Set<String> MONEY_FIELDS = Set.of("maxDeposit", "maxRent");
    private static final Set<String> PLACE_FIELDS = Set.of("label", "targetType", "query", "role",
            "importance", "transport", "maxMinutes", "frequency");
    private final ConditionsExtractionService extraction;
    private final ConditionsRules rules;

    public ConditionsMerger(ConditionsExtractionService extraction, ConditionsRules rules) {
        this.extraction = extraction;
        this.rules = rules;
    }

    public MergeResult merge(ConditionsDraft current, ConditionsUpdate update, String text, String question) {
        if (update == null || update.values() == null || !MONEY_FIELDS.containsAll(update.updatedFields())) throw invalid();
        var values = update.values();
        declared(update.updatedFields(), "maxDeposit", values.maxDeposit());
        declared(update.updatedFields(), "maxRent", values.maxRent());
        var added = extraction.prepare(values, text, question);
        var changed = update.updatedFields();
        Long deposit = changed.contains("maxDeposit") && !blocked(added, "maxDeposit")
                ? added.conditions().maxDeposit() : current.conditions().maxDeposit();
        Long rent = changed.contains("maxRent") && !blocked(added, "maxRent")
                ? added.conditions().maxRent() : current.conditions().maxRent();
        Map<String, PlaceCondition> places = new LinkedHashMap<>();
        current.conditions().places().forEach(p -> places.put(p.id(), p));
        Set<String> specified = new HashSet<>(current.importanceSpecifiedIds());
        specified.addAll(added.importanceSpecifiedIds());
        Set<String> workOrSchool = new HashSet<>(current.workOrSchoolIds());
        workOrSchool.addAll(added.workOrSchoolIds());
        List<String> notices = new ArrayList<>(added.notices());
        Set<String> seen = new HashSet<>();
        String deletionId = null;
        for (var patch : update.placeUpdates()) {
            if (!places.containsKey(patch.id()) || !seen.add(patch.id()) || patch.action() == null) throw invalid();
            if (patch.action() == ConditionsUpdate.Action.REQUEST_DELETE) {
                if (deletionId != null || patch.values() != null || !patch.updatedFields().isEmpty()) throw invalid();
                deletionId = patch.id();
                continue;
            }
            ExtractedPlace v = patch.values();
            Set<String> fields = patch.updatedFields();
            if (v == null || fields.isEmpty() || !PLACE_FIELDS.containsAll(fields)) throw invalid();
            validatePlacePatch(fields, v);
            var old = places.get(patch.id());
            var importance = fields.contains("importance") ? v.importance() : old.importance();
            if (fields.contains("importance")) specified.add(old.id());
            var type = fields.contains("targetType") ? v.targetType() : old.targetType();
            if (fields.contains("role")) {
                if (v.role() == null) workOrSchool.remove(old.id());
                else workOrSchool.add(old.id());
            }
            if (workOrSchool.contains(old.id()) && importance == null
                    && type == TargetType.FIXED_PLACE && !specified.contains(old.id())) {
                importance = Importance.REQUIRED;
                notices.add("회사·학교 장소는 서비스 기본값으로 중요도를 필수로 설정했어요. 원하시면 바꿀 수 있어요.");
            }
            // 위치나 대상 유형이 바뀌면 예전 검색 결과를 재사용하지 않는다. 이름 변경은 보존한다.
            boolean locationChanged = (fields.contains("query") && !Objects.equals(old.query(), v.query()))
                    || (fields.contains("targetType") && old.targetType() != type);
            places.put(old.id(), new PlaceCondition(old.id(), fields.contains("label") ? v.label() : old.label(),
                    type, fields.contains("query") ? v.query() : old.query(), locationChanged ? null : old.location(),
                    importance, fields.contains("transport") ? v.transport() : old.transport(),
                    fields.contains("maxMinutes") ? v.maxMinutes() : old.maxMinutes(),
                    fields.contains("frequency") ? v.frequency() : old.frequency()));
            if (v.frequencyUnclear()) notices.add("방문 횟수는 불분명해서 반영하지 않았어요. 주·월 횟수를 알려주셔도 돼요.");
        }
        added.conditions().places().forEach(p -> places.put(p.id(), p));
        var pending = new ArrayList<ConditionsDraft.PendingInput>();
        Set<String> known = new HashSet<>();
        for (var input : current.pendingInputs()) {
            known.add(input.id());
            if (!update.resolvedIssueIds().contains(input.id())) {
                pending.add(input);
                continue;
            }
            // 확인되지 않은 금액을 질문 목록에서만 제거해서 완료 처리할 수 없다.
            if (input.reason() == Reason.AMOUNT_UNIT_REQUIRED || input.reason() == Reason.INTEGER_MANWON_REQUIRED
                    || input.reason() == Reason.NET_RENT_REQUIRED) {
                String path = input.issue().path();
                boolean concrete = path.equals("budget")
                        ? (changed.contains("maxDeposit") && values.maxDeposit() != null)
                            || (changed.contains("maxRent") && values.maxRent() != null)
                        : changed.contains(path) && (path.equals("maxDeposit") ? values.maxDeposit() : values.maxRent()) != null;
                if (!concrete || blocked(added, path)) throw invalid();
            }
        }
        if (!known.containsAll(update.resolvedIssueIds())) throw invalid();
        for (var input : added.pendingInputs()) {
            boolean duplicate = pending.stream().anyMatch(p -> p.reason() == input.reason()
                    && p.issue().equals(input.issue()));
            if (!duplicate) pending.add(input);
        }
        var draft = new ConditionsDraft(new Conditions(deposit, rent, List.copyOf(places.values())),
                specified, pending, notices, workOrSchool);
        if (draft.check(rules).issues().stream().anyMatch(i -> i.kind() == Kind.INVALID)) throw invalid();
        return new MergeResult(draft, deletionId);
    }

    private boolean blocked(ConditionsDraft draft, String field) {
        return draft.pendingInputs().stream().anyMatch(p -> p.issue().path().equals(field)
                || p.issue().path().equals("budget") || p.reason() == Reason.UNSUPPORTED_RENTAL_TYPE);
    }

    private void validatePlacePatch(Set<String> fields, ExtractedPlace v) {
        declared(fields, "label", v.label());
        declared(fields, "targetType", v.targetType());
        declared(fields, "query", v.query());
        declared(fields, "role", v.role());
        declared(fields, "importance", v.importance());
        declared(fields, "transport", v.transport());
        declared(fields, "maxMinutes", v.maxMinutes());
        declared(fields, "frequency", v.frequency());
        if (v.importanceSpecified() != fields.contains("importance")) throw invalid();
        if (v.frequencyUnclear() && (!fields.contains("frequency") || v.frequency() != null)) throw invalid();
    }

    private void declared(Set<String> fields, String field, Object value) {
        if (!fields.contains(field) && value != null) throw invalid();
    }

    private ApiException invalid() {
        return new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE",
                "AI의 조건 변경 답변을 확인하지 못했어요. 기존 조건은 유지했으니 다시 시도해 주세요.");
    }

    public record MergeResult(ConditionsDraft draft, String deletionId) {}
}
