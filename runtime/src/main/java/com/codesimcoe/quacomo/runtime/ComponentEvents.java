package com.codesimcoe.quacomo.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public event interface implemented by subscribing components.
 * Methods returning void run synchronously; methods returning CompletionStage&lt;Void&gt;
 * run on the Quarkus managed executor and complete after all subscriber stages finish.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ComponentEvents {
}
