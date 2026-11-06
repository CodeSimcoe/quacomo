package com.codesimcoe.quacomo.remoting;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.inject.*;
import org.eclipse.microprofile.config.ConfigProvider;
import com.fasterxml.jackson.databind.*;
import io.quarkus.arc.Arc;

/**
 * Shared transport; generated adapters supply all types and operation identifiers.
 */
@Singleton
public class RemoteTransport {
  @Inject
  ObjectMapper mapper;
  private final AtomicInteger pending = new AtomicInteger();

  @PostConstruct
  void initialize() {
    mapper = codec(mapper);
  }

  static ObjectMapper codec(ObjectMapper source) {
    ObjectMapper result = source.copy().deactivateDefaultTyping();
    result.disable(MapperFeature.USE_ANNOTATIONS);
    return result;
  }

  private final HttpClient client =
      HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build();

  @PreDestroy
  void close() {
    client.shutdownNow();
  }

  public static Object decode(JsonNode value, String type) {
    ObjectMapper mapper = Arc.container().instance(RemoteTransport.class).get().mapper;
    return mapper.convertValue(value, mapper.getTypeFactory().constructFromCanonical(type));
  }

  public CompletionStage<?> call(
      String destinations,
      String operation,
      Object[] arguments,
      String resultType,
      boolean event
  ) {
    try {
      String[] names = destinations.split(",", -1);
      if (!event && names.length != 1) {
        throw new IllegalArgumentException("A service requires exactly one destination");
      }
      byte[] payload =
          mapper.writeValueAsBytes(
              new RemoteProtocol.Request(operation, mapper.convertValue(arguments, JsonNode[].class))
      );
      int max = config("max-payload-bytes", Integer.class, 1048576);
      if (payload.length > max) {
        throw new RemoteCallException("PAYLOAD_TOO_LARGE", "Remote request exceeds payload limit");
      }
      List<CompletableFuture<?>> calls = new ArrayList<>();
      for (String name : names) {
        try {
          calls.add(send(name.trim(), payload, resultType, max).toCompletableFuture());
        } catch (Exception failure) {
          calls.add(CompletableFuture.failedFuture(failure));
        }
      }
      if (!event) {
        return calls.getFirst();
      }
      return CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new));
    } catch (Exception failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private CompletionStage<?> send(String destination, byte[] payload, String resultType, int max) {
    if (!destination.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException("Invalid remote destination: " + destination);
    }
    String prefix = "destinations." + destination + ".";
    String url = config(prefix + "url", String.class, "");
    URI base = URI.create(url);
    if ((!"http".equals(base.getScheme()) && !"https".equals(base.getScheme()))
        || base.getHost() == null
        || base.getRawQuery() != null
        || base.getRawFragment() != null
        || base.getUserInfo() != null) {
      throw new IllegalArgumentException("Expected an HTTP(S) base URL for destination " + destination);
    }
    URI endpoint = URI.create(url.replaceAll("/+$", "") + "/quacomo/remote");
    HttpRequest.Builder request = HttpRequest
      .newBuilder(endpoint)
      .timeout(Duration.ofMillis(config("timeout-ms", Long.class, 10000L)))
      .header("Content-Type", "application/json")
      .header("X-Request-ID", UUID.randomUUID().toString())
      .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
    String token = config(prefix + "token", String.class, "");
    if (!token.isEmpty()) {
      request.header("Authorization", "Bearer " + token);
    }
    return exchange(request.build(), max).thenApply(response -> {
      try {
        byte[] body = response.body();
        if (body.length > max) {
          throw new RemoteCallException("PAYLOAD_TOO_LARGE", "Remote response exceeds payload limit");
        }
        if (response.statusCode() != 200) {
          throw new RemoteCallException(
              "HTTP_" + response.statusCode(),
              "Remote endpoint rejected request: HTTP " + response.statusCode()
          );
        }
        RemoteProtocol.Reply reply = mapper.readValue(body, RemoteProtocol.Reply.class);
        if (reply.error() != null) {
          throw new RemoteCallException(reply.error(), reply.message());
        }
        return "java.lang.Void".equals(resultType)
            ? null
            : mapper.convertValue(reply.result(), mapper.getTypeFactory().constructFromCanonical(resultType));
      } catch (RemoteCallException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new RemoteCallException("PROTOCOL_ERROR", "Invalid remote response");
      }
    });
  }

  private CompletionStage<HttpResponse<byte[]>> exchange(HttpRequest request, int max) {
    if (pending.incrementAndGet() > config("max-in-flight", Integer.class, 128)) {
      pending.decrementAndGet();
      return CompletableFuture.failedFuture(new RemoteCallException("BUSY", "Too many remote requests"));
    }
    try {
      return client
        .sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), max))
        .orTimeout(config("timeout-ms", Long.class, 10000L), TimeUnit.MILLISECONDS)
        .whenComplete((_, _) -> pending.decrementAndGet());
    } catch (Throwable failure) {
      pending.decrementAndGet();
      return CompletableFuture.failedFuture(failure);
    }
  }

  static <T> T config(String key, Class<T> type, T fallback) {
    return ConfigProvider.getConfig().getOptionalValue("quacomo.remote." + key, type).orElse(fallback);
  }
}
