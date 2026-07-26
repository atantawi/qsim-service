/*
 * qsim-service — a JMT-backed queueing-network simulation service.
 * Copyright (C) 2026 qsim-service contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package qsim.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import qsim.contract.ValidationException;
import qsim.engine.EngineException;
import qsim.model.SimulationRequest;
import qsim.model.SimulationResponse;

public class SimulationHandler implements HttpHandler {

  /** Upper bound on request-body size, to prevent unbounded-memory DoS. */
  static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;

  private static final Logger LOG = Logger.getLogger(SimulationHandler.class.getName());

  public record Result(int status, byte[] body) {}

  /** Thrown when a request body exceeds MAX_REQUEST_BYTES. */
  static final class PayloadTooLargeException extends IOException {
    PayloadTooLargeException(String m) { super(m); }
  }

  private final SimulationService service;

  public SimulationHandler(SimulationService service) {
    this.service = service;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        writeJson(exchange, 405, toJson(new ErrorResponse("method not allowed", List.of())));
        return;
      }
      String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
      if (contentLength != null) {
        try {
          if (Long.parseLong(contentLength.trim()) > MAX_REQUEST_BYTES) {
            writeJson(exchange, 413, toJson(new ErrorResponse("request body too large",
                List.of("limit " + MAX_REQUEST_BYTES + " bytes"))));
            return;
          }
        } catch (NumberFormatException ignored) {
          // fall through to bounded read, which enforces the limit regardless.
        }
      }
      byte[] body;
      try {
        body = readBounded(exchange.getRequestBody(), MAX_REQUEST_BYTES);
      } catch (PayloadTooLargeException e) {
        writeJson(exchange, 413, toJson(new ErrorResponse("request body too large",
            List.of("limit " + MAX_REQUEST_BYTES + " bytes"))));
        return;
      }
      Result r = process(body);
      writeJson(exchange, r.status(), r.body());
    } finally {
      exchange.close();
    }
  }

  /** Reads up to {@code max} bytes from {@code in}; throws PayloadTooLargeException if more remain. */
  static byte[] readBounded(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 8192));
    byte[] buf = new byte[8192];
    int total = 0;
    int n;
    while ((n = in.read(buf)) != -1) {
      total += n;
      if (total > max) {
        throw new PayloadTooLargeException("request body exceeds " + max + " bytes");
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  /** Pure request→response mapping, testable without a socket. */
  public Result process(byte[] requestBody) {
    SimulationRequest req;
    try {
      req = Json.MAPPER.readValue(requestBody, SimulationRequest.class);
    } catch (JsonProcessingException | java.io.UncheckedIOException e) {
      return new Result(400, toJson(new ErrorResponse("malformed request JSON", List.of(rootMessage(e)))));
    } catch (IOException e) {
      return new Result(400, toJson(new ErrorResponse("malformed request JSON", List.of(rootMessage(e)))));
    }
    try {
      SimulationResponse resp = service.simulate(req);
      return new Result(200, toJson(resp));
    } catch (ValidationException e) {
      return new Result(statusFor(e), toJson(new ErrorResponse(
          e.kind() == ValidationException.Kind.BAD_REQUEST ? "invalid request" : "unprocessable model",
          e.details())));
    } catch (EngineException e) {
      String correlationId = UUID.randomUUID().toString();
      LOG.log(Level.SEVERE, "simulation failed [" + correlationId + "]", e);
      return new Result(500, toJson(new ErrorResponse("simulation engine error",
          List.of("correlationId=" + correlationId))));
    } catch (RuntimeException e) {
      String correlationId = UUID.randomUUID().toString();
      LOG.log(Level.SEVERE, "simulation failed [" + correlationId + "]", e);
      return new Result(500, toJson(new ErrorResponse("internal error",
          List.of("correlationId=" + correlationId))));
    }
  }

  public int statusFor(Throwable t) {
    if (t instanceof ValidationException v) {
      return v.kind() == ValidationException.Kind.BAD_REQUEST ? 400 : 422;
    }
    if (t instanceof JsonProcessingException || t instanceof JsonMappingException) {
      return 400;
    }
    return 500;
  }

  private static String rootMessage(Throwable t) {
    Throwable r = t;
    while (r.getCause() != null && r.getCause() != r) {
      r = r.getCause();
    }
    return r.getMessage() == null ? r.getClass().getSimpleName() : r.getMessage();
  }

  private static byte[] toJson(Object o) {
    try {
      return Json.MAPPER.writeValueAsBytes(o);
    } catch (JsonProcessingException e) {
      return ("{\"error\":\"failed to serialize response\"}").getBytes(StandardCharsets.UTF_8);
    }
  }

  private static void writeJson(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }
}
