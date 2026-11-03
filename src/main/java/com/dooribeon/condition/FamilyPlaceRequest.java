package com.dooribeon.condition;

import java.util.regex.Pattern;

/** 미지원 목록에 잘못 들어간 순수 본가 소개·방문 문장만 복구한다. */
final class FamilyPlaceRequest {
    private static final Pattern VAGUE_FREQUENCY = Pattern.compile("자주|가끔|종종");
    private static final Pattern OTHER_DEMAND = Pattern.compile("소음|조용|치안|안전|자가용|주차|전세|관리비");
    private static final String HOME = "(?<home>본가|부모님\\s*집)";
    private static final Pattern LOCATION = Pattern.compile("^" + HOME
            + "(?:은|는|가|이)?\\s*(?<region>[가-힣]{2,}?(?:\\s+[가-힣]+(?:시|군|구|동|읍|면|리))*)"
            + "(?:이야|야|이에요|예요|이다|입니다|에\\s*있어(?:요)?)?[.!?\\s]*$");
    private static final Pattern VISIT = Pattern.compile("^(?:(?<region>[가-힣]+)\\s+)?"
            + HOME + "(?:은|는|에|을|를)?\\s*(?:(?:자주|가끔|종종|계속)\\s*)?"
            + "(?:가고\\s*싶(?:어(?:요)?|습니다)|가(?:요)?|방문해(?:요)?)[.!?\\s]*$");

    private FamilyPlaceRequest() {}

    static ConditionsExtraction.ExtractedPlace recover(String fragment) {
        if (OTHER_DEMAND.matcher(fragment).find()) return null;
        var match = LOCATION.matcher(fragment.strip());
        if (!match.matches()) {
            match = VISIT.matcher(fragment.strip());
            if (!match.matches()) return null;
        }
        String region = match.group("region");
        String label = match.group("home");
        return new ConditionsExtraction.ExtractedPlace(label, PlaceCondition.TargetType.FIXED_PLACE,
                null, null, null, false, null, null, null,
                VAGUE_FREQUENCY.matcher(fragment).find(), region);
    }

    static boolean isVisit(String fragment) {
        return !OTHER_DEMAND.matcher(fragment).find() && VISIT.matcher(fragment.strip()).matches();
    }

    static boolean isHome(String label) {
        return label != null && Pattern.compile("(?:본가|부모님\\s*집)$").matcher(label.strip()).find();
    }
}
