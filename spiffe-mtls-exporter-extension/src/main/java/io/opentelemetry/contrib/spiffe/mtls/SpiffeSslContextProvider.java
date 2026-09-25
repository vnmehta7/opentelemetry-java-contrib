/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.contrib.spiffe.mtls;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.spiffe.exception.SocketEndpointAddressException;
import io.spiffe.exception.X509SourceException;
import io.spiffe.provider.SpiffeSslContextFactory;
import io.spiffe.provider.SpiffeTrustManager;
import io.spiffe.workloadapi.DefaultX509Source;
import java.io.Closeable;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

/**
 * Provides a live {@link SSLContext} backed by the SPIFFE Workload API.
 *
 * <p>Creates a {@link DefaultX509Source} on construction, which establishes a streaming gRPC
 * subscription to the Workload API and keeps the in-memory X.509 SVID and trust bundle up-to-date
 * automatically. Calls to {@link #get()} return an {@link SSLContext} built from the latest SVID —
 * cert rotation is transparent and requires no restart.
 *
 * <p>The SVID to use is selected by:
 *
 * <ol>
 *   <li>Matching the {@code OTEL_EXPORTER_SPIFFE_APP_ID} config value as a substring of the SVID's
 *       SPIFFE ID URI, if the option is configured.
 *   <li>Using the first SVID returned by the Workload API when the option is absent (safe default
 *       for single-identity workloads).
 * </ol>
 *
 * <p>If the SPIFFE agent socket is not yet available at startup, the provider retries the Workload
 * API connection every {@link #retryDelayMs} milliseconds until either the first SVID is received
 * or the timeout configured via {@code OTEL_EXPORTER_SPIFFE_STARTUP_TIMEOUT} elapses (default 120
 * seconds). This covers the race between the application starting and the sidecar becoming ready.
 */
final class SpiffeSslContextProvider implements Closeable {

  private static final Logger logger = Logger.getLogger(SpiffeSslContextProvider.class.getName());

  /** Delay between startup retry attempts. Package-private to allow test overrides. */
  static volatile long retryDelayMs = 3_000L;

  private final DefaultX509Source x509Source;

  private SpiffeSslContextProvider(DefaultX509Source x509Source) {
    this.x509Source = x509Source;
  }

  /**
   * Factory interface for creating a {@link DefaultX509Source}. Package-private to allow injection
   * in tests without hitting a real Workload API socket.
   */
  @FunctionalInterface
  interface X509SourceFactory {
    DefaultX509Source create() throws X509SourceException, SocketEndpointAddressException;
  }

  /**
   * Creates a {@link SpiffeSslContextProvider} backed by the Workload API.
   *
   * <p>Retries the Workload API connection on transient failures ({@link X509SourceException}) for
   * up to {@code OTEL_EXPORTER_SPIFFE_STARTUP_TIMEOUT} seconds (default 120). Does not retry on
   * invalid socket addresses ({@link SocketEndpointAddressException}).
   *
   * @throws ConfigurationException if the socket address is invalid, the startup timeout is
   *     exceeded, or the thread is interrupted
   */
  static SpiffeSslContextProvider create(ConfigProperties config) {
    DefaultX509Source.X509SourceOptions options = buildOptions(config);
    return createWithRetry(config, () -> DefaultX509Source.newSource(options));
  }

  /**
   * Creates a {@link SpiffeSslContextProvider} using the supplied factory, retrying on transient
   * {@link X509SourceException} failures. Package-private to allow unit testing with a fake
   * factory.
   */
  static SpiffeSslContextProvider createWithRetry(
      ConfigProperties config, X509SourceFactory factory) {
    long timeoutSeconds =
        SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_STARTUP_TIMEOUT.getConfiguredValueAsLong(
            config, 120L);
    long deadlineMs = System.currentTimeMillis() + timeoutSeconds * 1_000L;
    int attempt = 0;

    while (true) {
      attempt++;
      try {
        DefaultX509Source x509Source = factory.create();
        logger.log(
            Level.INFO,
            "SPIFFE mTLS extension: X.509 source initialised (attempt {0}). SVID: {1}",
            new Object[] {attempt, x509Source.getX509Svid().getSpiffeId()});
        return new SpiffeSslContextProvider(x509Source);
      } catch (X509SourceException e) {
        long remaining = deadlineMs - System.currentTimeMillis();
        if (remaining <= 0) {
          throw new ConfigurationException(
              "SPIFFE mTLS extension: Workload API not available after "
                  + timeoutSeconds
                  + "s. Ensure the SPIFFE agent is running and the socket path is correct.",
              e);
        }
        long sleepMs = Math.min(retryDelayMs, remaining);
        logger.log(
            Level.WARNING,
            "SPIFFE mTLS extension: Workload API not ready (attempt {0})"
                + " — retrying in {1}ms ({2}s remaining): {3}",
            new Object[] {attempt, sleepMs, remaining / 1_000, e.getMessage()});
        try {
          Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new ConfigurationException(
              "SPIFFE mTLS extension: interrupted waiting for Workload API to become available.",
              ie);
        }
      } catch (SocketEndpointAddressException e) {
        throw new ConfigurationException(
            "SPIFFE mTLS extension: invalid Workload API socket address. "
                + "Ensure OTEL_EXPORTER_SPIFFE_SOCKET or SPIFFE_ENDPOINT_SOCKET is set correctly.",
            e);
      }
    }
  }

  /** Holds both the {@link SSLContext} and the {@link X509TrustManager} built from the SVID. */
  static final class SslMaterials {
    final SSLContext sslContext;
    final X509TrustManager trustManager;

    SslMaterials(SSLContext sslContext, X509TrustManager trustManager) {
      this.sslContext = sslContext;
      this.trustManager = trustManager;
    }
  }

  /**
   * Returns the {@link SslMaterials} (SSLContext + X509TrustManager) built from the current SVID
   * and trust bundle. Both are required by the OTLP exporter builder's {@code setSslContext} API.
   *
   * @throws ConfigurationException if the SSLContext cannot be built
   */
  SslMaterials get() {
    try {
      X509TrustManager trustManager = new SpiffeTrustManager(x509Source);
      SpiffeSslContextFactory.SslContextOptions options =
          SpiffeSslContextFactory.SslContextOptions.builder()
              .x509Source(x509Source)
              .acceptAnySpiffeId()
              .build();
      SSLContext sslContext = SpiffeSslContextFactory.getSslContext(options);
      return new SslMaterials(sslContext, trustManager);
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      throw new ConfigurationException(
          "SPIFFE mTLS extension: failed to build SSLContext from SVID.", e);
    }
  }

  /** Closes the underlying {@link DefaultX509Source} and drops the Workload API connection. */
  @Override
  public void close() {
    try {
      x509Source.close();
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "SPIFFE mTLS extension: error closing X.509 source.", e);
    }
  }

  private static DefaultX509Source.X509SourceOptions buildOptions(ConfigProperties config) {
    Optional<String> socketPath =
        SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_SOCKET.getConfiguredValueAsOptional(config);
    Optional<String> appId =
        SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_APP_ID.getConfiguredValueAsOptional(config);

    DefaultX509Source.X509SourceOptions.X509SourceOptionsBuilder optionsBuilder =
        DefaultX509Source.X509SourceOptions.builder();

    socketPath.ifPresent(optionsBuilder::spiffeSocketPath);

    appId.ifPresent(
        id ->
            optionsBuilder.svidPicker(
                svids ->
                    svids.stream()
                        .filter(svid -> svid.getSpiffeId().toString().contains(id))
                        .findFirst()
                        .orElseThrow(
                            () ->
                                new ConfigurationException(
                                    "SPIFFE mTLS extension: no SVID found matching app ID '"
                                        + id
                                        + "'. Available SVIDs: "
                                        + svids.stream()
                                            .map(s -> s.getSpiffeId().toString())
                                            .reduce((a, b) -> a + ", " + b)
                                            .orElse("(none)")))));

    return optionsBuilder.build();
  }
}
