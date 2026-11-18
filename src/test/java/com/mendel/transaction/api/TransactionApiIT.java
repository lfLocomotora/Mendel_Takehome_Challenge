package com.mendel.transaction.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionApiIT {
  @LocalServerPort private int port;
  private final JsonMapper json = JsonMapper.shared();

  @Test
  void servesTheAssignmentExampleOverRealHttp() throws Exception {
    assertEquals(201, put(10, "{\"amount\":5000,\"type\":\"cars\"}").statusCode());
    assertEquals(201, put(11, "{\"amount\":10000,\"type\":\"shopping\",\"parent_id\":10}").statusCode());
    assertEquals(201, put(12, "{\"amount\":5000,\"type\":\"shopping\",\"parent_id\":11}").statusCode());
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
    assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
  }

  private HttpResponse<String> put(long id, String body) throws Exception {
    return request("PUT", "/transactions/" + id, body);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return request("GET", path, null);
  }

  private HttpResponse<String> request(String method, String path, String body) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
          .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
          .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
