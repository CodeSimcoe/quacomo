package com.codesimcoe.quacomo.remoting.it;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import com.codesimcoe.quacomo.runtime.*;

@Path("/fixture")
@Produces(MediaType.APPLICATION_JSON)
public class Fixture {
  public enum Kind {
    SAMPLE
  }

  public record Message(String text, List<String> tags, Map<String, Integer> counts, Kind kind) {}

  public record Reply(long pid, Message message, int repeat) {}

  public interface ParentService {
    CompletionStage<Reply> echo(Message message, int repeat);
  }

  @Remotable
  @ComponentServices
  public interface Service extends ParentService {
    CompletionStage<String> echo(String text);

    CompletionStage<String> fail();

    CompletionStage<String> slow();
  }

  @Remotable
  @ComponentEvents
  public interface Events {
    CompletionStage<Void> seen(Message message);
  }

  @Component
  public static class Provider implements Service {
    public CompletionStage<Reply> echo(Message message, int repeat) {
      return CompletableFuture.completedFuture(new Reply(ProcessHandle.current().pid(), message, repeat));
    }

    public CompletionStage<String> echo(String text) {
      return CompletableFuture.completedFuture(text);
    }

    public CompletionStage<String> fail() {
      throw new IllegalStateException("private server exception");
    }

    public CompletionStage<String> slow() {
      return new CompletableFuture<>();
    }
  }

  @Component
  public static class Listener implements Events {
    public final AtomicInteger received = new AtomicInteger();
    public volatile Message last;

    public CompletionStage<Void> seen(Message message) {
      last = message;
      received.incrementAndGet();
      return CompletableFuture.completedFuture(null);
    }
  }

  @Component
  public static class Consumer {
    @UsedComponentServices
    Service localService;
    @ProducedComponentEvents
    Events localEvents;
    @UsedComponentServices(remote = "peer")
    Service service;
    @ProducedComponentEvents(remote = "peer")
    Events events;
    @ProducedComponentEvents(remote = "peer,peer2")
    Events fanout;
  }

  @Inject
  Consumer consumer;
  @Inject
  Listener listener;

  static Message message() {
    return new Message("hello", List.of("one", "two"), Map.of("count", 3), Kind.SAMPLE);
  }

  @GET
  @Path("/pid")
  public long pid() {
    return ProcessHandle.current().pid();
  }

  @GET
  @Path("/call")
  public CompletionStage<Reply> call() {
    return consumer.service.echo(message(), 42);
  }

  @GET
  @Path("/local")
  public CompletionStage<Reply> local() {
    return consumer.localService.echo(message(), 1);
  }

  @GET
  @Path("/local-event")
  public CompletionStage<String> localEvent() {
    return consumer.localEvents.seen(message()).thenApply(_ -> "ok");
  }

  @GET
  @Path("/null")
  public CompletionStage<String> nullValue() {
    return consumer.service
      .echo((String) null)
      .thenApply(value -> value == null ? "null" : "wrong");
  }

  @GET
  @Path("/event")
  public CompletionStage<String> event() {
    return consumer.events.seen(message()).thenApply(_ -> "ok");
  }

  @GET
  @Path("/fanout")
  public CompletionStage<String> fanout() {
    return consumer.fanout.seen(message()).thenApply(_ -> "ok");
  }

  @GET
  @Path("/count")
  public int count() {
    return listener.received.get();
  }

  @GET
  @Path("/last")
  public Message last() {
    return listener.last;
  }

  @GET
  @Path("/failure")
  public CompletionStage<String> failure() {
    return consumer.service
      .fail()
      .handle((_, failure) -> failure == null ? "wrong" : "failed");
  }

  @GET
  @Path("/timeout")
  public CompletionStage<String> timeout() {
    return consumer.service
      .slow()
      .handle((_, failure) -> failure == null ? "wrong" : "timed-out");
  }
}
