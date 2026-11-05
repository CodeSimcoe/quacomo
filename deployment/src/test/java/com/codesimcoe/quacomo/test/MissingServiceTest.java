package com.codesimcoe.quacomo.test;

import com.codesimcoe.quacomo.runtime.*;
import io.quarkus.test.QuarkusExtensionTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingServiceTest {
  @RegisterExtension
  static final QuarkusExtensionTest app = new QuarkusExtensionTest()
    .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClasses(Service.class, Consumer.class))
    .assertException(t -> {
      while (t.getCause() != null) {
        t = t.getCause();
      }
      assertTrue(t.getMessage().contains("Expected exactly one component providing"));
    });

  @Test
  void rejectsMissingService() {}

  @ComponentServices
  public interface Service {
    void call();
  }

  @Component
  public static class Consumer {
    @UsedComponentServices
    Service service;
  }
}
