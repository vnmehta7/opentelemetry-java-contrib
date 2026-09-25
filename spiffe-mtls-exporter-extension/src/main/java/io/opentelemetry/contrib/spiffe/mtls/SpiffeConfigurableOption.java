/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.contrib.spiffe.mtls;

import static java.util.Locale.ROOT;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Configurable options for the SPIFFE mTLS exporter extension. Each option maps to an environment
 * variable and an equivalent system property (lower-cased, underscores replaced with dots).
 */
enum SpiffeConfigurableOption {

  /**
   * SPIFFE ID suffix used to select the correct X.509-SVID when the Workload API returns multiple
   * SVIDs for the calling workload.
   *
   * <p>The configured value is matched as a substring against each SVID's SPIFFE ID URI (e.g.
   * {@code spiffe://trust-domain/ns/default/sa/my-app}). When not set, the first SVID returned by
   * the Workload API is used.
   *
   * <p>Env var: {@code OTEL_EXPORTER_SPIFFE_APP_ID}<br>
   * System property: {@code otel.exporter.spiffe.app.id}
   */
  OTEL_EXPORTER_SPIFFE_APP_ID("SPIFFE app identifier for SVID selection"),

  /**
   * Path to the SPIFFE Workload API Unix domain socket.
   *
   * <p>When not set, {@code java-spiffe-provider} falls back to the {@code SPIFFE_ENDPOINT_SOCKET}
   * environment variable, which is the standard location set by SPIRE agents.
   *
   * <p>Env var: {@code OTEL_EXPORTER_SPIFFE_SOCKET}<br>
   * System property: {@code otel.exporter.spiffe.socket}
   */
  OTEL_EXPORTER_SPIFFE_SOCKET("SPIFFE Workload API socket path"),

  /**
   * Maximum seconds to wait for the SPIFFE Workload API to become available at startup.
   *
   * <p>The extension retries the Workload API connection every 3 seconds until the first X.509 SVID
   * is received or this timeout elapses. This covers the race between the Java application starting
   * and the SPIFFE agent sidecar becoming ready. Defaults to 120 seconds.
   *
   * <p>Env var: {@code OTEL_EXPORTER_SPIFFE_STARTUP_TIMEOUT}<br>
   * System property: {@code otel.exporter.spiffe.startup.timeout}
   */
  OTEL_EXPORTER_SPIFFE_STARTUP_TIMEOUT("Maximum seconds to wait for Workload API at startup");

  private final String userReadableName;
  private final String environmentVariableName;
  private final String systemPropertyName;

  SpiffeConfigurableOption(String userReadableName) {
    this.userReadableName = userReadableName;
    this.environmentVariableName = this.name();
    this.systemPropertyName = this.environmentVariableName.toLowerCase(ROOT).replace('_', '.');
  }

  String getEnvironmentVariable() {
    return environmentVariableName;
  }

  String getSystemProperty() {
    return systemPropertyName;
  }

  String getUserReadableName() {
    return userReadableName;
  }

  /**
   * Returns the configured value, throwing {@link ConfigurationException} if neither env var nor
   * system property is set.
   */
  String getConfiguredValue(ConfigProperties configProperties) {
    String value = configProperties.getString(systemPropertyName);
    if (value != null && !value.trim().isEmpty()) {
      return value.trim();
    }
    throw new ConfigurationException(
        String.format(
            "SPIFFE mTLS extension: %s not configured." + " Set env var %s or system property %s.",
            userReadableName, environmentVariableName, systemPropertyName));
  }

  /** Returns the configured value, or the result of {@code fallback} when not set. */
  String getConfiguredValueWithFallback(
      ConfigProperties configProperties, Supplier<String> fallback) {
    try {
      return getConfiguredValue(configProperties);
    } catch (ConfigurationException e) {
      return fallback.get();
    }
  }

  /** Returns the configured value as an {@link Optional}, empty when not set. */
  Optional<String> getConfiguredValueAsOptional(ConfigProperties configProperties) {
    try {
      return Optional.of(getConfiguredValue(configProperties));
    } catch (ConfigurationException e) {
      return Optional.empty();
    }
  }

  /**
   * Returns the configured value parsed as a {@code long}, or {@code defaultValue} when not set or
   * the value cannot be parsed as a number.
   */
  long getConfiguredValueAsLong(ConfigProperties configProperties, long defaultValue) {
    return getConfiguredValueAsOptional(configProperties)
        .map(
            v -> {
              try {
                return Long.parseLong(v.trim());
              } catch (NumberFormatException e) {
                return defaultValue;
              }
            })
        .orElse(defaultValue);
  }
}
