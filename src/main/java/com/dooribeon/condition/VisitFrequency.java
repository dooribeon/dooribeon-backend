package com.dooribeon.condition;

/** 정확히 입력된 방문 주기·횟수. 미입력·불명확한 표현은 이 객체 대신 null로 둔다. */
public record VisitFrequency(Period period, Integer count) {
    public enum Period { WEEK, MONTH }
}
