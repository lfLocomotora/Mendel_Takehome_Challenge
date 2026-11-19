package com.mendel.transaction.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mendel.transaction.concurrency.TransactionAccessCoordinator;
import com.mendel.transaction.domain.ErrorCode;
import com.mendel.transaction.domain.Transaction;
import com.mendel.transaction.domain.TransactionException;
import com.mendel.transaction.storage.InMemoryTransactionStore;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionServiceTest {
  private final InMemoryTransactionStore store = new InMemoryTransactionStore();
  private final TransactionService service =
      new TransactionService(store, new TransactionAccessCoordinator());

  @Test
  void createsReplacesAndNormalizesWithoutDuplicating() {
    assertTrue(service.save(0, 2.0, " Cars ", null));
    assertFalse(service.save(0, 3.0, "SHOPPING", null));
    assertFalse(service.save(0, 3.0, "SHOPPING", null));
    assertEquals(3, service.sum(0));
    assertEquals(List.of(0L), service.idsByType(" Shopping "));
    assertTrue(service.idsByType("cars").isEmpty());
  }

  @Test
  void matchesAssignmentAndSumsOnlyTheRequestedSubtree() {
    service.save(10, 5000.0, "cars", null);
    service.save(11, 10000.0, "shopping", 10L);
    service.save(12, 5000.0, "shopping", 11L);
    assertEquals(20000, service.sum(10));
    assertEquals(15000, service.sum(11));
    service.save(13, -100.0, "fees", 10L);
    service.save(20, 99999.0, "other", null);
    assertEquals(19900, service.sum(10));
    assertEquals(5000, service.sum(12));
  }

  @Test
  void movesAnEntireBranchAndSupportsDetachingItsParent() {
    service.save(10, 0.0, "root", null);
    service.save(20, 0.0, "root", null);
    service.save(1, 2.0, "cars", 10L);
    service.save(2, 5.0, "fees", 1L);
    service.save(1, 3.0, "shopping", 20L);
    assertEquals(0, service.sum(10));
    assertEquals(8, service.sum(20));
    service.save(1, 3.0, "shopping", null);
    assertEquals(0, service.sum(20));
    assertEquals(8, service.sum(1));
  }

  @Test
  void rejectedUpdatePreservesEveryFieldAndAssociation() {
    service.save(10, 1.0, "root", null);
    service.save(11, 2.0, "cars", 10L);
    service.save(12, 3.0, "fees", 11L);
    assertError(ErrorCode.PARENT_NOT_FOUND, () -> service.save(11, 90.0, "changed", 99L));
    assertError(ErrorCode.CYCLE_DETECTED, () -> service.save(10, 90.0, "changed", 12L));
    assertEquals(new Transaction(11, 2, "cars", 10L), store.find(11).orElseThrow());
    assertEquals(List.of(11L), service.idsByType("cars"));
    assertTrue(service.idsByType("changed").isEmpty());
    assertEquals(6, service.sum(10));
    assertError(ErrorCode.PARENT_NOT_FOUND, () -> service.save(99, 1.0, "new", 100L));
    assertTrue(store.find(99).isEmpty());
  }

  @Test
  void rejectsSelfParentForNewAndExistingIds() {
    assertError(ErrorCode.CYCLE_DETECTED, () -> service.save(1, 2.0, "cars", 1L));
    service.save(1, 2.0, "cars", null);
    assertError(ErrorCode.CYCLE_DETECTED, () -> service.save(1, 3.0, "cars", 1L));
    assertEquals(2, service.sum(1));
  }

  @ParameterizedTest
  @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE})
  void acceptsTheEntireLongRange(long id) {
    assertTrue(service.save(id, -2.0, "cars", null));
    assertEquals(-2, service.sum(id));
  }

  @ParameterizedTest
  @ValueSource(doubles = {-Double.MAX_VALUE, -2, 0, 2, Double.MAX_VALUE})
  void acceptsEveryFiniteSign(double amount) {
    service.save(1, amount, "cars", null);
    assertEquals(amount, service.sum(1));
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
  void rejectsNonFiniteAmounts(double amount) {
    assertError(ErrorCode.INVALID_REQUEST, () -> service.save(1, amount, "cars", null));
    assertTrue(store.find(1).isEmpty());
  }

  @Test
  void rejectsMissingAmount() {
    assertError(ErrorCode.INVALID_REQUEST, () -> service.save(1, null, "cars", null));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n", "\u2003"})
  void rejectsBlankTypes(String type) {
    assertError(ErrorCode.INVALID_REQUEST, () -> service.save(1, 2.0, type, null));
    assertError(ErrorCode.INVALID_REQUEST, () -> service.idsByType(type));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {".", "..", " . ", "a\u0000b", "\uD800", "\uDC00", "a\uD800b", "\uDC00\uD800"})
  void rejectsTypesThatCannotRoundTripWithoutChangingStoredState(String type) {
    service.save(1, 2.0, "cars", null);
    assertError(ErrorCode.INVALID_REQUEST, () -> service.save(1, 99.0, type, null));
    assertError(ErrorCode.INVALID_REQUEST, () -> service.save(2, 99.0, type, null));
    assertError(ErrorCode.INVALID_REQUEST, () -> service.idsByType(type));
    assertError(ErrorCode.INVALID_REQUEST, () -> service.pageByType(type, 1, null));
    assertEquals(List.of(1L), service.idsByType("cars"));
    assertEquals(2, service.sum(1));
    assertTrue(store.find(2).isEmpty());
  }

  @Test
  void rejectsTypesExceedingTheNormalizedUtf8ByteLimit() {
    service.save(1, 2.0, "cars", null);
    for (String type : List.of("a".repeat(1025), "é".repeat(513), "\u0130".repeat(342))) {
      var error = assertError(ErrorCode.INVALID_REQUEST, () -> service.save(1, 99.0, type, null));
      assertEquals(
          "Type must not exceed 1024 UTF-8 bytes after normalization.", error.getMessage());
      assertError(ErrorCode.INVALID_REQUEST, () -> service.idsByType(type));
      assertError(ErrorCode.INVALID_REQUEST, () -> service.pageByType(type, 1, null));
      assertEquals(2, service.sum(1));
      assertEquals(List.of(1L), service.idsByType("cars"));
    }
  }

  @Test
  void acceptsWellFormedUnicodeAtTheNormalizedUtf8Limit() {
    long id = 0;
    for (String type :
        List.of("a".repeat(1024), "é".repeat(512), "😀".repeat(256), "\u0130".repeat(341))) {
      long firstId = id++;
      long secondId = id++;
      assertTrue(service.save(firstId, 1.0, " " + type + " ", null));
      assertTrue(service.save(secondId, 1.0, type, null));
      var page = service.pageByType(type, 1, null);
      assertEquals(List.of(firstId), page.ids());
      assertEquals(List.of(secondId), service.pageByType(type, 1, page.nextCursor()).ids());
    }
  }

  @Test
  void normalizesIndependentlyOfTheMachineLocale() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      service.save(1, 0.0, " ITEMS ", null);
      assertEquals(List.of(1L), service.idsByType("items"));
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  void distinguishesMissingTransactionFromZeroSum() {
    assertError(ErrorCode.TRANSACTION_NOT_FOUND, () -> service.sum(1));
    service.save(1, 0.0, "cars", null);
    assertEquals(0, service.sum(1));
  }

  @Test
  void preservesDoubleArithmeticAndDeterministicDepthFirstOrder() {
    service.save(0, 0.1, "root", null);
    service.save(1, 0.2, "cars", 0L);
    assertEquals(0.30000000000000004, service.sum(0));
    service.save(0, 0.0, "root", null);
    service.save(3, 1.0, "cars", 0L);
    service.save(2, -1e16, "cars", 0L);
    service.save(1, 1e16, "cars", 0L);
    assertEquals(1, service.sum(0));
    service.save(1, 1e16, "cars", 0L);
    assertEquals(1, service.sum(0));
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.MAX_VALUE, -Double.MAX_VALUE})
  void rejectsIntermediateOverflowEvenIfLaterAmountsWouldCancelIt(double amount) {
    service.save(0, amount, "root", null);
    service.save(1, amount, "cars", 0L);
    service.save(2, -amount, "cars", 0L);
    var error = assertError(ErrorCode.SUM_OVERFLOW, () -> service.sum(0));
    assertEquals("The transaction sum exceeds the finite double range.", error.getMessage());
  }

  @Test
  void traversesDeepHierarchiesWithoutRecursion() {
    for (long id = 0; id < 20000; id++) {
      store.save(new Transaction(id, 1, "cars", id == 0 ? null : id - 1));
    }
    assertEquals(20000, service.sum(0));
  }

  @Test
  void creatingAChainDoesNotRescanPreviouslyValidatedAncestors() {
    var counted = new CountingStore();
    var subject = new TransactionService(counted, new TransactionAccessCoordinator());
    for (long id = 0; id < 1000; id++) {
      assertTrue(subject.save(id, 1.0, "cars", id == 0 ? null : id - 1));
    }
    assertTrue(counted.lookups <= 2000, "Creating new IDs must not walk ancestor chains");
    assertEquals(1000, subject.sum(0));
  }

  @Test
  void updatingWithoutChangingTheParentDoesNotWalkTheHierarchy() {
    var counted = new CountingStore();
    for (long id = 0; id < 1000; id++) {
      counted.save(new Transaction(id, 1, "cars", id == 0 ? null : id - 1));
    }
    var subject = new TransactionService(counted, new TransactionAccessCoordinator());
    assertFalse(subject.save(999, 2.0, "shopping", 998L));
    assertEquals(1, counted.lookups);
    assertEquals(1001, subject.sum(0));
  }

  private static final class CountingStore extends InMemoryTransactionStore {
    private int lookups;

    @Override
    public Optional<Transaction> find(long id) {
      lookups++;
      return super.find(id);
    }
  }

  private TransactionException assertError(
      ErrorCode code, org.junit.jupiter.api.function.Executable action) {
    var error = assertThrows(TransactionException.class, action);
    assertEquals(code, error.code());
    return error;
  }
}
