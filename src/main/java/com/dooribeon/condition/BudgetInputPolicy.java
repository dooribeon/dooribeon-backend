package com.dooribeon.condition;

import com.dooribeon.condition.ConditionsExtraction.Clarification;
import com.dooribeon.condition.ConditionsExtraction.Reason;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.*;
import java.util.regex.Pattern;

/** 금액의 대상은 대화 맥락으로, 단위 없는 숫자의 단위는 서비스 규칙으로 정한다. */
final class BudgetInputPolicy {
    private static final String NUMBER = "([+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?)";
    private static final Pattern LABELLED = Pattern.compile(
            "(보증금|월세|월쎄)\\s*(?:은|는|이|가|을|를)?\\s*" + NUMBER
            + "(?![0-9]|[.,][0-9])\\s*(만원|만\\s*원|원)?(?!\\s*[십백천만억조원])(?=$|[\\s,.!?]|이하|이내|이야|이요|요|으로|로|이고|까지)");
    private static final Pattern BARE = Pattern.compile("^\\s*" + NUMBER + "\\s*(?:이야|이요|요|입니다)?[.!]?\\s*$");

    static boolean isNumericReply(String text) { return BARE.matcher(text).matches(); }

    static Result read(String text, String question, ConditionsExtraction extraction) {
        Map<String, String[]> tokens = new LinkedHashMap<>();
        // 정정·대안의 최종 뜻은 문장 전체를 해석한 추출기가 결정한다.
        if (Pattern.compile("아니|말고|정정|취소|또는|~|∼").matcher(text).find()) {
            return new Result(extraction.maxDeposit(), extraction.maxRent(), Set.of(), List.of(), correctionNotices(text, extraction));
        }
        Set<Integer> budgetNumberStarts = new HashSet<>();
        var match = LABELLED.matcher(text);
        while (match.find()) {
            budgetNumberStarts.add(match.start(2));
            tokens.put(match.group(1).equals("보증금") ? "maxDeposit" : "maxRent",
                    new String[] {match.group(2), match.group(3)});
        }
        // 이름 붙은 금액 외에 숫자가 더 있으면 현재 지출과 희망 예산 등의 관계를 AI가 판단한다.
        // 서버가 문장 일부의 숫자로 최종 추출값을 덮어쓰지 않는다.
        var numbers = Pattern.compile(NUMBER).matcher(text);
        if (!tokens.isEmpty()) {
            while (numbers.find()) {
                if (!budgetNumberStarts.contains(numbers.start())) {
                    return new Result(extraction.maxDeposit(), extraction.maxRent(), Set.of(), List.of(),
                            correctionNotices(text, extraction));
                }
            }
        }
        var bare = BARE.matcher(text);
        String field = question.contains("보증금") && !question.contains("월세") ? "maxDeposit"
                : question.contains("월세") && !question.contains("보증금") ? "maxRent" : null;
        if (field != null && bare.matches()) tokens.put(field, new String[] {bare.group(1), null});
        Long deposit = extraction.maxDeposit(), rent = extraction.maxRent();
        var questions = new ArrayList<Clarification>();
        var notices = new ArrayList<String>();
        Set<String> handled = new HashSet<>();
        for (var entry : tokens.entrySet()) {
            String path = entry.getKey();
            // 관리비 포함·범위·대안 등의 의미 확인을 숫자 하나로 대신하지 않는다.
            boolean semanticQuestion = extraction.clarifications().stream().anyMatch(c ->
                    (c.field().equals(path) || c.field().equals("budget") || c.field().equals("conditions"))
                    && c.reason() != Reason.AMOUNT_UNIT_REQUIRED && c.reason() != Reason.INTEGER_MANWON_REQUIRED);
            if (semanticQuestion) continue;
            var token = entry.getValue();
            BigDecimal number = new BigDecimal(token[0].replace(",", ""));
            boolean inferred = token[1] == null;
            Long value = null;
            Reason reason = null;
            if (inferred && number.compareTo(BigDecimal.valueOf(10000)) == 0) {
                reason = Reason.AMOUNT_UNIT_REQUIRED;
            } else {
                boolean won = "원".equals(token[1]) || inferred && number.compareTo(BigDecimal.valueOf(10000)) > 0;
                BigDecimal manwon = won ? number.movePointLeft(4) : number;
                try {
                    value = manwon.longValueExact();
                    if (value < 0 || value > 9007199254740991L) {
                        value = null;
                        reason = Reason.AMBIGUOUS_INPUT;
                    }
                } catch (ArithmeticException e) {
                    reason = Reason.INTEGER_MANWON_REQUIRED;
                }
            }
            handled.add(path);
            if (path.equals("maxDeposit")) deposit = value;
            else rent = value;
            if (reason != null) questions.add(new Clarification(path, reason));
            else if (inferred) notices.add(notice(path, value,
                    number.compareTo(BigDecimal.valueOf(10000)) < 0 ? "만원" : "원"));
        }
        return new Result(deposit, rent, handled, questions, notices);
    }

    private static String notice(String field, long value, String unit) {
        return (field.equals("maxDeposit") ? "보증금은 " : "월세는 ")
                + NumberFormat.getIntegerInstance(Locale.KOREA).format(value)
                + "만 원 이하로 적용했어요. 단위 없는 금액은 " + unit
                + "으로 읽었어요. 다르게 뜻하셨다면 금액과 단위를 함께 알려주시면 수정할게요.";
    }

    /** 정정에서는 첫 숫자로 재계산하지 않고, 최종 추출값과 일치하는 단위 없는 숫자만 안내한다. */
    private static List<String> correctionNotices(String text, ConditionsExtraction extraction) {
        var notices = new ArrayList<String>();
        for (String field : List.of("maxDeposit", "maxRent")) {
            Long value = field.equals("maxDeposit") ? extraction.maxDeposit() : extraction.maxRent();
            if (value == null || extraction.clarifications().stream().anyMatch(c ->
                    c.field().equals(field) || c.field().equals("budget") || c.field().equals("conditions"))) continue;
            var matcher = Pattern.compile(NUMBER + "(?![0-9]|[.,][0-9])").matcher(text);
            while (matcher.find()) {
                String suffix = text.substring(matcher.end()).stripLeading();
                if (Pattern.compile("^(?:만|천|백|억|원|분|회|번|일|월|년|시간)").matcher(suffix).find()) continue;
                var number = new BigDecimal(matcher.group(1).replace(",", ""));
                if (number.signum() < 0 || number.compareTo(BigDecimal.valueOf(10000)) == 0) continue;
                boolean won = number.compareTo(BigDecimal.valueOf(10000)) > 0;
                var manwon = won ? number.movePointLeft(4) : number;
                if (manwon.compareTo(BigDecimal.valueOf(value)) == 0) {
                    notices.add(notice(field, value, won ? "원" : "만원"));
                    break;
                }
            }
        }
        return notices;
    }

    record Result(Long deposit, Long rent, Set<String> handled, List<Clarification> questions, List<String> notices) {}
}
