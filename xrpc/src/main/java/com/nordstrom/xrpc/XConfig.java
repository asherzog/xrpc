/*
 * Copyright 2018 Nordstrom, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nordstrom.xrpc;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;
import com.google.common.io.CharSink;
import com.google.common.io.Files;
import com.nordstrom.xrpc.server.tls.Tls;
import com.nordstrom.xrpc.server.tls.X509Certificate;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.xjeffrose.xio.SSL.TlsConfig;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.util.internal.PlatformDependent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import sun.security.provider.X509Factory;
import sun.security.x509.X509CertImpl;

/**
 * A configuration object for the xrpc framework. This can be left with defaults, or provided with a
 * configuration object for overrides.
 *
 * <p>See <a
 * href="https://github.com/Nordstrom/xrpc/blob/master/src/main/resources/com/nordstrom/xrpc/xrpc.conf">the
 * embedded config</a> for defaults and documentation.
 */
@Accessors(fluent = true)
@Getter
@Slf4j
public class XConfig {
  private final int readerIdleTimeout;
  private final int writerIdleTimeout;
  private final int allIdleTimeout;
  private final String workerNameFormat;
  private final int bossThreadCount;
  private final int workerThreadCount;
  private final int asyncHealthCheckThreadCount;
  private final int maxPayloadBytes;
  private final int maxConnections;
  private final double softReqPerSec;
  private final double hardReqPerSec;
  private final int port;
  private final double globalSoftReqPerSec;
  private final double globalHardReqPerSec;
  private final ImmutableSet<String> ipBlackList;
  private final ImmutableSet<String> ipWhiteList;
  private final boolean slf4jReporter;
  private final boolean jmxReporter;
  private final boolean consoleReporter;
  private final int slf4jReporterPollingRate;
  private final int consoleReporterPollingRate;
  private final boolean adminRoutesEnableInfo;
  private final boolean adminRoutesEnableUnsafe;
  private final String defaultContentType;
  private final TlsConfig tlsConfig;

  private final Map<String, List<Double>> clientRateLimitOverride =
      PlatformDependent.newConcurrentHashMap();
  private final boolean enableWhiteList;
  private final boolean enableBlackList;
  private final int rateLimiterPoolSize;

  private final CorsConfig corsConfig;

  /**
   * Construct a config object using the default configuration values <a
   * href="https://github.com/Nordstrom/xrpc/blob/master/src/main/resources/com/nordstrom/xrpc/xrpc.conf">here</a>.
   */
  public XConfig() {
    this(ConfigFactory.empty());
  }

  /**
   * Construct a config object using the provided configuration, falling back on the default
   * configuration values <a
   * href="https://github.com/Nordstrom/xrpc/blob/master/src/main/resources/com/nordstrom/xrpc/xrpc.conf">here</a>.
   *
   * @throws RuntimeException if there is an error reading one of path_to_cert or path_to_key
   */
  public XConfig(Config configOverrides) {
    Config defaultConfig = ConfigFactory.parseResources(this.getClass(), "xrpc.conf");
    Config config = configOverrides.withFallback(defaultConfig);

    readerIdleTimeout = config.getInt("reader_idle_timeout_seconds");
    writerIdleTimeout = config.getInt("writer_idle_timeout_seconds");
    allIdleTimeout = config.getInt("all_idle_timeout_seconds");
    workerNameFormat = config.getString("worker_name_format");
    bossThreadCount = config.getInt("boss_thread_count");
    workerThreadCount = config.getInt("worker_thread_count");
    asyncHealthCheckThreadCount = config.getInt("async_health_check_thread_count");
    long maxPayloadBytesLong = config.getBytes("max_payload_bytes");
    Preconditions.checkArgument(
        maxPayloadBytesLong <= Integer.MAX_VALUE,
        String.format(
            "value %d for max_payload_bytes must be less than or equal to %d",
            maxPayloadBytesLong, Integer.MAX_VALUE));
    maxPayloadBytes = (int) maxPayloadBytesLong;
    maxConnections = config.getInt("max_connections");
    rateLimiterPoolSize = config.getInt("rate_limiter_pool_size");
    softReqPerSec = config.getDouble("soft_req_per_sec");
    hardReqPerSec = config.getDouble("hard_req_per_sec");
    adminRoutesEnableInfo = config.getBoolean("admin_routes.enable_info");
    adminRoutesEnableUnsafe = config.getBoolean("admin_routes.enable_unsafe");
    defaultContentType = config.getString("default_content_type");
    globalSoftReqPerSec = config.getDouble("global_soft_req_per_sec");
    globalHardReqPerSec = config.getDouble("global_hard_req_per_sec");
    port = config.getInt("server.port");
    slf4jReporter = config.getBoolean("slf4j_reporter");
    jmxReporter = config.getBoolean("jmx_reporter");
    consoleReporter = config.getBoolean("console_reporter");
    slf4jReporterPollingRate = config.getInt("slf4j_reporter_polling_rate");
    consoleReporterPollingRate = config.getInt("console_reporter_polling_rate");
    enableWhiteList = config.getBoolean("enable_white_list");
    enableBlackList = config.getBoolean("enable_black_list");

    ipBlackList =
        ImmutableSet.<String>builder().addAll(config.getStringList("ip_black_list")).build();
    ipWhiteList =
        ImmutableSet.<String>builder().addAll(config.getStringList("ip_white_list")).build();

    corsConfig = buildCorsConfig(config.getConfig("cors"));

    tlsConfig = generateTlsConfig(config);

    populateClientOverrideList(config.getObjectList("req_per_second_override"));
  }

  private TlsConfig generateTlsConfig(Config config) {
    Config tls = config.getConfig("tls");
    if (!tls.hasPath("privateKeyPath") || !tls.hasPath("x509CertPath")) {
      log.info(
          "Private key path or x509 certificate path not defined. "
              + "Generating self signed certificate.");
      X509Certificate selfSignedCertificate = Tls.createSelfSigned();
      try {
        String certificate = generateCertificateString(selfSignedCertificate.cert());
        String certificateFilePath = writeToDiskReturnAbsoluterPath(certificate, "certificate.crt");
        String privateKey = generatePrivateKeyString(selfSignedCertificate.key());
        String privateKeyFilePath = writeToDiskReturnAbsoluterPath(privateKey, "key.pem");
        Config configWithCertPaths =
            ConfigFactory.parseMap(
                ImmutableMap.of(
                    "privateKeyPath", privateKeyFilePath, "x509CertPath", certificateFilePath));
        tls = configWithCertPaths.withFallback(tls);
      } catch (CertificateEncodingException e) {
        log.error("Failed when writing certificate or private key to disc", e);
        throw new RuntimeException(e);
      }
    }
    return new TlsConfig(tls);
  }

  private String generatePrivateKeyString(PrivateKey key) {
    return "-----BEGIN PRIVATE KEY-----\n"
        + BaseEncoding.base64().encode(key.getEncoded())
        + "-----END PRIVATE KEY-----";
  }

  private String generateCertificateString(X509CertImpl certificate)
      throws CertificateEncodingException {
    return X509Factory.BEGIN_CERT
        + System.lineSeparator()
        + BaseEncoding.base64().encode(certificate.getEncoded())
        + X509Factory.END_CERT;
  }

  private String writeToDiskReturnAbsoluterPath(String content, String filePath) {
    try {
      File outputFile = new File(filePath);
      CharSink sink = Files.asCharSink(outputFile, StandardCharsets.UTF_8);
      sink.write(content);
      return outputFile.getAbsolutePath();
    } catch (IOException e) {
      log.error("Failure whilst attempting to write to disk", e);
      throw new RuntimeException(e);
    }
  }

  private CorsConfig buildCorsConfig(Config config) {
    if (!config.getBoolean("enable")) {
      return CorsConfigBuilder.forAnyOrigin().disable().build();
    }

    CorsConfigBuilder builder =
        CorsConfigBuilder.forOrigins(getStrings(config, "allowed_origins"))
            .allowedRequestHeaders(getStrings(config, "allowed_headers"))
            .allowedRequestMethods(getHttpMethods(config, "allowed_methods"));
    if (config.getBoolean("allow_credentials")) {
      builder.allowCredentials();
    }
    if (config.getBoolean("short_circuit")) {
      builder.shortCircuit();
    }
    return builder.build();
  }

  private String[] getStrings(Config config, String key) {
    if (!config.hasPath(key)) {
      return new String[0];
    }
    return config.getStringList(key).toArray(new String[0]);
  }

  private HttpMethod[] getHttpMethods(Config config, String key) {
    if (!config.hasPath(key)) {
      return new HttpMethod[0];
    }
    return config.getStringList(key).stream().map(HttpMethod::valueOf).toArray(HttpMethod[]::new);
  }

  private void populateClientOverrideList(List<? extends ConfigObject> reqPerSecondOverride) {
    reqPerSecondOverride.forEach(
        xs ->
            xs.forEach(
                (key, value) -> {
                  List<String> valString = Arrays.asList(value.unwrapped().toString().split(":"));
                  List<Double> val = new ArrayList<>();
                  valString.forEach(v -> val.add(Double.parseDouble(v)));
                  clientRateLimitOverride.put(key, val);
                }));
  }

  public Map<String, List<Double>> getClientRateLimitOverride() {
    return clientRateLimitOverride;
  }
}
