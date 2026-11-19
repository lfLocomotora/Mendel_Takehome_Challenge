package com.mendel.transaction.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mendel.transaction.domain.Transaction;
import com.mendel.transaction.storage.InMemoryTransactionStore;
import com.mendel.transaction.storage.TransactionStore;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TransactionApiIT.Isolation.class)
class TransactionApiIT {
  @LocalServerPort private int port;
  @Autowired private IsolatedStore isolatedStore;
  private final JsonMapper json = JsonMapper.shared();

  @BeforeEach
  void startWithEmptyStorage() {
    isolatedStore.delegate = new InMemoryTransactionStore();
  }

  @Test
  void servesTheAssignmentExampleOverRealHttp() throws Exception {
    assertEquals(201, put(10, "{\"amount\":5000,\"type\":\"cars\"}").statusCode());
    assertEquals(
        201, put(11, "{\"amount\":10000,\"type\":\"shopping\",\"parent_id\":10}").statusCode());
    assertEquals(
        201, put(12, "{\"amount\":5000,\"type\":\"shopping\",\"parent_id\":11}").statusCode());
    assertEquals(20000, json.readTree(get("/transactions/sum/10").body()).get("sum").doubleValue());
    assertEquals(15000, json.readTree(get("/transactions/sum/11").body()).get("sum").doubleValue());
    assertEquals("[10]", get("/transactions/types/cars").body());
    var repeated = put(10, "{\"amount\":5000,\"type\":\"cars\"}");
    assertEquals(200, repeated.statusCode());
    assertEquals("ok", json.readTree(repeated.body()).get("status").stringValue());
  }

  @Test
  void reportsMissingTransactionsInTheAgreedFormat() throws Exception {
    var response = get("/transactions/sum/9999");
    assertEquals(404, response.statusCode());
    var error = json.readTree(response.body());
    assertEquals("TRANSACTION_NOT_FOUND", error.get("code").stringValue());
    assertEquals("Transaction with ID 9999 does not exist.", error.get("message").stringValue());
    assertTrue(
        response.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"food/drink", "food%2Fdrink", "food\\drink", "food;drink", "food?drink#extra"})
  void retrievesAndPaginatesTypesContainingReservedUrlCharacters(String type) throws Exception {
    for (long id : List.of(1L, 2L)) {
      assertEquals(
          201,
          put(id, json.writeValueAsString(java.util.Map.of("amount", 1, "type", type)))
              .statusCode());
    }
    String path =
        "/transactions/types/"
            + URLEncoder.encode(type, StandardCharsets.UTF_8).replace("+", "%20");
    var all = get(path);
    assertEquals(200, all.statusCode(), all.body());
    assertEquals("[1,2]", all.body());
    var first = get(path + "?limit=1");
    assertEquals(200, first.statusCode(), first.body());
    assertEquals("[1]", first.body());
    var last = get(nextLink(first));
    assertEquals(200, last.statusCode(), last.body());
    assertEquals("[2]", last.body());
    assertTrue(last.headers().firstValue("Link").isEmpty());
  }

  @Test
  void treatsCaseVariantsAsTheSameCategoryByDesign() throws Exception {
    assertEquals(201, put(1, "{\"amount\":1,\"type\":\"Cars\"}").statusCode());
    assertEquals(201, put(2, "{\"amount\":1,\"type\":\"cars\"}").statusCode());
    assertEquals("[1,2]", get("/transactions/types/CARS").body());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        ".",
        "..",
        " . ",
        "a\\u0000b",
        "\\uD800",
        "\\uDC00",
        "a\\uD800b",
        "\\u002e\\u002e"
      })
  void rejectsUnusableTypesBeforeCreatingOrReplacing(String jsonType) throws Exception {
    assertEquals(201, put(1, "{\"amount\":2,\"type\":\"cars\"}").statusCode());
    String body = "{\"amount\":99,\"type\":\"" + jsonType + "\"}";
    assertError(put(1, body), 400, "INVALID_REQUEST");
    assertError(put(2, body), 400, "INVALID_REQUEST");
    assertEquals("[1]", get("/transactions/types/cars").body());
    assertEquals(2, json.readTree(get("/transactions/sum/1").body()).get("sum").doubleValue());
    assertError(get("/transactions/sum/2"), 404, "TRANSACTION_NOT_FOUND");
  }

  @Test
  void rejectsOversizedTypesAtTheHttpBoundaryWithoutChangingState() throws Exception {
    assertEquals(201, put(1, "{\"amount\":2,\"type\":\"cars\"}").statusCode());
    for (String type : List.of("a".repeat(1025), "é".repeat(513), "\u0130".repeat(342))) {
      String body = json.writeValueAsString(java.util.Map.of("amount", 99, "type", type));
      assertError(put(1, body), 400, "INVALID_REQUEST");
      assertError(put(2, body), 400, "INVALID_REQUEST");
      assertEquals("[1]", get("/transactions/types/cars").body());
      assertEquals(2, json.readTree(get("/transactions/sum/1").body()).get("sum").doubleValue());
      assertError(get("/transactions/sum/2"), 404, "TRANSACTION_NOT_FOUND");
    }
  }

  @Test
  void paginatesBoundaryTypesWithLinksThatFitTheDefaultTransportLimits() throws Exception {
    long id = 0;
    for (String type :
        List.of("a".repeat(1024), "é".repeat(512), "😀".repeat(256), "\u0130".repeat(341))) {
      long firstId = id++;
      long secondId = id++;
      String body = json.writeValueAsString(java.util.Map.of("amount", 1, "type", type));
      assertEquals(201, put(firstId, body).statusCode());
      assertEquals(201, put(secondId, body).statusCode());
      String path =
          "/transactions/types/"
              + URLEncoder.encode(type, StandardCharsets.UTF_8).replace("+", "%20");
      var first = get(path + "?limit=1");
      assertEquals(200, first.statusCode(), first.body());
      assertEquals("[" + firstId + "]", first.body());
      String next = nextLink(first);
      assertTrue(
          next.length() < 5000,
          "Continuation must leave room for default request/response headers");
      var last = get(next);
      assertEquals(200, last.statusCode(), last.body());
      assertEquals("[" + secondId + "]", last.body());
      assertTrue(last.headers().firstValue("Link").isEmpty());
    }
  }

  @Test
  void buildsContinuationFromTheNormalizedTypeRatherThanRawPathPadding() throws Exception {
    String type = "a".repeat(1024);
    String body = json.writeValueAsString(java.util.Map.of("amount", 1, "type", type));
    assertEquals(201, put(1, body).statusCode());
    assertEquals(201, put(2, body).statusCode());
    String padded = " ".repeat(1000) + type.toUpperCase(java.util.Locale.ROOT) + " ".repeat(1000);
    String path =
        "/transactions/types/"
            + URLEncoder.encode(padded, StandardCharsets.UTF_8).replace("+", "%20");
    var first = get(path + "?limit=1");
    assertEquals(200, first.statusCode(), first.body());
    String next = nextLink(first);
    assertTrue(next.startsWith("/transactions/types/" + type + "?"));
    assertEquals("[2]", get(next).body());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "null",
        "[]",
        "{",
        "{}",
        "{\"type\":\"cars\"}",
        "{\"amount\":null,\"type\":\"cars\"}",
        "{\"amount\":\"2\",\"type\":\"cars\"}",
        "{\"amount\":true,\"type\":\"cars\"}",
        "{\"amount\":1}",
        "{\"amount\":1,\"type\":null}",
        "{\"amount\":1,\"type\":2}",
        "{\"amount\":1,\"type\":\" \"}",
        "{\"amount\":1e400,\"type\":\"cars\"}",
        "{\"amount\":NaN,\"type\":\"cars\"}",
        "{\"amount\":Infinity,\"type\":\"cars\"}",
        "{\"amount\":1,\"type\":\"cars\",\"currency\":\"ARS\"}",
        "{\"amount\":1,\"type\":\"cars\",\"parent_id\":1.5}",
        "{\"amount\":1,\"type\":\"cars\",\"parent_id\":\"1\"}",
        "{\"amount\":1,\"type\":\"cars\",\"parent_id\":9223372036854775808}"
      })
  void rejectsMalformedOrCoercedInputWithoutSaving(String body) throws Exception {
    assertError(put(1, body), 400, "INVALID_REQUEST");
    assertError(get("/transactions/sum/1"), 404, "TRANSACTION_NOT_FOUND");
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.5", "9223372036854775808", "-9223372036854775809", "abc"})
  void rejectsIdsOutsideTheContract(String id) throws Exception {
    assertError(
        request("PUT", "/transactions/" + id, "{\"amount\":1,\"type\":\"cars\"}"),
        400,
        "INVALID_REQUEST");
    assertError(get("/transactions/sum/" + id), 400, "INVALID_REQUEST");
  }

  @Test
  void preservesLongBoundariesAndParentIdsExactly() throws Exception {
    assertEquals(201, put(Long.MIN_VALUE, "{\"amount\":-2,\"type\":\" CARS \"}").statusCode());
    assertEquals(
        201,
        put(Long.MAX_VALUE, "{\"amount\":2,\"type\":\"cars\",\"parent_id\":-9223372036854775808}")
            .statusCode());
    assertEquals(
        0,
        json.readTree(get("/transactions/sum/" + Long.MIN_VALUE).body()).get("sum").doubleValue());
    var ids = json.readTree(get("/transactions/types/CARS").body());
    assertEquals(Long.MIN_VALUE, ids.get(0).longValue());
    assertEquals(Long.MAX_VALUE, ids.get(1).longValue());
  }

  @Test
  void mapsBusinessErrorsAndPreservesRejectedChanges() throws Exception {
    put(10, "{\"amount\":1,\"type\":\"root\"}");
    put(11, "{\"amount\":2,\"type\":\"cars\",\"parent_id\":10}");
    var missing = put(11, "{\"amount\":90,\"type\":\"changed\",\"parent_id\":99}");
    assertError(missing, 409, "PARENT_NOT_FOUND");
    assertEquals(
        "Parent transaction with ID 99 does not exist.",
        json.readTree(missing.body()).get("message").stringValue());
    assertError(
        put(10, "{\"amount\":90,\"type\":\"changed\",\"parent_id\":11}"), 409, "CYCLE_DETECTED");
    assertError(put(1, "{\"amount\":2,\"type\":\"cars\",\"parent_id\":1}"), 409, "CYCLE_DETECTED");
    assertEquals(3, json.readTree(get("/transactions/sum/10").body()).get("sum").doubleValue());
    assertEquals("[11]", get("/transactions/types/cars").body());
    assertEquals("[]", get("/transactions/types/changed").body());
    assertEquals(
        200, put(11, "{\"amount\":3,\"type\":\" SHOPPING \",\"parent_id\":null}").statusCode());
    assertEquals(1, json.readTree(get("/transactions/sum/10").body()).get("sum").doubleValue());
    assertEquals("[]", get("/transactions/types/cars").body());
    assertEquals("[11]", get("/transactions/types/shopping").body());
  }

  @Test
  void reportsOverflowWithoutAReplacementTotal() throws Exception {
    put(0, "{\"amount\":1.7976931348623157E308,\"type\":\"root\"}");
    put(1, "{\"amount\":1.7976931348623157E308,\"type\":\"cars\",\"parent_id\":0}");
    var response = get("/transactions/sum/0");
    assertError(response, 409, "SUM_OVERFLOW");
    assertEquals(
        "The transaction sum exceeds the finite double range.",
        json.readTree(response.body()).get("message").stringValue());
  }

  @Test
  void followsLinkHeadersAndRetainsTheUnpaginatedContract() throws Exception {
    String type = "compras café";
    for (long id : List.of(-1L, 0L, Long.MAX_VALUE, Long.MIN_VALUE)) {
      put(id, "{\"amount\":0,\"type\":\"" + type + "\"}");
    }
    String path =
        "/transactions/types/"
            + URLEncoder.encode(type, StandardCharsets.UTF_8).replace("+", "%20");
    var first = get(path + "?limit=2");
    assertEquals("[" + Long.MIN_VALUE + ",-1]", first.body());
    String nextPath = nextLink(first);
    assertTrue(nextPath.contains("limit=2"));
    var last = get(nextPath);
    assertEquals("[0," + Long.MAX_VALUE + "]", last.body());
    assertTrue(last.headers().firstValue("Link").isEmpty());
    assertEquals(4, json.readTree(get(path).body()).size());
    assertError(
        get("/transactions/types/other?" + nextPath.split("\\?", 2)[1]), 400, "INVALID_CURSOR");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "limit=0",
        "limit=-1",
        "limit=1001",
        "limit=1.5",
        "limit=abc",
        "limit=",
        "limit=99999999999999",
        "cursor=",
        "cursor=abc",
        "extra=1",
        "limit=1&limit=2",
        "cursor=a&cursor=b"
      })
  void rejectsInvalidPaginationParameters(String query) throws Exception {
    var response = get("/transactions/types/cars?" + query);
    assertEquals(400, response.statusCode());
    var code = json.readTree(response.body()).get("code").stringValue();
    assertTrue(code.equals("INVALID_REQUEST") || code.equals("INVALID_CURSOR"));
  }

  private String nextLink(HttpResponse<String> response) {
    String link = response.headers().firstValue("Link").orElseThrow();
    assertTrue(link.endsWith("; rel=\"next\""));
    return link.substring(1, link.indexOf('>'));
  }

  private void assertError(HttpResponse<String> response, int status, String code) {
    assertEquals(status, response.statusCode(), response.body());
    var error = json.readTree(response.body());
    assertEquals(2, error.size());
    assertEquals(code, error.get("code").stringValue());
    assertFalse(error.get("message").stringValue().isBlank());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Isolation {
    @Bean
    @Primary
    IsolatedStore isolatedTransactionStore() {
      return new IsolatedStore();
    }
  }

  static class IsolatedStore implements TransactionStore {
    private TransactionStore delegate = new InMemoryTransactionStore();

    public Optional<Transaction> find(long id) {
      return delegate.find(id);
    }

    public void save(Transaction transaction) {
      delegate.save(transaction);
    }

    public List<Long> idsByType(String type) {
      return delegate.idsByType(type);
    }

    public List<Long> childIds(long id) {
      return delegate.childIds(id);
    }

    public List<Long> idsAfter(String type, Long after, int maximum) {
      return delegate.idsAfter(type, after, maximum);
    }
  }

  private HttpResponse<String> put(long id, String body) throws Exception {
    return request("PUT", "/transactions/" + id, body);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return request("GET", path, null);
  }

  private HttpResponse<String> request(String method, String path, String body) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
