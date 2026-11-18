package com.mendel.transaction.application;

import com.mendel.transaction.concurrency.TransactionAccessCoordinator;
import com.mendel.transaction.domain.ErrorCode;
import com.mendel.transaction.domain.Transaction;
import com.mendel.transaction.domain.TransactionException;
import com.mendel.transaction.storage.TransactionStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class TransactionService {
  private final TransactionStore store;
  private final TransactionAccessCoordinator access;

  public TransactionService(TransactionStore store, TransactionAccessCoordinator access) {
    this.store = store;
    this.access = access;
  }

  public boolean save(long id, Double amount, String type, Long parentId) {
    if (amount == null || !Double.isFinite(amount)) {
      throw new TransactionException(ErrorCode.INVALID_REQUEST, "Amount must be a finite number.");
    }
    String normalizedType = normalizeType(type);
    var transaction = new Transaction(id, amount, normalizedType, parentId);
    return access.write(() -> {
      validateParent(id, parentId);
      boolean created = store.find(id).isEmpty();
      store.save(transaction);
      return created;
    });
  }

  public double sum(long id) {
    return access.read(() -> {
      var pending = new ArrayDeque<Long>();
      pending.push(id);
      double total = 0;
      while (!pending.isEmpty()) {
        long currentId = pending.pop();
        Transaction transaction = store.find(currentId).orElseThrow(() ->
            new TransactionException(ErrorCode.TRANSACTION_NOT_FOUND,
                "Transaction with ID " + currentId + " does not exist."));
        total += transaction.amount();
        if (!Double.isFinite(total)) {
          throw new TransactionException(ErrorCode.SUM_OVERFLOW,
              "The transaction sum exceeds the finite double range.");
        }
        var children = new ArrayList<>(store.childIds(currentId));
        children.sort(Comparator.reverseOrder());
        children.forEach(pending::push);
      }
      return total;
    });
  }

  public List<Long> idsByType(String type) {
    String normalizedType = normalizeType(type);
    return access.read(() -> store.idsByType(normalizedType));
  }

  static String normalizeType(String type) {
    if (type == null || type.isBlank()) {
      throw new TransactionException(ErrorCode.INVALID_REQUEST, "Type must be a non-blank string.");
    }
    return type.strip().toLowerCase(Locale.ROOT);
  }

  private void validateParent(long id, Long parentId) {
    Long ancestor = parentId;
    while (ancestor != null) {
      if (ancestor == id) {
        throw new TransactionException(ErrorCode.CYCLE_DETECTED,
            "Parent relationship would create a cycle for transaction " + id + ".");
      }
      long ancestorId = ancestor;
      ancestor = store.find(ancestorId).orElseThrow(() ->
          new TransactionException(ErrorCode.PARENT_NOT_FOUND,
              "Parent transaction with ID " + ancestorId + " does not exist.")).parentId();
    }
  }
}
