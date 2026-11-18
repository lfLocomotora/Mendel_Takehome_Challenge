package com.mendel.transaction.domain;

public final class TransactionException extends RuntimeException {
  private final ErrorCode code;

  public TransactionException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}
