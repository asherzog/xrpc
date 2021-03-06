package com.nordstrom.xrpc.server;

import static com.typesafe.config.ConfigValueFactory.fromAnyRef;
import static com.typesafe.config.ConfigValueFactory.fromIterable;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN;
import static io.netty.handler.codec.http.HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.collect.ImmutableList;
import com.nordstrom.xrpc.server.http.Recipes;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;
import com.xjeffrose.xio.test.OkHttpUnsafe;
import java.io.IOException;
import java.util.Objects;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CorsTest {

  private OkHttpClient http11Client;
  private OkHttpClient http2Client;
  private Config config;
  private Server server;
  private String endpoint;

  @BeforeEach
  void beforeEach() throws Exception {
    config = ConfigFactory.load("test.conf").getConfig("xrpc");
    http11Client = OkHttpUnsafe.getUnsafeClient();
    http2Client = OkHttpUnsafe.getUnsafeClient(Protocol.HTTP_2, Protocol.HTTP_1_1);
  }

  @AfterEach
  void afterEach() {
    server.shutdown();
  }

  @Test
  void testCorsEnabledWithShortCircuit() throws IOException {
    addConfigValue("cors.enable", fromAnyRef(true));
    addConfigValue("cors.short_circuit", fromAnyRef(true));
    init();
    start();

    Request request =
        new Request.Builder()
            .url(endpoint + "/people")
            .method("OPTIONS", null)
            .addHeader("Origin", "foo.bar")
            .build();
    Response response = http11Client.newCall(request).execute();
    assertEquals(403, response.code());
    response = http2Client.newCall(request).execute();
    assertEquals(403, response.code());
  }

  @Test
  void testCorsEnabledPreFlight() throws IOException {
    addConfigValue("cors.enable", fromAnyRef(true));
    addConfigValue("cors.allowed_origins", fromIterable(ImmutableList.of("foo.bar")));
    init();
    start();

    Request request =
        new Request.Builder()
            .url(endpoint + "/people")
            .method("OPTIONS", null)
            .addHeader("Origin", "foo.bar")
            .addHeader(ACCESS_CONTROL_REQUEST_METHOD.toString(), "GET")
            .build();
    Response response = http11Client.newCall(request).execute();
    assertEquals(200, response.code());
    assertEquals("foo.bar", response.header(ACCESS_CONTROL_ALLOW_ORIGIN.toString()));
    response = http2Client.newCall(request).execute();
    assertEquals(200, response.code());
    assertEquals("foo.bar", response.header(ACCESS_CONTROL_ALLOW_ORIGIN.toString()));
  }

  @Test
  void testCorsEnabledInFlight() throws IOException {
    addConfigValue("cors.enable", fromAnyRef(true));
    addConfigValue("cors.allowed_origins", fromIterable(ImmutableList.of("foo.bar")));
    init();
    server.get("/people", req -> Recipes.newResponseOk("hello foo.bar"));
    start();

    Request request =
        new Request.Builder()
            .url(endpoint + "/people")
            .get()
            .addHeader("Origin", "foo.bar")
            .addHeader(ACCESS_CONTROL_REQUEST_METHOD.toString(), "GET")
            .build();
    Response response = http11Client.newCall(request).execute();
    assertEquals(200, response.code());
    assertEquals("foo.bar", response.header(ACCESS_CONTROL_ALLOW_ORIGIN.toString()));
    assertEquals("hello foo.bar", Objects.requireNonNull(response.body()).string());
    response = http2Client.newCall(request).execute();
    assertEquals(200, response.code());
    assertEquals("foo.bar", response.header(ACCESS_CONTROL_ALLOW_ORIGIN.toString()));
    assertEquals("hello foo.bar", Objects.requireNonNull(response.body()).string());
  }

  @Test
  void testCorsEnabledPreFlightWithMethods() throws IOException {
    addConfigValue("cors.enable", fromAnyRef(true));
    addConfigValue("cors.allowed_origins", fromIterable(ImmutableList.of("foo.bar")));
    addConfigValue("cors.allowed_methods", fromIterable(ImmutableList.of("GET")));
    init();
    start();

    Request request =
        new Request.Builder()
            .url(endpoint + "/people")
            .method("OPTIONS", null)
            .addHeader("Origin", "foo.bar")
            .addHeader(ACCESS_CONTROL_REQUEST_METHOD.toString(), "GET")
            .build();
    Response response = http11Client.newCall(request).execute();
    assertEquals("GET", response.header(ACCESS_CONTROL_ALLOW_METHODS.toString()));
    response = http2Client.newCall(request).execute();
    assertEquals("GET", response.header(ACCESS_CONTROL_ALLOW_METHODS.toString()));
  }

  @Test
  void testCorsEnabledPreFlightWithHeaders() throws IOException {
    addConfigValue("cors.enable", fromAnyRef(true));
    addConfigValue("cors.allowed_origins", fromIterable(ImmutableList.of("foo.bar")));
    addConfigValue("cors.allowed_headers", fromIterable(ImmutableList.of("foo-header")));
    init();
    start();

    Request request =
        new Request.Builder()
            .url(endpoint + "/people")
            .method("OPTIONS", null)
            .addHeader("Origin", "foo.bar")
            .addHeader(ACCESS_CONTROL_REQUEST_METHOD.toString(), "GET")
            .build();
    Response response = http11Client.newCall(request).execute();
    assertEquals("foo-header", response.header(ACCESS_CONTROL_ALLOW_HEADERS.toString()));
    response = http2Client.newCall(request).execute();
    assertEquals("foo-header", response.header(ACCESS_CONTROL_ALLOW_HEADERS.toString()));
  }

  private void addConfigValue(String path, ConfigValue value) {
    config = config.withValue(path, value);
  }

  private void init() {
    server = new Server(config);
  }

  private void start() throws IOException {
    server.listenAndServe();
    endpoint = server.localEndpoint();
  }
}
