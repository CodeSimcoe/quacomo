package com.codesimcoe.quacomo.test;

import java.util.concurrent.CompletionStage;
import com.codesimcoe.quacomo.runtime.ComponentEvents;
import io.quarkus.test.QuarkusExtensionTest;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvalidAsyncEventsTest {
    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClasses(Events.class))
            .assertException(t -> {
                while (t.getCause() != null) t = t.getCause();
                assertTrue(t.getMessage().contains("Event methods must return void or CompletionStage<Void>"));
            });
    @Test void rejectsAsyncReturnValue() { }
    @ComponentEvents public interface Events { CompletionStage<String> invalid(); }
}
