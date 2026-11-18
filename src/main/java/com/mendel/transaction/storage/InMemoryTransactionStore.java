package com.mendel.transaction.storage;

import com.mendel.transaction.domain.Transaction;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public class InMemoryTransactionStore implements TransactionStore {
  private final Map<Long, Transaction> transactionsById = new HashMap<>();
  private final Map<String, NavigableSet<Long>> idsByType = new HashMap<>();
  private final Map<Long, Set<Long>> childIdsByParent = new HashMap<>();

  @Override
  public Optional<Transaction> find(long id) {
    return Optional.ofNullable(transactionsById.get(id));
  }

  @Override
  public void save(Transaction transaction) {
    Transaction previous = transactionsById.put(transaction.id(), transaction);
    if (previous != null) {
      removeId(idsByType, previous.type(), previous.id());
      if (previous.parentId() != null) {
        removeId(childIdsByParent, previous.parentId(), previous.id());
      }
    }
    idsByType.computeIfAbsent(transaction.type(), ignored -> new TreeSet<>()).add(transaction.id());
    if (transaction.parentId() != null) {
      childIdsByParent.computeIfAbsent(transaction.parentId(), ignored -> new HashSet<>())
          .add(transaction.id());
    }
  }

  @Override
  public List<Long> idsByType(String type) {
    var ids = idsByType.get(type);
    return ids == null ? List.of() : List.copyOf(ids);
  }

  @Override
  public List<Long> childIds(long parentId) {
    var ids = childIdsByParent.get(parentId);
    return ids == null ? List.of() : List.copyOf(ids);
  }

  @Override
  public List<Long> idsAfter(String type, Long after, int maximum) {
    var ids = idsByType.get(type);
    if (ids == null) {
      return List.of();
    }
    var iterator = (after == null ? ids : ids.tailSet(after, false)).iterator();
    var page = new ArrayList<Long>();
    while (page.size() < maximum && iterator.hasNext()) {
      page.add(iterator.next());
    }
    return List.copyOf(page);
  }

  private static <K> void removeId(Map<K, ? extends Set<Long>> map, K key, long id) {
    var ids = map.get(key);
    if (ids != null) {
      ids.remove(id);
      if (ids.isEmpty()) {
        map.remove(key);
      }
    }
  }
}
