package com.dooribeon.condition;

import com.dooribeon.condition.ConditionCheckResult.Issue;
import com.dooribeon.condition.ConditionCheckResult.Kind;
import com.dooribeon.condition.ConditionsExtraction.Clarification;
import com.dooribeon.condition.ConditionsExtraction.Reason;
import com.dooribeon.condition.PlaceCondition.TargetType;
import com.dooribeon.global.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 최초 문장 → AI 추출 → 서버 id·기본값 → 입력 검사. */
@Service
public class ConditionsExtractionService {
    private static final Pattern WON = Pattern.compile("[0-9영공일이삼사오육칠팔구십백천만억조]\\s*원");
    private static final Pattern NO_DEPOSIT = Pattern.compile("무보증|보증금\\s*(?:은|이|도)?\\s*(?:없이|없)");
    private final ConditionsExtractor extractor;
    private final ConditionsRules rules;

    public ConditionsExtractionService(ConditionsExtractor extractor, ConditionsRules rules) {
        this.extractor = extractor;
        this.rules = rules;
    }

    public ConditionCheckResult parse(String text) {
        return extractDraft(text).check(rules);
    }

    public ConditionsDraft extractDraft(String text) {
        if (text == null || text.isBlank() || text.length() > 10000) {
            throw new IllegalArgumentException("추출할 문장은 1~10000자로 입력해 주세요.");
        }
        return prepare(extractor.extract(text), text, "");
    }

    ConditionsDraft prepare(ConditionsExtraction extraction, String text, String question) {
        if (extraction == null) throw ApiException.invalidAiResponse();
        List<Clarification> clarifications = new ArrayList<>(extraction.clarifications());
        Long deposit = extraction.maxDeposit();
        Long rent = extraction.maxRent();
        // 원 단위가 전혀 없는데 AI가 금액을 추측했다면 서버에서도 확정을 막는다.
        boolean explicitNoDepositOnly = Long.valueOf(0).equals(deposit) && rent == null
                && NO_DEPOSIT.matcher(text).find();
        if ((deposit != null || rent != null) && !WON.matcher(text).find() && !explicitNoDepositOnly) {
            deposit = null;
            rent = null;
            var unit = new Clarification("budget", Reason.AMOUNT_UNIT_REQUIRED);
            if (!clarifications.contains(unit)) clarifications.add(unit);
        }
        List<PlaceCondition> places = new ArrayList<>();
        Set<String> defaultRequired = new HashSet<>();
        Set<String> userSpecified = new HashSet<>();
        List<String> extraNotices = new ArrayList<>();
        for (var extracted : extraction.places()) {
            String id = UUID.randomUUID().toString();
            places.add(new PlaceCondition(id, extracted.label(), extracted.targetType(), extracted.query(), null,
                    extracted.importance(), extracted.transport(), extracted.maxMinutes(), extracted.frequency()));
            if (extracted.role() != null) defaultRequired.add(id);
            if (extracted.importanceSpecified()) userSpecified.add(id);
            if (extracted.frequencyUnclear()) {
                extraNotices.add((extracted.label() == null ? "장소" : extracted.label())
                        + "의 방문 횟수는 확실하지 않아 반영하지 않았어요. 원하시면 주·월 횟수를 입력할 수 있어요.");
            }
        }
        ConditionCheckResult checked = rules.check(new Conditions(deposit, rent, places),
                new ConditionsRules.InputContext(defaultRequired, userSpecified, false));
        List<ConditionsDraft.PendingInput> pending = new ArrayList<>();
        for (Clarification c : clarifications) {
            pending.add(ConditionsDraft.PendingInput.create(toIssue(c), c.reason(), text, question));
        }
        for (String fragment : extraction.unhandledRequests()) {
            if (!text.contains(fragment)) throw ApiException.invalidAiResponse();
            pending.add(ConditionsDraft.PendingInput.create(new Issue("conditions", Kind.UNSUPPORTED,
                    "아직 반영할 수 없는 조건이 있어요: " + fragment
                            + ". 이 조건을 제외하거나 지원하는 조건으로 바꿀지 알려주세요."), null, text, question));
        }
        List<String> notices = new ArrayList<>(checked.notices());
        notices.addAll(extraNotices);
        return new ConditionsDraft(checked.conditions(), userSpecified, pending, notices, defaultRequired);
    }

    private Issue toIssue(Clarification c) {
        String message = switch (c.reason()) {
            case AMOUNT_UNIT_REQUIRED -> "금액 단위를 확인할게요. '100만 원'처럼 원 단위를 붙여 알려주세요.";
            case INTEGER_MANWON_REQUIRED -> "금액은 만원 단위 정수로 입력해 주세요. 만원 미만 금액을 반올림하지는 않아요.";
            case NET_RENT_REQUIRED -> "관리비를 제외한 월세는 최대 얼마까지 생각하고 있나요?";
            case UNSUPPORTED_RENTAL_TYPE -> "지금은 월세 조건을 지원해요. 월세로 찾으실지 확인해 주세요.";
            case AMBIGUOUS_INPUT -> "조건의 뜻이 불분명한 부분이 있어요. 금액·장소·편도시간을 구체적으로 알려주세요.";
        };
        return new Issue(c.field(), c.reason() == Reason.UNSUPPORTED_RENTAL_TYPE
                ? Kind.UNSUPPORTED : Kind.NEEDS_CONFIRMATION, message);
    }
}
