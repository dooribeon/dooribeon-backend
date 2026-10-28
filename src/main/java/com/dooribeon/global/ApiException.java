package com.dooribeon.global;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ApiException invalidAiResponse() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE",
                "AI 답변이 약속한 JSON 형식이나 예산 규칙과 달라요. 다시 시도해 주세요.");
    }
}
