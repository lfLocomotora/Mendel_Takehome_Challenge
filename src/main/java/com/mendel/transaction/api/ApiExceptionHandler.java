package com.mendel.transaction.api;

import com.mendel.transaction.domain.TransactionException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class ApiExceptionHandler {
  @ExceptionHandler(TransactionException.class)
  public ResponseEntity<ApiErrorResponse> transactionError(TransactionException exception) {
    int status =
        switch (exception.code()) {
          case INVALID_REQUEST, INVALID_CURSOR -> 400;
          case TRANSACTION_NOT_FOUND -> 404;
          case PARENT_NOT_FOUND, CYCLE_DETECTED, SUM_OVERFLOW -> 409;
        };
    return ResponseEntity.status(status)
        .body(new ApiErrorResponse(exception.code().name(), exception.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> malformedJson() {
    return ResponseEntity.badRequest()
        .body(
            new ApiErrorResponse(
                "INVALID_REQUEST",
                "Request body must contain valid JSON with the expected field types."));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiErrorResponse> invalidPathId() {
    return ResponseEntity.badRequest()
        .body(
            new ApiErrorResponse(
                "INVALID_REQUEST",
                "Transaction ID must be an integer within the signed 64-bit range."));
  }
}
