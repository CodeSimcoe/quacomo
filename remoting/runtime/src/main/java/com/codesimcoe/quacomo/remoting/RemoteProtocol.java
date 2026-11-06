package com.codesimcoe.quacomo.remoting;

import com.fasterxml.jackson.databind.JsonNode;

public final class RemoteProtocol {
  private RemoteProtocol() {
  }

  public record Request(String operation, JsonNode[] arguments) {}

  public record Reply(JsonNode result, String error, String message) {}
}
