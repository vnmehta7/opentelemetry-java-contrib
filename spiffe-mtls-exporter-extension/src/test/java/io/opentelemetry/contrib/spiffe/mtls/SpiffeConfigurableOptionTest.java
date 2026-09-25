/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.contrib.spiffe.mtls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpiffeConfigurableOptionTest {

  @Test
  void getConfiguredValue_returnsValueFromSystemProperty() {
    ConfigProperties config =
        DefaultConfigProperties.createFromMap(
            Collections.singletonMap("otel.exporter.spiffe.app.id", "my-app"));

    String value = SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_APP_ID.getConfiguredValue(config);

    assertThat(value).isEqualTo("my-app");
  }

  @Test
  void getConfiguredValue_throwsWhenNotConfigured() {
    ConfigProperties config = DefaultConfigProperties.createFromMap(Collections.emptyMap());

    assertThatThrownBy(
            () -> SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_APP_ID.getConfiguredValue(config))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("OTEL_EXPORTER_SPIFFE_APP_ID")
        .hasMessageContaining("otel.exporter.spiffe.app.id");
  }

  @Test
  void getConfiguredValueWithFallback_returnsFallbackWhenNotConfigured() {
    ConfigProperties config = DefaultConfigProperties.createFromMap(Collections.emptyMap());

    String value =
        SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_APP_ID.getConfiguredValueWithFallback(
            config, () -> "default-app");

    assertThat(value).isEqualTo("default-app");
  }

  @Test
  void getConfiguredValueWithFallback_prefersConfiguredValueOverFallback() {
    ConfigProperties config =
        DefaultConfigProperties.createFromMap(
            Collections.singletonMap("otel.exporter.spiffe.app.id", "configured-app"));

    String value =
        SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_APP_ID.getConfiguredValueWithFallback(
            config, () -> "fallback-app");

    assertThat(value).isEqualTo("configured-app");
  }

  @Test
  void getConfiguredValueAsOptional_returnsEmptyWhenNotConfigured() {
    ConfigProperties config = DefaultConfigProperties.createFromMap(Collections.emptyMap());

    assertThat(
            SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_APP_ID.getConfiguredValueAsOptional(
                config))
        .isEmpty();
  }

  @Test
  void getConfiguredValueAsOptional_returnsPresentWhenConfigured() {
    ConfigProperties config =
        DefaultConfigProperties.createFromMap(
            Collections.singletonMap("otel.exporter.spiffe.socket", "unix:/tmp/spire.sock"));

    assertThat(
            SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_SOCKET.getConfiguredValueAsOptional(
                config))
        .hasValue("unix:/tmp/spire.sock");
  }

  @Test
  void systemPropertyNames_followOtelConvention() {
    assertThat(SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_APP_ID.getSystemProperty())
        .isEqualTo("otel.exporter.spiffe.app.id");
    assertThat(SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_SOCKET.getSystemProperty())
        .isEqualTo("otel.exporter.spiffe.socket");
    assertThat(SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_STARTUP_TIMEOUT.getSystemProperty())
        .isEqualTo("otel.exporter.spiffe.startup.timeout");
  }

  @Test
  void environmentVariableNames_matchEnumNames() {
    assertThat(SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_APP_ID.getEnvironmentVariable())
        .isEqualTo("OTEL_EXPORTER_SPIFFE_APP_ID");
    assertThat(SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_SOCKET.getEnvironmentVariable())
        .isEqualTo("OTEL_EXPORTER_SPIFFE_SOCKET");
    assertThat(
            SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_STARTUP_TIMEOUT.getEnvironmentVariable())
        .isEqualTo("OTEL_EXPORTER_SPIFFE_STARTUP_TIMEOUT");
  }

  @Test
  void getConfiguredValueAsLong_returnsConfiguredValue() {
    ConfigProperties config =
        DefaultConfigProperties.createFromMap(
            Collections.singletonMap("otel.exporter.spiffe.startup.timeout", "60"));

    long value =
        SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_STARTUP_TIMEOUT.getConfiguredValueAsLong(
            config, 120L);

    assertThat(value).isEqualTo(60L);
  }

  @Test
  void getConfiguredValueAsLong_returnsDefaultWhenNotConfigured() {
    ConfigProperties config = DefaultConfigProperties.createFromMap(Collections.emptyMap());

    long value =
        SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_STARTUP_TIMEOUT.getConfiguredValueAsLong(
            config, 120L);

    assertThat(value).isEqualTo(120L);
  }

  @Test
  void getConfiguredValueAsLong_returnsDefaultWhenValueIsNotANumber() {
    ConfigProperties config =
        DefaultConfigProperties.createFromMap(
            Collections.singletonMap("otel.exporter.spiffe.startup.timeout", "not-a-number"));

    long value =
        SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_STARTUP_TIMEOUT.getConfiguredValueAsLong(
            config, 120L);

    assertThat(value).isEqualTo(120L);
  }

  @Test
  void getConfiguredValue_ignoresBlankValue() {
    Map<String, String> props = new HashMap<>();
    props.put("otel.exporter.spiffe.app.id", "  ");
    ConfigProperties config = DefaultConfigProperties.createFromMap(props);

    // OTel DefaultConfigProperties trims values; blank should be treated as absent
    assertThatThrownBy(
            () -> SpiffeConfigurableOption.OTEL_EXPORTER_SPIFFE_APP_ID.getConfiguredValue(config))
        .isInstanceOf(ConfigurationException.class);
  }
}
