package com.codesimcoe.quacomo.remoting.test;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import io.quarkus.test.QuarkusExtensionTest;
import com.codesimcoe.quacomo.runtime.*;
import static org.junit.jupiter.api.Assertions.*;

class InvalidRemoteContractTest {
  @RegisterExtension
  static final QuarkusExtensionTest app = new QuarkusExtensionTest()
    .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClass(Service.class))
    .assertException(t -> {
      while (t.getCause() != null) {
        t = t.getCause();
      }
      assertTrue(t.getMessage().contains("Remote methods must return CompletionStage<T>"));
    });

  @Test
  void rejectsBlockingRemoteMethod() {}

  @Remotable
  @ComponentServices
  public interface Service {
    String ping();
  }
}
