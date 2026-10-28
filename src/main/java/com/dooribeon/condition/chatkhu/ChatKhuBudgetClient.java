package com.dooribeon.condition.chatkhu;

import com.dooribeon.global.ApiException;
import com.dooribeon.condition.Budget;
import com.dooribeon.condition.BudgetExtractor;
import com.dooribeon.condition.BudgetExtraction;
import com.dooribeon.condition.BudgetUpdate;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ChatKhuBudgetClient implements BudgetExtractor {
    private final RestClient client;
    private final ChatKhuProperties properties;
    private final ChatKhuBudgetCodec codec;

    public ChatKhuBudgetClient(@Qualifier("chatKhuRestClient") RestClient client,
                              ChatKhuProperties properties, ChatKhuBudgetCodec codec) {
        this.client = client;
        this.properties = properties;
        this.codec = codec;
    }

    @Override
    public BudgetExtraction extract(String text) {
        requireConfigured();
        return codec.readResponse(callProvider(codec.requestJson(text, properties.model())));
    }

    @Override
    public BudgetUpdate extractUpdate(String text, Budget currentBudget, String lastQuestion) {
        return extractUpdate(text, currentBudget, lastQuestion, "");
    }

    @Override
    public BudgetUpdate extractUpdate(String text, Budget currentBudget, String lastQuestion, String pendingUserText) {
        requireConfigured();
        return codec.readUpdateResponse(callProvider(codec.updateRequestJson(text, properties.model(), currentBudget, lastQuestion, pendingUserText)));
    }

    private void requireConfigured() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()
                || properties.model() == null || properties.model().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                    "AI 연결 설정이 아직 없어요. 서버의 ChatKHU 키와 모델 설정을 확인해 주세요.");
        }
    }

    private String callProvider(String requestJson) {
        String body;
        try {
            body = client.post().uri("/chat/completions/")
                    .headers(headers -> headers.setBearerAuth(properties.apiKey()))
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve().body(String.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_BUSY",
                        "AI 요청이 많아요. 잠시 뒤 다시 시도해 주세요.");
            }
            // 제공자 응답 본문은 키·입력을 포함할 수 있으므로 사용자에게 그대로 보내지 않는다.
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_ERROR",
                    "AI 제공자가 요청을 처리하지 못했어요. 서버의 키·모델 설정을 확인해 주세요.");
        } catch (ResourceAccessException exception) {
            Throwable cause = exception.getMostSpecificCause();
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT",
                        "AI 답변이 늦어지고 있어요. 잠시 뒤 다시 시도해 주세요.");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_CONNECTION_FAILED",
                    "AI에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        }
        return body;
    }
}
