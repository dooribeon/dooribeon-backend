package com.dooribeon.condition;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conditions")
public class ConditionController {
    private final ConditionsExtractionService service;

    public ConditionController(ConditionsExtractionService service) { this.service = service; }

    @PostMapping("/parse")
    public ConditionResultResponse parse(@Valid @RequestBody ConditionParseRequest request) {
        return ConditionResultResponse.of(service.parse(request.text()));
    }
}
