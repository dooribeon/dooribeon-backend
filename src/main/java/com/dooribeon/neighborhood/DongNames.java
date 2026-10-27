package com.dooribeon.neighborhood;

/** 법정동 이름 정리 : "영등포동3가" → "영등포동" */
public final class DongNames {

    private DongNames() {}

    public static String normalize(String dong) {
        String name = dong.replaceAll("\\d+가$", "");    // 끝의 "숫자+가" 제거
        if (!name.endsWith("동") && !name.endsWith("로")) {
            name = name + "동";                          // "명륜1가" → "명륜" → "명륜동"
        }
        return name;
    }
}