package com.codesimcoe.quacomo.remoting;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletionStage;

/**
 * Implemented at build time, with a fixed operation and argument types.
 */
public interface RemoteOperation {
  String key();

  String contract();

  int arity();

  CompletionStage<?> invoke(JsonNode[] arguments);
}
