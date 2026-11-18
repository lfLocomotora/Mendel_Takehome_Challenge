package com.mendel.transaction.storage;

import com.mendel.transaction.domain.Transaction;
import java.util.List;
import java.util.Optional;

/** Access must be coordinated by the service's shared read/write lock. */
public interface TransactionStore {
  Optional<Transaction> find(long id);

  void save(Transaction transaction);

  List<Long> idsByType(String type);

  List<Long> childIds(long parentId);

  List<Long> idsAfter(String type, Long after, int maximum);
}
