package com.dooribeon.recommend;

import com.dooribeon.condition.PlaceCondition.Importance;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 중요도별 점수 가중치와 편도시간 여유 */
@Getter
@RequiredArgsConstructor
public enum ImportancePolicy {
    REQUIRED(3.0, 0),   // 필수: 편도시간 이내
    HIGH(2.5, 10),      // 높음: +10분까지 (팀원과 확정 전 임시값)
    MEDIUM(2.0, 20),    // 중간: +20분까지
    LOW(1.0, 30);       // 낮음: +30분까지

    private final double weight;
    private final int extraMinutes;

    /** 중요도 미입력이면 중간으로 본다 */
    public static ImportancePolicy of(Importance importance) {
        return importance == null ? MEDIUM : valueOf(importance.name());
    }

    /** 편도시간 조건 통과 여부. 시간을 안 정했으면 항상 통과 */
    public boolean allows(int minutes, Integer maxMinutes) {
        return maxMinutes == null || minutes <= maxMinutes + extraMinutes;
    }
}