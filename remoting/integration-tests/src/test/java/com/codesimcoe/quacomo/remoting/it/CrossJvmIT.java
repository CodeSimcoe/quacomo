package com.codesimcoe.quacomo.remoting.it;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Packages the extension and runs two independent Quarkus JVMs on loopback TCP.
 */
class CrossJvmIT {
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  final ObjectMapper json = new ObjectMapper();

  @Test
  void servicesEventsAndFailuresAcrossProcesses() throws Exception {
    int a = freePort();
    int b = freePort();
    while (a == b) {
      b = freePort();
    }
    Process server = start("receiver", b, b, "secret");
    Process caller = null;
    try {
      await(server, b);
      caller = start("caller", a, b, "secret");
      await(caller, a);
      long serverPid = json.readTree(get(b, "/pid").body()).asLong();
      long callerPid = json.readTree(get(a, "/pid").body()).asLong();
      assertNotEquals(serverPid, callerPid);
      assertNotEquals(ProcessHandle.current().pid(), callerPid);
      HttpResponse<String> call = get(a, "/call");
      assertEquals(200, call.statusCode(), call.body());
      JsonNode reply = json.readTree(call.body());
      assertEquals(serverPid, reply.get("pid").asLong());
      assertEquals(42, reply.get("repeat").asInt());
      assertEquals("hello", reply.at("/message/text").asText());
      assertEquals("two", reply.at("/message/tags/1").asText());
      assertEquals(3, reply.at("/message/counts/count").asInt());
      assertEquals("SAMPLE", reply.at("/message/kind").asText());
      assertTrue(get(a, "/null").body().contains("null"));
      assertEquals(200, get(a, "/event").statusCode());
      assertEquals(1, Integer.parseInt(get(b, "/count").body()));
      assertEquals(0, Integer.parseInt(get(a, "/count").body()));
      assertEquals("hello", json.readTree(get(b, "/last").body()).get("text").asText());
      assertEquals(200, get(a, "/fanout").statusCode());
      assertEquals(3, Integer.parseInt(get(b, "/count").body()));
      assertEquals(callerPid, json.readTree(get(a, "/local").body()).get("pid").asLong());
      assertEquals(200, get(a, "/local-event").statusCode());
      assertEquals(1, Integer.parseInt(get(a, "/count").body()));
      assertEquals(3, Integer.parseInt(get(b, "/count").body()));
      assertTrue(get(a, "/failure").body().contains("failed"));
      assertTrue(get(a, "/timeout").body().contains("timed-out"));
      assertEquals(401, post(b, "wrong", "{}").statusCode());
      assertEquals(400, post(b, "secret", "{}").statusCode());
      assertEquals(404, post(b, "secret", "{\"operation\":\"java.lang.Runtime#exec\",\"arguments\":[]}").statusCode());
      stop(server);
      assertTrue(get(a, "/failure").body().contains("failed"));
    } finally {
      if (caller != null) {
        stop(caller);
      }
      stop(server);
      http.close();
    }
  }

  private Process start(String name, int port, int peer, String token) throws Exception {
    String fixture = Fixture.class.getName();
    return new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dquarkus.http.host=127.0.0.1",
        "-Dquarkus.http.port=" + port,
        "-Dquacomo.remote.server.token=" + token,
        "-Dquacomo.remote.exports=" + fixture + "$Service," + fixture + "$Events",
        "-Dquacomo.remote.timeout-ms=2000",
        "-Dquacomo.remote.destinations.peer.url=http://127.0.0.1:" + peer,
        "-Dquacomo.remote.destinations.peer.token=" + token,
        "-Dquacomo.remote.destinations.peer2.url=http://127.0.0.1:" + peer,
        "-Dquacomo.remote.destinations.peer2.token=" + token,
        "-jar",
        "target/quarkus-app/quarkus-run.jar"
    )
      .redirectErrorStream(true)
      .redirectOutput(Path.of("target", name + ".log").toFile())
      .start();
  }

  private void await(Process process, int port) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      assertTrue(process.isAlive(), "Fixture exited; inspect target/caller.log and target/receiver.log");
      try {
        if (get(port, "/pid").statusCode() == 200) {
          return;
        }
      } catch (Exception ignored) {
      }
      Thread.sleep(100);
    }
    fail("Fixture did not start");
  }

  private HttpResponse<String> get(int port, String path) throws Exception {
    return http.send(
        HttpRequest
          .newBuilder(URI.create("http://127.0.0.1:" + port + "/fixture" + path))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build(),
        HttpResponse.BodyHandlers.ofString()
    );
  }

  private HttpResponse<String> post(int port, String token, String body) throws Exception {
    return http.send(
        HttpRequest
          .newBuilder(URI.create("http://127.0.0.1:" + port + "/quacomo/remote"))
          .timeout(Duration.ofSeconds(10))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + token)
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build(),
        HttpResponse.BodyHandlers.ofString()
    );
  }

  private static int freePort() throws Exception {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void stop(Process process) throws Exception {
    if (!process.isAlive()) {
      return;
    }
    process.destroy();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
  }
}
