package com.mendel.transaction.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mendel.transaction.concurrency.TransactionAccessCoordinator;
import com.mendel.transaction.domain.ErrorCode;
import com.mendel.transaction.domain.TransactionException;
import com.mendel.transaction.storage.InMemoryTransactionStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionPaginationTest {
  private final TransactionService service = new TransactionService(new InMemoryTransactionStore(), new TransactionAccessCoordinator());

  @Test
  void continuesAcrossSignedIdsAndStopsOnAnExactlyFullLastPage() {
    for (long id : List.of(Long.MAX_VALUE, 0L, Long.MIN_VALUE, -1L)) {
      service.save(id, 1.0, "cars", null);
    }
    var first = service.pageByType(" CARS ", 2, null);
    assertEquals(List.of(Long.MIN_VALUE, -1L), first.ids());
    assertNotNull(first.nextCursor());
    var last = service.pageByType("cars", 2, first.nextCursor());
    assertEquals(List.of(0L, Long.MAX_VALUE), last.ids());
    assertNull(last.nextCursor());
  }

  @Test
  void returnsEmptyPageWithoutContinuation() {
    var page = service.pageByType("cars", 1, null);
    assertTrue(page.ids().isEmpty());
    assertNull(page.nextCursor());
  }

  @Test
  void defaultsToOneHundredWhenOnlyCursorIsPresent() {
    for (long id = 0; id < 103; id++) {
      service.save(id, 0.0, "cars", null);
    }
    var first = service.pageByType("cars", 1, null);
    var next = service.pageByType("cars", null, first.nextCursor());
    assertEquals(100, next.ids().size());
    assertEquals(1L, next.ids().getFirst());
    assertEquals(100L, next.ids().getLast());
    assertEquals(List.of(101L, 102L), service.pageByType("cars", null, next.nextCursor()).ids());
  }

  @Test
  void readsCurrentDataWithoutRequiringTheCursorIdToStillMatch() {
    for (long id : List.of(1L, 2L, 4L)) {
      service.save(id, 1.0, "cars", null);
    }
    var first = service.pageByType("cars", 2, null);
    service.save(2, 1.0, "other", null);
    service.save(0, 1.0, "cars", null);
    service.save(3, 1.0, "cars", null);
    service.save(4, 1.0, "other", null);
    var next = service.pageByType("cars", 2, first.nextCursor());
    assertEquals(List.of(3L), next.ids());
    assertNull(next.nextCursor());
    assertEquals(List.of(1L, 2L), first.ids());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 1001, Integer.MAX_VALUE})
  void rejectsOutOfRangePageSizes(int limit) {
    var error = assertThrows(TransactionException.class, () -> service.pageByType("cars", limit, null));
    assertEquals(ErrorCode.INVALID_REQUEST, error.code());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "!invalid", "abc", "aA=="})
  void rejectsMalformedCursors(String cursor) {
    var error = assertThrows(TransactionException.class, () -> service.pageByType("cars", 1, cursor));
    assertEquals(ErrorCode.INVALID_CURSOR, error.code());
    assertEquals("The pagination cursor is invalid.", error.getMessage());
  }

  @Test
  void rejectsCursorFromAnotherType() {
    service.save(1, 1.0, "cars", null);
    service.save(2, 1.0, "cars", null);
    var first = service.pageByType("cars", 1, null);
    var error = assertThrows(TransactionException.class, () -> service.pageByType("shopping", 1, first.nextCursor()));
    assertEquals(ErrorCode.INVALID_CURSOR, error.code());
    assertEquals("The pagination cursor does not match the requested transaction type.", error.getMessage());
  }

  @Test
  void canTraverseATypeContainingUnicodeAndSeparators() {
    String type = "Compras: café\nextras";
    service.save(1, 0.0, type, null);
    service.save(2, 0.0, type, null);
    var first = service.pageByType(type, 1, null);
    assertEquals(List.of(2L), service.pageByType(type, 1, first.nextCursor()).ids());
  }
}
