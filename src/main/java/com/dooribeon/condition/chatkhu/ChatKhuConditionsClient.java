package com.dooribeon.condition.chatkhu;

import com.dooribeon.condition.ConditionsExtraction;
import com.dooribeon.condition.ConditionsExtractor;
import com.dooribeon.condition.ConditionsUpdate;
import com.dooribeon.condition.ConditionsDraft;
import com.dooribeon.global.ApiException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** 공통 조건의 최초 추출·추가 답변을 ChatKHU에 요청한다. */
@Component
public class ChatKhuConditionsClient implements ConditionsExtractor {
    private final RestClient client;
    private final ChatKhuProperties properties;
    private final ChatKhuConditionsCodec codec;

    public ChatKhuConditionsClient(@Qualifier("chatKhuRestClient") RestClient client,
                                   ChatKhuProperties properties, ChatKhuConditionsCodec codec) {
        this.client = client;
        this.properties = properties;
        this.codec = codec;
    }

    @Override
    public ConditionsExtraction extract(String text) {
        return codec.readResponse(call(codec.requestJson(text, model())));
    }

    @Override
    public ConditionsUpdate extractUpdate(String text, ConditionsDraft current, String lastQuestion) {
        return codec.readUpdateResponse(call(codec.updateRequestJson(text, current, lastQuestion, model())));
    }

    private String model() {
        if (properties.model() == null || properties.model().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "AI 모델 설정을 확인해 주세요.");
        }
        return properties.model();
    }

    private String call(String request) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()
                || properties.model() == null || properties.model().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                    "AI 연결 설정이 아직 없어요. 서버의 ChatKHU 키와 모델 설정을 확인해 주세요.");
        }
        String response;
        try {
            response = client.post().uri("/chat/completions/")
                    .headers(headers -> headers.setBearerAuth(properties.apiKey()))
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(request).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_BUSY", "AI 요청이 많아요. 잠시 뒤 다시 시도해 주세요.");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_ERROR", providerErrorMessage(e));
        } catch (ResourceAccessException e) {
            if (e.getMostSpecificCause() instanceof HttpTimeoutException
                    || e.getMostSpecificCause() instanceof SocketTimeoutException) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", "AI 답변이 늦어지고 있어요. 잠시 뒤 다시 시도해 주세요.");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_CONNECTION_FAILED",
                    "AI에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        }
        return response;
    }
    private String providerErrorMessage(RestClientResponseException error) {
        int status = error.getStatusCode().value();
        String detail;
        if (status == 401 || status == 403) {
            detail = "ChatKHU 키의 인증·모델 접근 권한을 확인해 주세요.";
        } else if (status == 400 || status == 422) {
            // 원문·모델명·요청 내용을 복사하지 않고 미리 정한 진단 문구만 반환한다.
            String body = error.getResponseBodyAsString();
            String limited = body.substring(0, Math.min(body.length(), 16000)).toLowerCase(java.util.Locale.ROOT);
            if (limited.contains("response_format") || limited.contains("json_schema") || limited.contains("schema")) {
                detail = "ChatKHU가 JSON 응답 형식 설정과 관련된 오류를 반환했어요. 모델의 형식 지원과 스키마를 확인해야 해요.";
            } else {
                detail = "ChatKHU가 요청을 거절했어요. 모델·요청 형식 설정을 확인해야 해요.";
            }
        } else if (status == 404) {
            detail = "ChatKHU 요청 주소 또는 모델 설정을 확인해 주세요.";
        } else if (status >= 500) {
            detail = "ChatKHU 제공자에서 오류가 발생했어요. 잠시 뒤 다시 시도해 주세요.";
        } else {
            detail = "ChatKHU가 요청을 처리하지 못했어요. 제공자 설정·이용 상태를 확인해 주세요.";
        }
        return detail + " (제공자 HTTP " + status + ")";
    }
}
