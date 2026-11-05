package com.codesimcoe.quacomo.test;

import com.codesimcoe.quacomo.runtime.*;
import io.quarkus.test.QuarkusExtensionTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvalidEventsTest {
  @RegisterExtension
  static final QuarkusExtensionTest app = new QuarkusExtensionTest()
    .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClasses(Events.class))
    .assertException(t -> {
      while (t.getCause() != null) {
        t = t.getCause();
      }
      assertTrue(t.getMessage().contains("Event methods must return void or CompletionStage<Void>"));
    });

  @Test
  void rejectsEventReturnValue() {}

  @ComponentEvents
  public interface Events {
    String invalid();
  }
}
