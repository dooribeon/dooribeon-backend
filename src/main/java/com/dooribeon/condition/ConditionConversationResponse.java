package com.dooribeon.condition;

import java.util.List;
import java.util.UUID;

public record ConditionConversationResponse(
        UUID conversationId, Conditions conditions, boolean readyForRecommendation,
        List<ConditionCheckResult.Issue> issues, String nextQuestion, List<String> notices,
        ConditionConversationService.DeletionConfirmation deletionConfirmation
) {
    public static ConditionConversationResponse of(UUID id, ConditionCheckResult result,
                                                    ConditionConversationService.DeletionConfirmation deletion) {
        String question = result.nextQuestion();
        if (deletion != null) {
            String address = deletion.address() == null ? "위치 미선택" : deletion.address();
            question = deletion.displayName() + " (" + address + ")을 삭제하려면 '"
                    + deletion.confirmationText() + "'를 입력해 주세요. 취소하려면 '삭제 취소'를 입력해 주세요.";
        }
        return new ConditionConversationResponse(id, result.conditions(),
                deletion == null && result.readyForRecommendation(), result.issues(), question, result.notices(), deletion);
    }
}
