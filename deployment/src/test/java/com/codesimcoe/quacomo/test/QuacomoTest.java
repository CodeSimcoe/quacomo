package com.codesimcoe.quacomo.test;

import java.util.ArrayList;
import java.util.List;
import jakarta.inject.Inject;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.arc.ClientProxy;
import com.codesimcoe.quacomo.runtime.*;
import static org.junit.jupiter.api.Assertions.*;

public class QuacomoTest {
    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClasses(
                    Greeting.class, ParentEvents.class, Events.class, EmptyEvents.class,
                    Publisher.class, Subscriber.class, SecondSubscriber.class));

    @Inject Publisher publisher;
    @Inject Subscriber subscriber;
    @Inject SecondSubscriber second;

    @Test
    void servicesAndGeneratedFanOut() {
        subscriber.values.clear();
        second.values.clear();
        assertSame(publisher, subscriber.service);
        assertFalse(publisher instanceof ClientProxy);
        assertFalse(publisher.events instanceof ClientProxy);
        publisher.events.message("hello", 42);
        assertEquals(List.of("hello42:service"), subscriber.values);
        assertEquals(List.of("hello42"), second.values);
        publisher.events.inherited(7L);
        assertEquals(7L, subscriber.last);
        publisher.empty.ignored();
    }

    @Test
    void subscriberExceptionPropagates() {
        assertThrows(IllegalStateException.class, () -> publisher.events.fail());
    }

    @ComponentServices
    public interface Greeting { String greet(); }
    public interface ParentEvents { void inherited(long value); }
    @ComponentEvents
    public interface Events extends ParentEvents {
        void message(String message, int value);
        void fail();
    }
    @ComponentEvents
    public interface EmptyEvents { void ignored(); }

    @Component
    public static class Publisher implements Greeting {
        @ProducedComponentEvents Events events;
        @ProducedComponentEvents EmptyEvents empty;
        public String greet() { return "service"; }
    }

    @Component
    public static class Subscriber implements Events {
        @UsedComponentServices Greeting service;
        final List<String> values = new ArrayList<>();
        long last;
        public void message(String message, int value) { values.add(message + value + ":" + service.greet()); }
        public void inherited(long value) { last = value; }
        public void fail() { throw new IllegalStateException("subscriber failure"); }
    }

    @Component
    public static class SecondSubscriber implements Events {
        final List<String> values = new ArrayList<>();
        public void message(String message, int value) { values.add(message + value); }
        public void inherited(long value) { }
        public void fail() { }
    }
}
