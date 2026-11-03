package com.dooribeon.condition;

import java.util.regex.Pattern;

/** 여러 거점 뒤의 독립된 '거기까지 N분'은 임의로 한 곳이나 전체에 배분하지 않는다. */
final class AmbiguousTravelTarget {
    private static final Pattern TARGET = Pattern.compile("(?:^|[.!?。]\\s*)(?<phrase>(?:거기|그곳)(?:까지|는)?\\s*\\d+\\s*분[^.!?。]*)");
    static String phrase(String text, int placeCount) {
        if (placeCount < 2 || Pattern.compile("모두|둘\\s*다|각각|모든\\s*장소").matcher(text).find()) return null;
        var match = TARGET.matcher(text);
        if (!match.find() || Pattern.compile("\\d+\\s*분").matcher(text.substring(0, match.start())).find()) return null;
        return match.group("phrase").strip();
    }
}
