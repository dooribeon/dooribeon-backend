package com.dooribeon.recommend;

import lombok.RequiredArgsConstructor;
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

    /** 조건 JSON → 추천 동네 상위 5곳 */
    @PostMapping
    public List<RecommendResult> recommend(@RequestBody RecommendCondition condition) {
        return recommendService.recommend(condition);
    }
}