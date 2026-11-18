package com.mendel.transaction.application;

import com.mendel.transaction.domain.ErrorCode;
import com.mendel.transaction.domain.TransactionException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class CursorCodec {
  private CursorCodec() {}

  static String encode(String type, long lastId) {
    byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
    var buffer = ByteBuffer.allocate(Long.BYTES + typeBytes.length);
    buffer.putLong(lastId).put(typeBytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
  }

  static long decode(String cursor, String expectedType) {
    long id;
    String type;
    try {
      var buffer = ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor));
      if (buffer.remaining() <= Long.BYTES) {
        throw new IllegalArgumentException("Missing cursor type");
      }
      id = buffer.getLong();
      type =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(buffer)
              .toString();
    } catch (IllegalArgumentException | CharacterCodingException exception) {
      throw new TransactionException(ErrorCode.INVALID_CURSOR, "The pagination cursor is invalid.");
    }
    if (!type.equals(expectedType)) {
      throw new TransactionException(
          ErrorCode.INVALID_CURSOR,
          "The pagination cursor does not match the requested transaction type.");
    }
    return id;
  }
}
