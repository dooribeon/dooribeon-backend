package com.dooribeon.recommend;

import com.dooribeon.condition.Conditions;
import com.dooribeon.global.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/** 공통 조건 그대로 동네 하나를 검사한다. 기존 순위 API와 구분한다. */
@RestController
@RequestMapping("/api/recommend")
public class RecommendationCheckController {
    private final RecommendationCheckService service;
    private final JsonMapper mapper;

    public RecommendationCheckController(RecommendationCheckService service, JsonMapper mapper) {
        this.service = service;
        // 이 입력에서만 50.5 → 50, "50" → 50 같은 자동 변환을 막는다.
        this.mapper = mapper.rebuild()
                .withCoercionConfig(LogicalType.Enum, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Integer, config -> config
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .build();
    }

    @PostMapping("/check")
    public RecommendationCheckResponse check(@RequestBody JsonNode body,
                                              @RequestParam String neighborhoodKey) {
        Conditions conditions;
        try {
            conditions = mapper.treeToValue(body, Conditions.class);
        } catch (JacksonException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JSON",
                    "공통 조건 JSON을 확인해 주세요. 금액은 정수 만원, 시간과 횟수는 정수로 보내 주세요.");
        }
        return service.check(conditions, neighborhoodKey);
    }
}
