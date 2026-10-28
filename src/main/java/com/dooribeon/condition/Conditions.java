package com.dooribeon.condition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 서버가 모은 현재 조건. 금액은 원이 아닌 정수 만원이며 null은 미입력이다. */
public record Conditions(Long maxDeposit, Long maxRent, List<PlaceCondition> places) {
    public Conditions {
        // 나중에 원래 목록을 수정해도 저장된 조건이 바뀌지 않게 복사한다.
        // null 목록·항목은 검사기가 잘못된 입력으로 구분한다.
        if (places != null) places = Collections.unmodifiableList(new ArrayList<>(places));
    }
}
