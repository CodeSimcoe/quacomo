package com.codesimcoe.quacomo.remoting.test;

import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import io.quarkus.test.QuarkusExtensionTest;
import com.codesimcoe.quacomo.runtime.*;
import static org.junit.jupiter.api.Assertions.*;

class InvalidRemoteDtoTest {
  @RegisterExtension
  static final QuarkusExtensionTest app = new QuarkusExtensionTest()
    .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClass(Service.class))
    .assertException(t -> {
      while (t.getCause() != null) {
        t = t.getCause();
      }
      assertTrue(t.getMessage().contains("Remote DTO must be an indexed public record or enum"));
    });

  @Test
  void rejectsArbitraryObjectGraphs() {}

  @Remotable
  @ComponentServices
  public interface Service {
    CompletionStage<String> ping(Object unsafe);
  }
}
