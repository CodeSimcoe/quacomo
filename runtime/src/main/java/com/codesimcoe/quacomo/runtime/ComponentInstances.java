package com.codesimcoe.quacomo.runtime;

import io.quarkus.arc.Arc;

/**
 * Internal entry point used by generated event dispatchers.
 */
public final class ComponentInstances {
  private ComponentInstances() {
  }

  public static <T> T get(Class<T> type) {
    return Arc.container().instance(type).get();
  }
}
