package com.mendel.transaction.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mendel.transaction.domain.Transaction;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryTransactionStoreTest {
  private final TransactionStore store = new InMemoryTransactionStore();

  @Test
  void savesAndReplacesAcrossAllThreeMaps() {
    var original = new Transaction(1, 2, "cars", 10L);
    store.save(original);
    assertEquals(original, store.find(1).orElseThrow());
    assertEquals(List.of(1L), store.idsByType("cars"));
    assertEquals(List.of(1L), store.childIds(10));

    store.save(new Transaction(1, 3, "shopping", 20L));
    assertTrue(store.idsByType("cars").isEmpty());
    assertTrue(store.childIds(10).isEmpty());
    assertEquals(List.of(1L), store.idsByType("shopping"));
    assertEquals(List.of(1L), store.childIds(20));
    assertEquals(3, store.find(1).orElseThrow().amount());
    assertEquals(2, original.amount());
    assertNotSame(original, store.find(1).orElseThrow());

    store.save(new Transaction(1, 4, "shopping", null));
    assertTrue(store.childIds(20).isEmpty());
    assertEquals(List.of(1L), store.idsByType("shopping"));
  }

  @Test
  void repeatedSaveDoesNotDuplicateIdsAndCopiesDoNotTrackChanges() {
    var transaction = new Transaction(1, 2, "cars", 10L);
    store.save(transaction);
    var types = store.idsByType("cars");
    var children = store.childIds(10);
    store.save(transaction);
    store.save(new Transaction(2, 5, "cars", 10L));
    assertEquals(List.of(1L), types);
    assertEquals(List.of(1L), children);
    var mutableCopy = new java.util.ArrayList<>(store.idsByType("cars"));
    mutableCopy.clear();
    assertEquals(2, store.idsByType("cars").size());
  }

  @Test
  void pagesInNumericOrderAcrossTheEntireLongRange() {
    for (long id : List.of(Long.MAX_VALUE, 0L, -1L, Long.MIN_VALUE)) {
      store.save(new Transaction(id, 0, "cars", null));
    }
    assertEquals(List.of(Long.MIN_VALUE, -1L), store.idsAfter("cars", null, 2));
    assertEquals(List.of(0L, Long.MAX_VALUE), store.idsAfter("cars", -1L, 3));
    assertTrue(store.idsAfter("cars", Long.MAX_VALUE, 2).isEmpty());
    assertEquals(List.of(Long.MAX_VALUE), store.idsAfter("cars", 50L, 2));
    assertTrue(store.idsAfter("unknown", null, 2).isEmpty());
    assertFalse(store.find(50).isPresent());
  }
}
