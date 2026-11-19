package com.mendel.transaction.api;

import com.mendel.transaction.application.TransactionService;
import com.mendel.transaction.domain.ErrorCode;
import com.mendel.transaction.domain.TransactionException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/transactions")
public final class TransactionController {
  private final TransactionService service;

  public TransactionController(TransactionService service) {
    this.service = service;
  }

  @PutMapping(value = "/{id}", consumes = "application/json", produces = "application/json")
  public ResponseEntity<Map<String, String>> save(
      @PathVariable long id, @RequestBody JsonNode body) {
    var request = PutTransactionRequest.from(body);
    boolean created = service.save(id, request.amount(), request.type(), request.parentId());
    return ResponseEntity.status(created ? 201 : 200).body(Map.of("status", "ok"));
  }

  @GetMapping(value = "/sum/{id}", produces = "application/json")
  public Map<String, Double> sum(@PathVariable long id) {
    return Map.of("sum", service.sum(id));
  }

  @GetMapping(value = "/types/{type}", produces = "application/json")
  public ResponseEntity<List<Long>> byType(
      @PathVariable String type, @RequestParam MultiValueMap<String, String> parameters) {
    validateParameters(parameters);
    String limit = parameters.getFirst("limit");
    String cursor = parameters.getFirst("cursor");
    if (limit == null && cursor == null) {
      return ResponseEntity.ok(service.idsByType(type));
    }
    Integer pageSize = parseLimit(limit);
    var page = service.pageByType(type, pageSize, cursor);
    var response = ResponseEntity.ok();
    if (page.nextCursor() != null) {
      URI next =
          UriComponentsBuilder.fromPath("/transactions/types/{type}")
              .queryParam("limit", pageSize == null ? 100 : pageSize)
              .queryParam("cursor", page.nextCursor())
              .encode()
              .buildAndExpand(page.type())
              .toUri();
      response.header(HttpHeaders.LINK, "<" + next.toASCIIString() + ">; rel=\"next\"");
    }
    return response.body(page.ids());
  }

  private static Integer parseLimit(String limit) {
    if (limit == null) {
      return null;
    }
    try {
      return Integer.valueOf(limit);
    } catch (NumberFormatException exception) {
      throw new TransactionException(
          ErrorCode.INVALID_REQUEST, "Limit must be an integer between 1 and 1000.");
    }
  }

  private static void validateParameters(MultiValueMap<String, String> parameters) {
    for (var entry : parameters.entrySet()) {
      if (!Set.of("limit", "cursor").contains(entry.getKey())) {
        throw new TransactionException(
            ErrorCode.INVALID_REQUEST, "Unknown query parameter: " + entry.getKey() + ".");
      }
      if (entry.getValue().size() != 1) {
        throw new TransactionException(
            ErrorCode.INVALID_REQUEST,
            "Query parameter must occur only once: " + entry.getKey() + ".");
      }
    }
  }
}
