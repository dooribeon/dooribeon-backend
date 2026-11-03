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
    private final CandidateFilterService candidateFilterService;
    private final ConditionsRules conditionsRules;

    /** 공통 조건 JSON → 추천 동네 상위 5곳 */
    @PostMapping
    public List<RecommendResult> recommend(@RequestBody Conditions conditions) {
        return recommendService.recommend(validate(conditions));
    }

    /** 공통 조건 JSON → 예산·거리로 걸러지는 후보 동네 (경로 API 호출 없음, 빠름) */
    @PostMapping("/candidates")
    public CandidatesResponse candidates(@RequestBody Conditions conditions) {
        return CandidatesResponse.of(candidateFilterService.search(validate(conditions)));
    }

    /** 입력 검사. 준비 안 된 조건이면 400과 다음 질문 반환 */
    private Conditions validate(Conditions conditions) {
        ConditionCheckResult checked = conditionsRules.check(conditions);
        if (!checked.readyForRecommendation()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INPUT_REQUIRED", checked.nextQuestion());
        }
        return checked.conditions();
    }
}