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
        return prepare(extraction, text, question, List.of());
    }

    ConditionsDraft prepare(ConditionsExtraction extraction, String text, String question,
                            List<PlaceCondition> existingPlaces) {
        if (extraction == null) throw ApiException.invalidAiResponse();
        List<Clarification> clarifications = new ArrayList<>(extraction.clarifications());
        var amounts = BudgetInputPolicy.read(text, question, extraction);
        Long deposit = amounts.deposit();
        Long rent = amounts.rent();
        clarifications.removeIf(c -> amounts.handled().contains(c.field())
                && (c.reason() == Reason.AMOUNT_UNIT_REQUIRED || c.reason() == Reason.INTEGER_MANWON_REQUIRED));
        if (amounts.handled().containsAll(java.util.Set.of("maxDeposit", "maxRent"))) {
            clarifications.removeIf(c -> c.field().equals("budget") && c.reason() == Reason.AMOUNT_UNIT_REQUIRED);
        }
        clarifications.addAll(amounts.questions());
        var extractedPlaces = new ArrayList<>(extraction.places());
        String ambiguousTravel = AmbiguousTravelTarget.phrase(text, existingPlaces.size() + extractedPlaces.size());
        if (ambiguousTravel != null) {
            extractedPlaces.replaceAll(p -> new ConditionsExtraction.ExtractedPlace(p.label(), p.targetType(), p.query(), p.role(),
                    p.importance(), p.importanceSpecified(), p.transport(), null, p.frequency(), p.frequencyUnclear(), p.areaHint()));
            var clarification = new Clarification("conditions", Reason.AMBIGUOUS_INPUT, ambiguousTravel);
            clarifications.remove(clarification);
            clarifications.addFirst(clarification);
        }
        Set<String> recoveredFamilyRequests = new HashSet<>();
        List<ConditionsDraft.PendingInput> familyConfirmations = new ArrayList<>();
        for (String fragment : extraction.unhandledRequests()) {
            if (!text.contains(fragment)) throw ApiException.invalidAiResponse();
            var home = FamilyPlaceRequest.recover(fragment);
            var existingHome = existingPlaces.stream().filter(place -> FamilyPlaceRequest.isHome(place.label())).findFirst();
            if (home != null && existingHome.isEmpty()) {
                if (extractedPlaces.stream().noneMatch(place -> FamilyPlaceRequest.isHome(place.label()))) {
                    extractedPlaces.add(home);
                }
                recoveredFamilyRequests.add(fragment);
            } else if (home != null) {
                recoveredFamilyRequests.add(fragment);
                if (!FamilyPlaceRequest.isVisit(fragment)) {
                    familyConfirmations.add(ConditionsDraft.PendingInput.create(new Issue("conditions",
                            Kind.NEEDS_CONFIRMATION, "본가의 정확한 주소나 근처 기준 장소를 알려주세요."),
                            Reason.AMBIGUOUS_INPUT, text, question));
                }
            }
        }
        List<PlaceCondition> places = new ArrayList<>();
        Set<String> defaultRequired = new HashSet<>();
        Set<String> userSpecified = new HashSet<>();
        List<String> extraNotices = new ArrayList<>();
        for (var extracted : extractedPlaces) {
            String id = UUID.randomUUID().toString();
            places.add(new PlaceCondition(id, extracted.label(), extracted.targetType(), extracted.query(), null,
                    extracted.importance(), extracted.transport(), extracted.maxMinutes(), extracted.frequency(), extracted.areaHint()));
            if (extracted.role() != null) defaultRequired.add(id);
            if (extracted.importanceSpecified()) userSpecified.add(id);
            if (extracted.frequencyUnclear()) {
                extraNotices.add(unclearFrequencyNotice(text, extracted.label()));
            }
        }
        ConditionCheckResult checked = rules.check(new Conditions(deposit, rent, places),
                new ConditionsRules.InputContext(defaultRequired, userSpecified, false));
        List<ConditionsDraft.PendingInput> pending = new ArrayList<>(familyConfirmations);
        for (Clarification c : clarifications) {
            if (c.sourceText() != null && !text.contains(c.sourceText())) throw ApiException.invalidAiResponse();
            Issue issue = toIssue(c, text, ambiguousTravel);
            if (c.reason() == Reason.AMBIGUOUS_INPUT && ambiguousTravel != null) {
                var names = new ArrayList<String>();
                existingPlaces.forEach(p -> names.add(p.label() == null ? "이름 없는 장소" : p.label()));
                places.forEach(p -> names.add(p.label() == null ? "이름 없는 장소" : p.label()));
                issue = new Issue(c.field(), Kind.NEEDS_CONFIRMATION,
                        "‘" + ambiguousTravel + "’의 편도시간은 " + String.join(", ", names) + " 중 어느 장소까지인가요? 장소 이름을 알려주세요.");
            }
            pending.add(ConditionsDraft.PendingInput.create(issue, c.reason(), text, question));
        }
        for (String fragment : extraction.unhandledRequests()) {
            if (!text.contains(fragment)) throw ApiException.invalidAiResponse();
            if (recoveredFamilyRequests.contains(fragment) || isRepeatedChurchVisit(fragment, extraction, existingPlaces)) continue;
            pending.add(ConditionsDraft.PendingInput.create(new Issue("conditions", Kind.UNSUPPORTED,
                    "아직 반영할 수 없는 조건이 있어요: " + fragment
                            + ". 이 조건을 제외하거나 지원하는 조건으로 바꿀지 알려주세요."), null, text, question));
        }
        List<String> notices = new ArrayList<>(checked.notices());
        notices.addAll(extraNotices);
        notices.addAll(amounts.notices());
        return new ConditionsDraft(checked.conditions(), userSpecified, pending, notices, defaultRequired);
    }

    private boolean isRepeatedChurchVisit(String fragment, ConditionsExtraction extraction,
                                          List<PlaceCondition> existingPlaces) {
        Set<String> names = new HashSet<>();
        extraction.places().stream().filter(place -> place.targetType() == TargetType.FIXED_PLACE)
                .forEach(place -> addChurchNames(names, place.label(), place.query()));
        existingPlaces.stream().filter(place -> place.targetType() == TargetType.FIXED_PLACE)
                .forEach(place -> addChurchNames(names, place.label(), place.query()));
        if (names.isEmpty()) return false;
        names.add("교회");
        String church = names.stream().map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        // 방문 의사만 담긴 문장 전체가 일치할 때만 중복을 제거한다.
        // 치안·소음·자가용 등의 요구가 함께 있으면 원래 미지원 안내를 보존한다.
        String visit = "^(?:(?:지금|현재|기존에?)\\s*)?(?:(?:다니는|다녔던|가던)\\s*)?"
                + "(?:" + church + ")\\s*(?:을|를|에)?\\s*(?:계속\\s*)?"
                + "(?:(?:다니고\\s*싶(?:어(?:요)?|습니다)|다녀(?:요)?|다닐\\s*거(?:야|예요)))?[.!?。\\s]*$";
        return Pattern.matches(visit, fragment.strip());
    }

    private void addChurchNames(Set<String> names, String label, String query) {
        for (String name : new String[] {label, query}) {
            if (name != null && name.strip().endsWith("교회")) names.add(name.strip());
        }
    }

    static String unclearFrequencyNotice(String text, String label) {
        String expression = vagueFrequencyExpression(text);
        return expression + (label == null ? "장소" : label) + "의 횟수는 미설정으로 두었어요. 원하시면 주·월 횟수를 알려주세요.";
    }

    static String vagueFrequencyExpression(String text) {
        var phrases = Pattern.compile("자주|가끔|종종|주\\s*\\d+\\s*[~～-]\\s*\\d+\\s*회|주\\s*\\d+\\s*회\\s*이상")
                .matcher(text).results().map(m -> "‘" + m.group() + "’").distinct().toList();
        return phrases.isEmpty() ? "방문 횟수가 불분명해 " : String.join("·", phrases) + "처럼 방문 횟수가 불분명한 표현만으로는 횟수를 정할 수 없어 ";
    }

    private Issue toIssue(Clarification c, String text, String ambiguousTravel) {
        String source = c.sourceText() == null ? text : c.sourceText();
        if (source.length() > 200) source = source.substring(0, 200) + "…";
        String message = switch (c.reason()) {
            case AMOUNT_UNIT_REQUIRED -> "이 금액은 원과 만원 중 어느 단위인가요? '1만 원' 또는 '1억 원'처럼 알려주세요.";
            case INTEGER_MANWON_REQUIRED -> "금액은 만원 단위 정수로 입력해 주세요. 만원 미만 금액을 반올림하지는 않아요.";
            case NET_RENT_REQUIRED -> "관리비를 제외한 월세는 최대 얼마까지 생각하고 있나요?";
            case UNSUPPORTED_RENTAL_TYPE -> "지금은 월세 조건을 지원해요. 월세로 찾으실지 확인해 주세요.";
            case AMBIGUOUS_INPUT -> "‘" + source + "’ 부분을 확인할게요. "
                    + (ambiguousTravel != null && ambiguousTravel.equals(c.sourceText())
                    ? "어느 장소까지의 편도시간인가요? 장소 이름과 시간을 함께 알려주세요."
                    : "어느 금액이나 장소, 편도시간을 뜻하는지 구체적으로 알려주세요.");
        };
        if (c.field().equals("maxDeposit")) message = "보증금 " + message;
        if (c.field().equals("maxRent")) message = "월세 " + message;
        return new Issue(c.field(), c.reason() == Reason.UNSUPPORTED_RENTAL_TYPE
                ? Kind.UNSUPPORTED : Kind.NEEDS_CONFIRMATION, message);
    }
}
