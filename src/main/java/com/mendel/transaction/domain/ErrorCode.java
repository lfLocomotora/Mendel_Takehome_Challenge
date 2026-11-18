package com.mendel.transaction.domain;

public enum ErrorCode {
  INVALID_REQUEST,
  PARENT_NOT_FOUND,
  CYCLE_DETECTED,
  TRANSACTION_NOT_FOUND,
  SUM_OVERFLOW,
  INVALID_CURSOR
}
