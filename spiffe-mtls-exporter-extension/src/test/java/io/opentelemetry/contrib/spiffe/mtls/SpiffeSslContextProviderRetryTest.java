/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.contrib.spiffe.mtls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.spiffe.exception.SocketEndpointAddressException;
import io.spiffe.exception.X509SourceException;
import io.spiffe.workloadapi.DefaultX509Source;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpiffeSslContextProviderRetryTest {

  @BeforeEach
  void setInstantRetry() {
    SpiffeSslContextProvider.retryDelayMs = 0;
  }

  @AfterEach
  void restoreRetryDelay() {
    SpiffeSslContextProvider.retryDelayMs = 3_000L;
  }

  @Test
  void succeedsImmediatelyWhenFactorySucceedsOnFirstAttempt() {
    DefaultX509Source mockSource =
        mock(DefaultX509Source.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    AtomicInteger calls = new AtomicInteger();

    SpiffeSslContextProvider provider =
        SpiffeSslContextProvider.createWithRetry(
            configWithTimeout(5),
            () -> {
              calls.incrementAndGet();
              return mockSource;
            });

    assertThat(calls.get()).isEqualTo(1);
    provider.close();
  }

  @Test
  void retriesOnX509SourceExceptionAndSucceedsOnLaterAttempt() throws Exception {
    DefaultX509Source mockSource =
        mock(DefaultX509Source.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    AtomicInteger calls = new AtomicInteger();

    SpiffeSslContextProvider provider =
        SpiffeSslContextProvider.createWithRetry(
            configWithTimeout(10),
            () -> {
              if (calls.incrementAndGet() < 4) {
                throw new X509SourceException("socket not ready yet");
              }
              return mockSource;
            });

    assertThat(calls.get()).isEqualTo(4);
    provider.close();
  }

  @Test
  void throwsConfigurationExceptionWhenTimeoutExceeded() {
    assertThatThrownBy(
            () ->
                SpiffeSslContextProvider.createWithRetry(
                    configWithTimeout(0),
                    () -> {
                      throw new X509SourceException("never ready");
                    }))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("not available after");
  }

  @Test
  void doesNotRetryOnSocketEndpointAddressException() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                SpiffeSslContextProvider.createWithRetry(
                    configWithTimeout(120),
                    () -> {
                      calls.incrementAndGet();
                      throw new SocketEndpointAddressException("bad address");
                    }))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("invalid Workload API socket address");

    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void throwsConfigurationExceptionOnInterruption() throws Exception {
    SpiffeSslContextProvider.retryDelayMs = 60_000L;
    Thread testThread = Thread.currentThread();

    Thread interrupter =
        new Thread(
            () -> {
              try {
                Thread.sleep(50);
              } catch (InterruptedException ignored) {
              }
              testThread.interrupt();
            });
    interrupter.setDaemon(true);
    interrupter.start();

    assertThatThrownBy(
            () ->
                SpiffeSslContextProvider.createWithRetry(
                    configWithTimeout(120),
                    () -> {
                      throw new X509SourceException("not ready");
                    }))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("interrupted");

    Thread.interrupted(); // clear interrupted flag for other tests
  }

  private static ConfigProperties configWithTimeout(long timeoutSeconds) {
    return DefaultConfigProperties.createFromMap(
        Map.of("otel.exporter.spiffe.startup.timeout", String.valueOf(timeoutSeconds)));
  }
}
