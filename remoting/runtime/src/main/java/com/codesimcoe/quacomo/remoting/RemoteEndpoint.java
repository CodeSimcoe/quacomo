package com.codesimcoe.quacomo.remoting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.inject.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.All;
import io.smallrye.common.annotation.Blocking;

@Path("/quacomo/remote")
@Singleton
@Blocking
public class RemoteEndpoint {
  private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(RemoteEndpoint.class);
  @Inject
  @All
  List<RemoteOperation> operations;
  @Inject
  ObjectMapper mapper;

  @jakarta.annotation.PostConstruct
  void initialize() {
    mapper = RemoteTransport.codec(mapper);
    Set<String> allowlist = new HashSet<>(Arrays
      .stream(RemoteTransport.config("exports", String.class, "").split(","))
      .map(String::trim)
      .toList()
    );
    Map<String, RemoteOperation> selected = new HashMap<>();
    for (RemoteOperation operation : operations) {
      if (allowlist.contains(operation.contract())) {
        selected.put(operation.key(), operation);
      }
    }
    exported = Map.copyOf(selected);
  }

  private Map<String, RemoteOperation> exported;
  private final AtomicInteger inFlight = new AtomicInteger();

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public CompletionStage<Response> invoke(byte[] body, @HeaderParam("Authorization") String authorization) {
    String token = RemoteTransport.config("server.token", String.class, "");
    if (token.isEmpty()
        || authorization == null
        || !MessageDigest.isEqual(
            ("Bearer " + token).getBytes(StandardCharsets.UTF_8),
            authorization.getBytes(StandardCharsets.UTF_8)
    )) {
      return response(401, "UNAUTHORIZED");
    }
    if (body.length > RemoteTransport.config("max-payload-bytes", Integer.class, 1048576)) {
      return response(413, "PAYLOAD_TOO_LARGE");
    }
    RemoteProtocol.Request request;
    try {
      request = mapper.readValue(body, RemoteProtocol.Request.class);
    } catch (Exception failure) {
      return response(400, "BAD_REQUEST");
    }
    if (request == null || request.operation() == null || request.arguments() == null) {
      return response(400, "BAD_REQUEST");
    }
    RemoteOperation operation = exported.get(request.operation());
    if (operation == null) {
      return response(404, "UNKNOWN_OPERATION");
    }
    if (request.arguments().length != operation.arity()) {
      return response(400, "BAD_ARGUMENTS");
    }
    if (inFlight.incrementAndGet() > RemoteTransport.config("max-in-flight", Integer.class, 128)) {
      inFlight.decrementAndGet();
      return response(503, "BUSY");
    }
    try {
      CompletionStage<?> delivery = Objects.requireNonNull(operation.invoke(request.arguments()));
      return delivery.handle((result, failure) -> {
        try {
          if (failure != null) {
            LOG.debug("Remote operation failed: " + operation.key(), failure);
            return reply(200, "REMOTE_EXECUTION_FAILED");
          }
          byte[] encoded = mapper.writeValueAsBytes(new RemoteProtocol.Reply(mapper.valueToTree(result), null, null));
          if (encoded.length > RemoteTransport.config("max-payload-bytes", Integer.class, 1048576)) {
            return reply(200, "PAYLOAD_TOO_LARGE");
          }
          return Response.ok(encoded, MediaType.APPLICATION_JSON).build();
        } catch (Exception serializationFailure) {
          return reply(200, "SERIALIZATION_FAILED");
        } finally {
          inFlight.decrementAndGet();
        }
      });
    } catch (Throwable failure) {
      LOG.debug("Remote operation failed: " + operation.key(), failure);
      inFlight.decrementAndGet();
      return response(200, "REMOTE_EXECUTION_FAILED");
    }
  }

  private static Response reply(int status, String code) {
    return Response.status(status).entity(new RemoteProtocol.Reply(null, code, code)).build();
  }

  private static CompletionStage<Response> response(int status, String code) {
    return CompletableFuture.completedFuture(reply(status, code));
  }
}
