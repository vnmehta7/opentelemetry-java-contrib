/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.contrib.spiffe.mtls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.opentelemetry.contrib.spiffe.mtls.SpiffeSslContextProvider.SslMaterials;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collections;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SpiffeMtlsAutoConfigurationCustomizerProviderTest {

  @Mock private SpiffeSslContextProvider sslContextProvider;
  @Mock private SSLContext sslContext;
  @Mock private X509TrustManager trustManager;

  private SpiffeMtlsAutoConfigurationCustomizerProvider.LazySpiffeSslContextProviderHolder holder;
  private ConfigProperties emptyConfig;

  @BeforeEach
  void setUp() {
    emptyConfig = DefaultConfigProperties.createFromMap(Collections.emptyMap());
    // Pre-populate the holder with our mock provider so tests don't hit the Workload API
    holder =
        new SpiffeMtlsAutoConfigurationCustomizerProvider.LazySpiffeSslContextProviderHolder() {
          @Override
          SpiffeSslContextProvider get(ConfigProperties config) {
            return sslContextProvider;
          }
        };
    lenient().when(sslContextProvider.get()).thenReturn(new SslMaterials(sslContext, trustManager));
  }

  // ── Span exporters ──────────────────────────────────────────────────────────

  @Test
  void customizesGrpcSpanExporterWithSslContext() {
    OtlpGrpcSpanExporter original = OtlpGrpcSpanExporter.builder().build();

    SpanExporter result =
        SpiffeMtlsAutoConfigurationCustomizerProvider.customizeSpanExporter(
            original, holder, emptyConfig);

    assertThat(result).isInstanceOf(OtlpGrpcSpanExporter.class).isNotSameAs(original);
    verify(sslContextProvider).get();
  }

  @Test
  void customizesHttpSpanExporterWithSslContext() {
    OtlpHttpSpanExporter original = OtlpHttpSpanExporter.builder().build();

    SpanExporter result =
        SpiffeMtlsAutoConfigurationCustomizerProvider.customizeSpanExporter(
            original, holder, emptyConfig);

    assertThat(result).isInstanceOf(OtlpHttpSpanExporter.class).isNotSameAs(original);
    verify(sslContextProvider).get();
  }

  @Test
  void returnsOriginalSpanExporterWhenNotOtlpType() {
    SpanExporter nonOtlp = mock(SpanExporter.class);

    SpanExporter result =
        SpiffeMtlsAutoConfigurationCustomizerProvider.customizeSpanExporter(
            nonOtlp, holder, emptyConfig);

    assertThat(result).isSameAs(nonOtlp);
  }

  // ── Metric exporters ────────────────────────────────────────────────────────

  @Test
  void customizesGrpcMetricExporterWithSslContext() {
    OtlpGrpcMetricExporter original = OtlpGrpcMetricExporter.builder().build();

    MetricExporter result =
        SpiffeMtlsAutoConfigurationCustomizerProvider.customizeMetricExporter(
            original, holder, emptyConfig);

    assertThat(result).isInstanceOf(OtlpGrpcMetricExporter.class).isNotSameAs(original);
  }

  @Test
  void customizesHttpMetricExporterWithSslContext() {
    OtlpHttpMetricExporter original = OtlpHttpMetricExporter.builder().build();

    MetricExporter result =
        SpiffeMtlsAutoConfigurationCustomizerProvider.customizeMetricExporter(
            original, holder, emptyConfig);

    assertThat(result).isInstanceOf(OtlpHttpMetricExporter.class).isNotSameAs(original);
  }

  // ── Log record exporters ────────────────────────────────────────────────────

  @Test
  void customizesGrpcLogRecordExporterWithSslContext() {
    OtlpGrpcLogRecordExporter original = OtlpGrpcLogRecordExporter.builder().build();

    LogRecordExporter result =
        SpiffeMtlsAutoConfigurationCustomizerProvider.customizeLogRecordExporter(
            original, holder, emptyConfig);

    assertThat(result).isInstanceOf(OtlpGrpcLogRecordExporter.class).isNotSameAs(original);
  }

  @Test
  void customizesHttpLogRecordExporterWithSslContext() {
    OtlpHttpLogRecordExporter original = OtlpHttpLogRecordExporter.builder().build();

    LogRecordExporter result =
        SpiffeMtlsAutoConfigurationCustomizerProvider.customizeLogRecordExporter(
            original, holder, emptyConfig);

    assertThat(result).isInstanceOf(OtlpHttpLogRecordExporter.class).isNotSameAs(original);
  }

  // ── Workload API unavailable ─────────────────────────────────────────────────

  @Test
  void returnsOriginalExporterWhenWorkloadApiUnavailable() {
    SpiffeMtlsAutoConfigurationCustomizerProvider.LazySpiffeSslContextProviderHolder failingHolder =
        new SpiffeMtlsAutoConfigurationCustomizerProvider.LazySpiffeSslContextProviderHolder() {
          @Override
          SpiffeSslContextProvider get(ConfigProperties config) {
            throw new ConfigurationException("Workload API not available");
          }
        };

    OtlpGrpcSpanExporter original = OtlpGrpcSpanExporter.builder().build();
    SpanExporter result =
        SpiffeMtlsAutoConfigurationCustomizerProvider.customizeSpanExporter(
            original, failingHolder, emptyConfig);

    // Original exporter returned unchanged — application starts without mTLS rather than failing
    assertThat(result).isSameAs(original);
  }

  @Test
  void orderIsMaxValueMinusOne() {
    assertThat(new SpiffeMtlsAutoConfigurationCustomizerProvider().order())
        .isEqualTo(Integer.MAX_VALUE - 1);
  }
}
