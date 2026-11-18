package com.mendel.transaction.api;

import com.mendel.transaction.domain.ErrorCode;
import com.mendel.transaction.domain.TransactionException;
import java.util.Set;
import tools.jackson.databind.JsonNode;

record PutTransactionRequest(Double amount, String type, Long parentId) {
  private static final Set<String> FIELDS = Set.of("amount", "type", "parent_id");

  static PutTransactionRequest from(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw invalid("Request body must be a JSON object.");
    }
    for (String field : body.propertyNames()) {
      if (!FIELDS.contains(field)) {
        throw invalid("Unknown field: " + field + ".");
      }
    }
    JsonNode amount = body.get("amount");
    if (amount == null || !amount.isNumber()) {
      throw invalid("Amount is required and must be a number.");
    }
    JsonNode type = body.get("type");
    if (type == null || !type.isString()) {
      throw invalid("Type is required and must be a string.");
    }
    JsonNode parent = body.get("parent_id");
    Long parentId = null;
    if (parent != null && !parent.isNull()) {
      if (!parent.isIntegralNumber() || !parent.canConvertToLong()) {
        throw invalid("Parent ID must be an integer within the signed 64-bit range.");
      }
      parentId = parent.longValue();
    }
    return new PutTransactionRequest(amount.numberValue().doubleValue(), type.stringValue(), parentId);
  }

  private static TransactionException invalid(String message) {
    return new TransactionException(ErrorCode.INVALID_REQUEST, message);
  }
}
