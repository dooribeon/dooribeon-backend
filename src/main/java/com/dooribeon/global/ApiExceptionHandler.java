package com.dooribeon.global;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record ErrorResponse(String code, String message) {}

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ErrorResponse(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_INPUT",
                "요청 항목의 필수값과 입력 범위를 확인해 주세요."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleJson(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_JSON",
                "요청 항목의 이름과 값의 형식을 확인해 주세요."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handlePath(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_CONVERSATION_ID",
                "서버가 반환한 conversationId를 그대로 보내 주세요."));
    }
}
