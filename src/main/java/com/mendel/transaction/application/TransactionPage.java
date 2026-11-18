package com.mendel.transaction.application;

import java.util.List;

public record TransactionPage(List<Long> ids, String nextCursor) {
  public TransactionPage {
    ids = List.copyOf(ids);
  }
}
