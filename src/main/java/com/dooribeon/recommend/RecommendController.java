package com.dooribeon.recommend;

import com.dooribeon.condition.ConditionCheckResult;
import com.dooribeon.condition.Conditions;
import com.dooribeon.condition.ConditionsRules;
import com.dooribeon.global.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;
    private final ConditionsRules conditionsRules;

    /** 공통 조건 JSON → 추천 동네 상위 5곳 */
    @PostMapping
    public List<RecommendResult> recommend(@RequestBody Conditions conditions) {
        ConditionCheckResult checked = conditionsRules.check(conditions);
        if (!checked.readyForRecommendation()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INPUT_REQUIRED", checked.nextQuestion());
        }
        return recommendService.recommend(checked.conditions());
    }
}