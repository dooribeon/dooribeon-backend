package com.dooribeon.condition;

import java.util.*;
import java.util.regex.Pattern;

/** 지역과 개인 거점을 명시한 짧은 방문 문장만 처리한다. 다른 요구가 섞이면 AI에 맡긴다. */
final class PersonalVisitRequest {
    private static final Pattern OTHER_DEMAND = Pattern.compile("자가용|자동차|차로|주차|소음|조용|치안|안전|전세|관리비");
    private static final String PERSON = "(?<person>남자친구|여자친구|친구|연인|애인|본가|부모님\\s*집)";
    private static final String REGION = "(?<region>[가-힣A-Za-z0-9]+)(?:\\s*근처)?(?:에서|에)";
    private static final String END = "\\s*(?<vague>자주|가끔|종종)?\\s*(?:만나(?:요)?|만나고\\s*싶어(?:요)?|가(?:요)?|가고\\s*싶어(?:요)?|방문해(?:요)?)[.!?\\s]*$";
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("^" + PERSON + "\\s*(?:을|를|에)?\\s*" + REGION + END),
            Pattern.compile("^" + REGION + "\\s*" + PERSON + "\\s*(?:을|를|에)?" + END));

    record Visit(String person, String region, boolean vague) {
        String query() { return region.endsWith("역") ? region : null; }
        boolean matches(String label) { return label != null && Pattern.compile("(?:^|\\s)" + Pattern.quote(person) + "(?:$|\\s)").matcher(label).find(); }
    }

    static Visit parse(String text) {
        if (text == null || OTHER_DEMAND.matcher(text).find()) return null;
        for (var pattern : PATTERNS) {
            var match = pattern.matcher(text.strip());
            if (match.matches()) return new Visit(match.group("person"), match.group("region"), match.group("vague") != null);
        }
        return null;
    }

    static ConditionsUpdate update(String text, ConditionsDraft current) {
        var visit = parse(text);
        if (visit == null) return null;
        var matches = current.conditions().places().stream().filter(p -> visit.matches(p.label())).toList();
        if (matches.size() > 1) return null;
        var added = new ArrayList<ConditionsExtraction.ExtractedPlace>();
        var patches = new ArrayList<ConditionsUpdate.PlaceUpdate>();
        if (matches.isEmpty()) {
            added.add(new ConditionsExtraction.ExtractedPlace(visit.person(),
                    PlaceCondition.TargetType.FIXED_PLACE, visit.query(), null, null, false, null, null, null, visit.vague(), visit.query() == null ? visit.region() : null));
        } else {
            var old = matches.getFirst();
            var fields = new HashSet<String>();
            if (old.targetType() != PlaceCondition.TargetType.FIXED_PLACE) fields.add("targetType");
            if (visit.query() == null && !visit.region().equals(old.areaHint())) {
                fields.add("areaHint");
                fields.add("query");
            }
            if (visit.query() != null && old.areaHint() != null) fields.add("areaHint");
            if (visit.query() != null && !visit.query().equals(old.query())) fields.add("query");
            boolean unclear = visit.vague() && old.frequency() == null;
            if (unclear) fields.add("frequency");
            if (!fields.isEmpty()) patches.add(new ConditionsUpdate.PlaceUpdate(old.id(), ConditionsUpdate.Action.UPDATE, fields,
                    new ConditionsExtraction.ExtractedPlace(null, fields.contains("targetType") ? PlaceCondition.TargetType.FIXED_PLACE : null,
                            fields.contains("query") ? visit.query() : null, null, null, false, null, null, null, unclear,
                            fields.contains("areaHint") && visit.query() == null ? visit.region() : null)));
        }
        var resolved = new HashSet<String>();
        for (var pending : current.pendingInputs()) {
            var original = parse(pending.sourceText());
            if (pending.reason() == ConditionsExtraction.Reason.AMBIGUOUS_INPUT && original != null
                    && original.person().equals(visit.person()) && original.region().equals(visit.region())) resolved.add(pending.id());
        }
        return new ConditionsUpdate(new ConditionsExtraction(null, null, added, List.of(), List.of()), Set.of(), patches, resolved);
    }
}
