package com.codesimcoe.quacomo.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import jakarta.inject.Qualifier;

/**
 * Injects a generated event dispatcher. Synchronous exceptions stop delivery and propagate
 * to the caller. Asynchronous deliveries wait for all subscribers and report failures
 * through the returned stage without cancelling other deliveries.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
@Qualifier
public @interface ProducedComponentEvents {
}
