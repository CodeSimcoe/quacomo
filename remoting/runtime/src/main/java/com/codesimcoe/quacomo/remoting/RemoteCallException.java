package com.codesimcoe.quacomo.remoting;

/**
 * A transport, protocol or remote execution failure. No remote Throwable is deserialized.
 */
public class RemoteCallException extends RuntimeException {
  private final String code;

  public RemoteCallException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
