package com.codesimcoe.quacomo.runtime;

import java.lang.annotation.*;

/**
 * Allows an asynchronous service or event contract to cross a JVM boundary.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Remotable {
}
