package com.dooribeon.condition;

/** 최초 추출과 현재 조건·질문을 참고한 변경분 추출. */
public interface ConditionsExtractor {
    ConditionsExtraction extract(String text);
    ConditionsUpdate extractUpdate(String text, ConditionsDraft current, String lastQuestion);
}
