package com.mendel.transaction.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mendel.transaction.application.TransactionService;
import com.mendel.transaction.domain.ErrorCode;
import com.mendel.transaction.domain.Transaction;
import com.mendel.transaction.domain.TransactionException;
import com.mendel.transaction.storage.InMemoryTransactionStore;
import com.mendel.transaction.storage.TransactionStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class TransactionConcurrencyTest {
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final TransactionAccessCoordinator access = new TransactionAccessCoordinator(lock);
  private final GuardedStore store = new GuardedStore();
  private final TransactionService service = new TransactionService(store, access);

  @Test
  void allowsTwoReadersButExcludesWriters() throws Exception {
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(3)) {
      var first =
          threads.submit(() ->
              access.read(() -> {
                entered.countDown();
                await(release);
                return 1;
              }));
      var second =
          threads.submit(() ->
              access.read(() -> {
                entered.countDown();
                await(release);
                return 2;
              }));
      try {
        await(entered);
        var writerCouldEnter =
            threads.submit(() -> {
              boolean acquired = lock.writeLock().tryLock();
              if (acquired) {
                lock.writeLock().unlock();
              }
              return acquired;
            });
        assertFalse(writerCouldEnter.get(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      assertEquals(1, first.get(5, TimeUnit.SECONDS));
      assertEquals(2, second.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void excludesReadersAndOtherWritersWhileWriting() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var writer =
          threads.submit(() ->
              access.write(() -> {
                entered.countDown();
                await(release);
                return true;
              }));
      try {
        await(entered);
        var probe =
            threads.submit(() -> {
              boolean read = lock.readLock().tryLock();
              if (read) {
                lock.readLock().unlock();
              }
              boolean write = lock.writeLock().tryLock();
              if (write) {
                lock.writeLock().unlock();
              }
              return read || write;
            });
        assertFalse(probe.get(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      assertTrue(writer.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void holdsTheWriteLockThroughSavingAndTheReaderSeesTheCompletedMove() throws Exception {
    seedMove();
    var saving = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    store.beforeSave =
        () -> {
          saving.countDown();
          await(release);
        };
    try (var threads = Executors.newFixedThreadPool(2)) {
      var update = threads.submit(() -> service.save(1, 3.0, "shopping", 20L));
      try {
        await(saving);
        var reading = threads.submit(() -> service.sum(10));
        release.countDown();
        assertFalse(update.get(5, TimeUnit.SECONDS));
        assertEquals(0, reading.get(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
    }
    assertEquals(List.of(1L), service.idsByType("shopping"));
    assertTrue(service.idsByType("cars").isEmpty());
  }

  @Test
  void keepsTheReadLockAcrossChildLookupAndAmountLookup() throws Exception {
    seedMove();
    var childrenRead = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    store.afterChildren =
        id -> {
          if (id == 10) {
            childrenRead.countDown();
            await(release);
          }
        };
    try (var threads = Executors.newFixedThreadPool(2)) {
      var reading = threads.submit(() -> service.sum(10));
      try {
        await(childrenRead);
        var update = threads.submit(() -> service.save(1, 3.0, "shopping", 20L));
        release.countDown();
        assertEquals(2, reading.get(5, TimeUnit.SECONDS));
        assertFalse(update.get(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
    }
    store.afterChildren = id -> {};
    assertEquals(0, service.sum(10));
    assertEquals(3, service.sum(20));
  }

  @Test
  void serializesConcurrentParentChangesSoTheyCannotCreateACycle() throws Exception {
    service.save(1, 1.0, "root", null);
    service.save(2, 1.0, "root", null);
    var start = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var first =
          threads.submit(() -> {
            await(start);
            return moveOrConflict(1, 2);
          });
      var second =
          threads.submit(() -> {
            await(start);
            return moveOrConflict(2, 1);
          });
      start.countDown();
      assertEquals(1, first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS));
    }
    double firstSum = service.sum(1);
    double secondSum = service.sum(2);
    assertTrue((firstSum == 1 && secondSum == 2) || (firstSum == 2 && secondSum == 1));
  }

  @Test
  void createsOnlyOnceWhenTwoRequestsUseTheSameId() throws Exception {
    var start = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var first =
          threads.submit(() -> {
            await(start);
            return service.save(1, 2.0, "cars", null);
          });
      var second =
          threads.submit(() -> {
            await(start);
            return service.save(1, 3.0, "shopping", null);
          });
      start.countDown();
      assertTrue(first.get(5, TimeUnit.SECONDS) != second.get(5, TimeUnit.SECONDS));
    }
    double amount = service.sum(1);
    assertTrue(amount == 2 || amount == 3);
    assertEquals(1, service.idsByType("cars").size() + service.idsByType("shopping").size());
  }

  @Test
  void lastAppliedUpdateWinsAndValidationFailuresReleaseTheLock() throws Exception {
    service.save(1, 1.0, "cars", null);
    var firstApplied = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var second =
          threads.submit(() -> {
            await(firstApplied);
            return service.save(1, 3.0, "shopping", null);
          });
      var first =
          threads.submit(() -> {
            boolean created = service.save(1, 2.0, "cars", null);
            firstApplied.countDown();
            return created;
          });
      assertFalse(first.get(5, TimeUnit.SECONDS));
      assertFalse(second.get(5, TimeUnit.SECONDS));
      assertEquals(3, service.sum(1));
      assertThrows(TransactionException.class, () -> service.save(1, 9.0, "bad", 99L));
      assertEquals(3, threads.submit(() -> service.sum(1)).get(5, TimeUnit.SECONDS));
      assertThrows(TransactionException.class, () -> service.sum(99));
      assertFalse(
          threads.submit(() -> service.save(1, 4.0, "cars", null)).get(5, TimeUnit.SECONDS));
    }
  }

  private void seedMove() {
    service.save(10, 0.0, "root", null);
    service.save(20, 0.0, "root", null);
    service.save(1, 2.0, "cars", 10L);
  }

  private int moveOrConflict(long id, long parent) {
    try {
      service.save(id, 1.0, "root", parent);
      return 1;
    } catch (TransactionException exception) {
      assertEquals(ErrorCode.CYCLE_DETECTED, exception.code());
      return 0;
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Synchronization point was not reached");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private final class GuardedStore implements TransactionStore {
    private final TransactionStore delegate = new InMemoryTransactionStore();
    private Runnable beforeSave = () -> {};
    private java.util.function.LongConsumer afterChildren = id -> {};

    private void assertProtected() {
      assertTrue(
          lock.isWriteLockedByCurrentThread() || lock.getReadHoldCount() > 0,
          "Every storage access must share the operation lock");
    }

    public Optional<Transaction> find(long id) {
      assertProtected();
      return delegate.find(id);
    }

    public void save(Transaction transaction) {
      assertTrue(lock.isWriteLockedByCurrentThread());
      beforeSave.run();
      delegate.save(transaction);
    }

    public List<Long> idsByType(String type) {
      assertProtected();
      return delegate.idsByType(type);
    }

    public List<Long> childIds(long id) {
      assertProtected();
      var ids = delegate.childIds(id);
      afterChildren.accept(id);
      return ids;
    }

    public List<Long> idsAfter(String type, Long after, int maximum) {
      assertProtected();
      return delegate.idsAfter(type, after, maximum);
    }
  }
}
