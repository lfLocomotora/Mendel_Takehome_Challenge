package com.mendel.transaction.concurrency;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

public final class TransactionAccessCoordinator {
  private final ReentrantReadWriteLock lock;

  public TransactionAccessCoordinator() {
    this(new ReentrantReadWriteLock());
  }

  TransactionAccessCoordinator(ReentrantReadWriteLock lock) {
    this.lock = lock;
  }

  public <T> T read(Supplier<T> operation) {
    lock.readLock().lock();
    try {
      return operation.get();
    } finally {
      lock.readLock().unlock();
    }
  }

  public <T> T write(Supplier<T> operation) {
    lock.writeLock().lock();
    try {
      return operation.get();
    } finally {
      lock.writeLock().unlock();
    }
  }
}
