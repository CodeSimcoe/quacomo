package com.codesimcoe.quacomo.test;

import java.util.concurrent.*;
import jakarta.inject.Inject;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.arc.ClientProxy;
import com.codesimcoe.quacomo.runtime.*;
import static org.junit.jupiter.api.Assertions.*;

class AsyncEventsTest {
  @RegisterExtension
  static final QuarkusExtensionTest app = new QuarkusExtensionTest()
    .setArchiveProducer(() -> ShrinkWrap
      .create(JavaArchive.class)
      .addClasses(Parent.class, Events.class, Empty.class, Publisher.class, First.class, Second.class, Delivery.class));
  @Inject
  Publisher publisher;

  @Test
  void invokesOffThreadAndWaitsForEverySubscriberStage() throws Exception {
    Delivery delivery = new Delivery();
    Thread caller = Thread.currentThread();
    CompletionStage<Void> result = publisher.events.deliver(delivery, 42, new String[] {"payload"});
    assertTrue(delivery.started.await(10, TimeUnit.SECONDS));
    assertFalse(publisher.events instanceof ClientProxy);
    assertNotSame(caller, delivery.firstThread);
    assertNotSame(caller, delivery.secondThread);
    assertEquals(42, delivery.value);
    assertEquals("payload", delivery.payload);
    assertFalse(result.toCompletableFuture().isDone());
    delivery.first.complete(null);
    assertFalse(result.toCompletableFuture().isDone());
    delivery.second.complete(null);
    result.toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void thrownExceptionDoesNotCancelOtherSubscribers() throws Exception {
    verifyFailure("throw", IllegalStateException.class);
  }

  @Test
  void failedSubscriberStageDoesNotCompleteAggregateEarly() throws Exception {
    verifyFailure("failed", IllegalArgumentException.class);
  }

  @Test
  void nullSubscriberStageIsReportedAsFailure() throws Exception {
    verifyFailure("null", NullPointerException.class);
  }

  private void verifyFailure(String mode, Class<? extends Throwable> failureType) throws Exception {
    Delivery delivery = new Delivery();
    delivery.mode = mode;
    CompletionStage<Void> result = publisher.events.deliver(delivery, 42, new String[] {"payload"});
    assertTrue(delivery.started.await(10, TimeUnit.SECONDS));
    assertFalse(result.toCompletableFuture().isDone());
    delivery.second.complete(null);
    ExecutionException failure =
        assertThrows(ExecutionException.class, () -> result.toCompletableFuture().get(10, TimeUnit.SECONDS));
    assertInstanceOf(failureType, failure.getCause());
  }

  @Test
  void noSubscribersCompletesImmediately() throws Exception {
    publisher.empty.ignored().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  public interface Parent {
    CompletionStage<Void> deliver(Delivery delivery, int value, String[] payload);
  }

  @ComponentEvents
  public interface Events extends Parent {
  }

  @ComponentEvents
  public interface Empty {
    CompletionStage<Void> ignored();
  }

  @Component
  public static class Publisher {
    @ProducedComponentEvents
    Events events;
    @ProducedComponentEvents
    Empty empty;
  }

  public static class Delivery {
    final CountDownLatch started = new CountDownLatch(2);
    final CompletableFuture<Void> first = new CompletableFuture<>();
    final CompletableFuture<Void> second = new CompletableFuture<>();
    String mode = "success";
    volatile Thread firstThread;
    volatile Thread secondThread;
    volatile int value;
    volatile String payload;
  }

  @Component
  public static class First implements Events {
    public CompletionStage<Void> deliver(Delivery delivery, int value, String[] payload) {
      delivery.firstThread = Thread.currentThread();
      delivery.value = value;
      delivery.payload = payload[0];
      delivery.started.countDown();
      return switch (delivery.mode) {
        case "throw" -> throw new IllegalStateException("failure");
        case "failed" -> CompletableFuture.failedFuture(new IllegalArgumentException("failure"));
        case "null" -> null;
        default -> delivery.first;
      };
    }
  }

  @Component
  public static class Second implements Events {
    public CompletionStage<Void> deliver(Delivery delivery, int value, String[] payload) {
      delivery.secondThread = Thread.currentThread();
      delivery.started.countDown();
      return delivery.second;
    }
  }
}
