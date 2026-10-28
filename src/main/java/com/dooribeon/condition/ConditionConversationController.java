package com.dooribeon.condition;

import jakarta.validation.Valid;
import com.dooribeon.condition.place.PlaceSearchRequest;
import com.dooribeon.condition.place.PlaceSearchResponse;
import com.dooribeon.condition.place.PlaceSelectionRequest;
import org.springframework.web.bind.annotation.PutMapping;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conditions/conversations")
public class ConditionConversationController {
    private final ConditionConversationService service;

    public ConditionConversationController(ConditionConversationService service) { this.service = service; }

    @PostMapping
    public ConditionConversationResponse start(@Valid @RequestBody ConditionParseRequest request) {
        return service.start(request.text());
    }

    @PostMapping("/{conversationId}/messages")
    public ConditionConversationResponse reply(@PathVariable UUID conversationId,
                                               @Valid @RequestBody ConditionParseRequest request) {
        return service.reply(conversationId, request.text());
    }

    @PostMapping("/{conversationId}/places/{placeId}/search")
    public PlaceSearchResponse search(@PathVariable UUID conversationId, @PathVariable String placeId,
                                      @Valid @RequestBody PlaceSearchRequest request) {
        return service.searchPlace(conversationId, placeId, request);
    }

    @PutMapping("/{conversationId}/places/{placeId}/location")
    public ConditionConversationResponse select(@PathVariable UUID conversationId, @PathVariable String placeId,
                                                @Valid @RequestBody PlaceSelectionRequest request) {
        return service.selectPlace(conversationId, placeId, request);
    }
}
