package com.ontime.global.error;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
    ErrorCode code = e.getErrorCode();
    return ResponseEntity.status(code.getStatus())
        .body(new ErrorResponse(code.name(), code.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
    String message = e.getBindingResult().getFieldErrors().stream()
        .findFirst()
        .map(err -> err.getField() + ": " + err.getDefaultMessage())
        .orElse(ErrorCode.VALIDATION_FAILED.getMessage());
    return ResponseEntity.badRequest()
        .body(new ErrorResponse(ErrorCode.VALIDATION_FAILED.name(), message));
  }

  /** 요청 파라미터 검증 실패(@RequestParam 의 @Min 등), 타입 불일치, 읽을 수 없는 JSON 본문(잘못된 enum 값 등). */
  @ExceptionHandler({
    HandlerMethodValidationException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
    ErrorCode code = ErrorCode.VALIDATION_FAILED;
    return ResponseEntity.badRequest().body(new ErrorResponse(code.name(), code.getMessage()));
  }
}
