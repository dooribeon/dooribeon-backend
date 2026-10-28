package com.dooribeon.condition;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 공통 JSON 밖에 보관하는 대화 맥락. 미확인 입력은 다음 답변에도 함께 전달한다. */
public record ConditionsDraft(Conditions conditions, Set<String> importanceSpecifiedIds,
                              List<PendingInput> pendingInputs, List<String> notices, Set<String> workOrSchoolIds) {
    public ConditionsDraft {
        importanceSpecifiedIds = Set.copyOf(importanceSpecifiedIds);
        pendingInputs = List.copyOf(pendingInputs);
        notices = List.copyOf(notices);
        workOrSchoolIds = Set.copyOf(workOrSchoolIds);
    }

    public ConditionsDraft(Conditions conditions, Set<String> importanceSpecifiedIds,
                           List<PendingInput> pendingInputs, List<String> notices) {
        this(conditions, importanceSpecifiedIds, pendingInputs, notices, Set.of());
    }

    public ConditionCheckResult check(ConditionsRules rules) {
        var checked = rules.check(conditions);
        var issues = new ArrayList<ConditionCheckResult.Issue>();
        pendingInputs.forEach(p -> issues.add(p.issue()));
        for (var issue : checked.issues()) {
            boolean covered = issue.kind() == ConditionCheckResult.Kind.MISSING
                    && Set.of("maxDeposit", "maxRent").contains(issue.path())
                    && pendingInputs.stream().anyMatch(p -> p.issue().path().equals("budget")
                        || p.issue().path().equals(issue.path())
                        || p.reason() == ConditionsExtraction.Reason.UNSUPPORTED_RENTAL_TYPE);
            if (!covered) issues.add(issue);
        }
        var messages = new ArrayList<>(notices);
        messages.addAll(checked.notices());
        return new ConditionCheckResult(checked.conditions(), issues, messages.stream().distinct().toList());
    }

    public record PendingInput(String id, ConditionCheckResult.Issue issue,
                               ConditionsExtraction.Reason reason, String sourceText, String question) {
        public static PendingInput create(ConditionCheckResult.Issue issue, ConditionsExtraction.Reason reason,
                                          String text, String question) {
            return new PendingInput(UUID.randomUUID().toString(), issue, reason, text, question);
        }
    }
}
