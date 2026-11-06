package com.codesimcoe.quacomo.runtime;

import java.lang.annotation.*;
import jakarta.inject.Qualifier;

/**
 * Internal qualifier separating generated remote bindings from local components.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface RemoteBinding {
  String value();
}
