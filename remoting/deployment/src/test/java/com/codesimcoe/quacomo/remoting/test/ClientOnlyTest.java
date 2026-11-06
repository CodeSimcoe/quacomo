package com.codesimcoe.quacomo.remoting.test;

import java.util.concurrent.CompletionStage;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.arc.ClientProxy;
import com.codesimcoe.quacomo.runtime.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientOnlyTest {
  @RegisterExtension
  static final QuarkusExtensionTest app = new QuarkusExtensionTest()
    .setArchiveProducer(() -> ShrinkWrap
      .create(JavaArchive.class)
      .addClasses(Service.class, Events.class, Consumer.class));
  @Inject
  Consumer consumer;

  @Test
  void remoteBindingsNeedNoLocalProviderOrProxy() {
    assertNotNull(consumer.service);
    assertNotNull(consumer.events);
    assertFalse(consumer.service instanceof ClientProxy);
    assertFalse(consumer.events instanceof ClientProxy);
    assertThrows(java.util.concurrent.CompletionException.class, () -> consumer.service
      .ping()
      .toCompletableFuture()
      .join());
  }

  @Remotable
  @ComponentServices
  public interface Service {
    CompletionStage<String> ping();
  }

  @Remotable
  @ComponentEvents
  public interface Events {
    CompletionStage<Void> ping();
  }

  @Component
  public static class Consumer {
    @UsedComponentServices(remote = "server")
    Service service;
    @ProducedComponentEvents(remote = "server")
    Events events;
  }
}
