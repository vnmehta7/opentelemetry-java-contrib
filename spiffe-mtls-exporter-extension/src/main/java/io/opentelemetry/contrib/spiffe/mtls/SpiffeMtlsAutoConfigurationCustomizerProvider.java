/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.contrib.spiffe.mtls;

import com.google.auto.service.AutoService;
import io.opentelemetry.contrib.spiffe.mtls.SpiffeSslContextProvider.SslMaterials;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Automatically configures mTLS on all OTLP exporters using X.509 SVIDs obtained from the SPIFFE
 * Workload API.
 *
 * <p>Activated automatically when present on the classpath (via SPI). No code changes are required
 * in the instrumented application.
 *
 * <h3>Configuration</h3>
 *
 * <ul>
 *   <li>{@code OTEL_EXPORTER_SPIFFE_APP_ID} / {@code otel.exporter.spiffe.app.id} — SPIFFE ID
 *       suffix for SVID selection when the Workload API returns multiple SVIDs. Optional; when
 *       absent the first SVID is used.
 *   <li>{@code OTEL_EXPORTER_SPIFFE_SOCKET} / {@code otel.exporter.spiffe.socket} — Path to the
 *       Workload API Unix socket. Optional; falls back to {@code SPIFFE_ENDPOINT_SOCKET}.
 * </ul>
 *
 * <p>Both the shadow JAR (for use as a Java agent extension) and the plain JAR (for use as a
 * compile dependency with OTel autoconfigure) are published.
 */
@AutoService(AutoConfigurationCustomizerProvider.class)
public class SpiffeMtlsAutoConfigurationCustomizerProvider
    implements AutoConfigurationCustomizerProvider {

  private static final Logger logger =
      Logger.getLogger(SpiffeMtlsAutoConfigurationCustomizerProvider.class.getName());

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    // Single provider shared across all exporter customizations — one Workload API connection.
    LazySpiffeSslContextProviderHolder holder = new LazySpiffeSslContextProviderHolder();

    autoConfiguration
        .addSpanExporterCustomizer(
            (exporter, config) -> customizeSpanExporter(exporter, holder, config))
        .addMetricExporterCustomizer(
            (exporter, config) -> customizeMetricExporter(exporter, holder, config))
        .addLogRecordExporterCustomizer(
            (exporter, config) -> customizeLogRecordExporter(exporter, holder, config));
  }

  @Override
  public int order() {
    // Run after all other customizers so we wrap the final configured exporter.
    return Integer.MAX_VALUE - 1;
  }

  static SpanExporter customizeSpanExporter(
      SpanExporter exporter, LazySpiffeSslContextProviderHolder holder, ConfigProperties config) {
    SslMaterials ssl = getSslMaterialsSafely(holder, config);
    if (ssl == null) {
      return exporter;
    }
    if (exporter instanceof OtlpGrpcSpanExporter) {
      return ((OtlpGrpcSpanExporter) exporter)
          .toBuilder().setSslContext(ssl.sslContext, ssl.trustManager).build();
    }
    if (exporter instanceof OtlpHttpSpanExporter) {
      return ((OtlpHttpSpanExporter) exporter)
          .toBuilder().setSslContext(ssl.sslContext, ssl.trustManager).build();
    }
    return exporter;
  }

  static MetricExporter customizeMetricExporter(
      MetricExporter exporter, LazySpiffeSslContextProviderHolder holder, ConfigProperties config) {
    SslMaterials ssl = getSslMaterialsSafely(holder, config);
    if (ssl == null) {
      return exporter;
    }
    if (exporter instanceof OtlpGrpcMetricExporter) {
      return ((OtlpGrpcMetricExporter) exporter)
          .toBuilder().setSslContext(ssl.sslContext, ssl.trustManager).build();
    }
    if (exporter instanceof OtlpHttpMetricExporter) {
      return ((OtlpHttpMetricExporter) exporter)
          .toBuilder().setSslContext(ssl.sslContext, ssl.trustManager).build();
    }
    return exporter;
  }

  static LogRecordExporter customizeLogRecordExporter(
      LogRecordExporter exporter,
      LazySpiffeSslContextProviderHolder holder,
      ConfigProperties config) {
    SslMaterials ssl = getSslMaterialsSafely(holder, config);
    if (ssl == null) {
      return exporter;
    }
    if (exporter instanceof OtlpGrpcLogRecordExporter) {
      return ((OtlpGrpcLogRecordExporter) exporter)
          .toBuilder().setSslContext(ssl.sslContext, ssl.trustManager).build();
    }
    if (exporter instanceof OtlpHttpLogRecordExporter) {
      return ((OtlpHttpLogRecordExporter) exporter)
          .toBuilder().setSslContext(ssl.sslContext, ssl.trustManager).build();
    }
    return exporter;
  }

  /**
   * Returns the {@link SslMaterials}, or {@code null} and logs a warning if the Workload API is
   * unavailable. Returning {@code null} preserves the original exporter unchanged so the
   * application continues to start even without a SPIFFE agent present.
   */
  @Nullable
  private static SslMaterials getSslMaterialsSafely(
      LazySpiffeSslContextProviderHolder holder, ConfigProperties config) {
    try {
      return holder.get(config).get();
    } catch (ConfigurationException e) {
      logger.log(
          Level.WARNING,
          "SPIFFE mTLS extension: could not obtain SSLContext from Workload API."
              + " Exporter will use its default TLS configuration. Cause: {0}",
          e.getMessage());
      return null;
    }
  }

  /**
   * Lazily initialises and caches the {@link SpiffeSslContextProvider}. Thread-safe. A single
   * {@link SpiffeSslContextProvider} is created per JVM startup — it holds one Workload API
   * streaming subscription and handles SVID rotation internally.
   */
  static class LazySpiffeSslContextProviderHolder {

    @Nullable private volatile SpiffeSslContextProvider provider;

    SpiffeSslContextProvider get(ConfigProperties config) {
      if (provider == null) {
        synchronized (this) {
          if (provider == null) {
            provider = SpiffeSslContextProvider.create(config);
            Runtime.getRuntime().addShutdownHook(new Thread(provider::close));
          }
        }
      }
      return provider;
    }
  }
}
