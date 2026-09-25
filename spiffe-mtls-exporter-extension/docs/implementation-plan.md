# spiffe-mtls-exporter-extension — Implementation Plan

Module path: `opentelemetry-java-contrib/spiffe-mtls-exporter-extension`

Provides automatic mTLS for OTLP exporters using SPIFFE X.509 SVIDs from the Workload API,
via `java-spiffe-provider`. Zero code changes required in the instrumented application.
Usable as a Java agent extension (shadow JAR) or as a compile dependency.

---

## Task List

### TASK-01 — Gradle module scaffold
- [ ] Create `spiffe-mtls-exporter-extension/build.gradle.kts`
  - Plugins: `otel.java-conventions`, `otel.publish-conventions`, `com.gradleup.shadow`
  - `otelJava.moduleName` = `io.opentelemetry.contrib.spiffe.mtls`
  - `implementation` dep: `io.spiffe:java-spiffe-provider:0.8.12` (shaded into shadow JAR)
  - `compileOnly` deps: `opentelemetry-api`, `opentelemetry-sdk-extension-autoconfigure`, `opentelemetry-exporter-otlp`
  - `annotationProcessor` + `compileOnly`: `com.google.auto.service`
  - Shadow JAR classifier `shadow`; plain JAR classifier `""` (both published)
  - Integration test suite wired to shadow JAR + OTel Java agent
- [ ] Register in `settings.gradle.kts`: `include(":spiffe-mtls-exporter-extension")`
- [ ] Add `io.spiffe:java-spiffe-provider:0.8.12` constraint to `dependencyManagement/build.gradle.kts`

### TASK-02 — `SpiffeConfigurableOption` enum
File: `src/main/java/io/opentelemetry/contrib/spiffe/mtls/SpiffeConfigurableOption.java`

Options:
| Enum constant | Env var | System property | Default |
|---|---|---|---|
| `OTEL_EXPORTER_SPIFFE_APP_ID` | `OTEL_EXPORTER_SPIFFE_APP_ID` | `otel.exporter.spiffe.app.id` | (none — use first SVID) |
| `OTEL_EXPORTER_SPIFFE_SOCKET` | `OTEL_EXPORTER_SPIFFE_SOCKET` | `otel.exporter.spiffe.socket` | (env `SPIFFE_ENDPOINT_SOCKET`) |

Follows `ConfigurableOption` pattern from `gcp-auth-extension`:
- `getConfiguredValue(ConfigProperties)` — throws `ConfigurationException` if absent
- `getConfiguredValueWithFallback(ConfigProperties, Supplier<String>)` — returns fallback
- `getConfiguredValueAsOptional(ConfigProperties)` — returns `Optional<String>`

### TASK-03 — `SpiffeSslContextProvider` (core logic)
File: `src/main/java/io/opentelemetry/contrib/spiffe/mtls/SpiffeSslContextProvider.java`

Responsibilities:
- Holds a `WorkloadApiClient` (from `io.spiffe:java-spiffe-provider`)
- On `get()`: calls `WorkloadApiClient.fetchX509Context()` to get all SVIDs
- SVID selection:
  - If `OTEL_EXPORTER_SPIFFE_APP_ID` is set → filter SVIDs by SPIFFE ID URI containing the app ID string
  - If not set → use first SVID (single-identity workload assumption)
  - If no SVID matches → throw `ConfigurationException` with clear message
- Builds `javax.net.ssl.SSLContext` from selected SVID's X.509 chain + private key + trust bundle — **all in-memory**, no disk I/O
- Returns `SSLContext` (called once per export; `WorkloadApiClient` handles rotation internally via streaming subscription)
- `close()` — shuts down `WorkloadApiClient`

Key API surface:
```java
class SpiffeSslContextProvider implements Closeable {
  static SpiffeSslContextProvider create(ConfigProperties config);
  SSLContext get();          // throws SpiffeException on failure
}
```

### TASK-04 — `SpiffeMtlsAutoConfigurationCustomizerProvider` (SPI entry point)
File: `src/main/java/io/opentelemetry/contrib/spiffe/mtls/SpiffeMtlsAutoConfigurationCustomizerProvider.java`

- Annotated `@AutoService(AutoConfigurationCustomizerProvider.class)`
- `order()` = `Integer.MAX_VALUE - 1` (runs after all other customizers)
- `customize()`:
  - Creates a `SpiffeSslContextProvider` (lazy, shared across exporter customizations)
  - `addSpanExporterCustomizer` → wraps `OtlpGrpcSpanExporter` and `OtlpHttpSpanExporter` with mTLS SSLContext
  - `addMetricExporterCustomizer` → wraps `OtlpGrpcMetricExporter` and `OtlpHttpMetricExporter`
  - `addLogRecordExporterCustomizer` → wraps `OtlpGrpcLogRecordExporter` and `OtlpHttpLogRecordExporter`
  - For each exporter: calls `exporter.toBuilder().setSslContext(sslContextProvider.get()).build()`
  - Registers a JVM shutdown hook to call `sslContextProvider.close()`

### TASK-05 — SPI registration file
File: `src/main/resources/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider`

Content: `io.opentelemetry.contrib.spiffe.mtls.SpiffeMtlsAutoConfigurationCustomizerProvider`

(Note: `@AutoService` generates this at compile time via annotation processor — keep the manual
file as a fallback for the unshaded JAR path.)

### TASK-06 — Unit tests
File: `src/test/java/io/opentelemetry/contrib/spiffe/mtls/SpiffeMtlsAutoConfigurationCustomizerProviderTest.java`

Test cases:
- `customizesGrpcSpanExporterWithSslContext` — mock `WorkloadApiClient`, verify exporter receives SSLContext
- `customizesHttpSpanExporterWithSslContext` — same for HTTP variant
- `customizesGrpcMetricExporter` — metric exporter mTLS wiring
- `customizesHttpMetricExporter`
- `customizesLogRecordExporter`
- `selectsSvidByAppId` — multiple SVIDs returned, correct one selected by `OTEL_EXPORTER_SPIFFE_APP_ID`
- `usesFirstSvidWhenAppIdNotConfigured` — no `OTEL_EXPORTER_SPIFFE_APP_ID`, first SVID used
- `throwsWhenNoSvidMatchesAppId` — app ID set but no SVID matches → `ConfigurationException`
- `noopWhenWorkloadApiUnavailable` — `WorkloadApiClient` throws → logged warning, original exporter returned unchanged

File: `src/test/java/io/opentelemetry/contrib/spiffe/mtls/SpiffeConfigurableOptionTest.java`

Test cases:
- `readsAppIdFromSystemProperty`
- `readsAppIdFromEnvVar`
- `returnsEmptyOptionalWhenNotConfigured`
- `fallbackUsedWhenNotConfigured`

### TASK-07 — Integration tests
File: `src/test/java/io/opentelemetry/contrib/spiffe/mtls/SpiffeMtlsExtensionEndToEndTest.java`

Setup:
- Spin up a SPIRE server + SPIRE agent in Docker via Testcontainers
- Register a workload entry for the test process SPIFFE ID
- Launch a minimal OTel SDK via autoconfigure with the shadow JAR as agent extension
- Run against a mock OTLP server (MockServer / WireMock) that requires client cert
- Verify: exported spans arrive with mutual TLS handshake completed

Test cases:
- `exportsSingleTraceWithMtls` — happy path end-to-end
- `reconnectsAfterCertRotation` — force SVID rotation via SPIRE API, verify exports continue
- `failsWithClearMessageWhenSpiffeSocketMissing` — no socket configured → clear error log

Dependencies to add to build.gradle.kts for integration tests:
- `org.testcontainers:testcontainers`
- `org.mock-server:mockserver-netty`
- `io.opentelemetry.proto:opentelemetry-proto`

### TASK-08 — README.md
File: `spiffe-mtls-exporter-extension/README.md`

Sections:
- What it does (one paragraph)
- Configuration table (`OTEL_EXPORTER_SPIFFE_APP_ID`, `OTEL_EXPORTER_SPIFFE_SOCKET`)
- Usage as Java agent extension (`-Dotel.javaagent.extensions=spiffe-mtls-exporter-extension-shadow.jar`)
- Usage as library dependency (Gradle / Maven coordinates)
- Prerequisites (SPIRE agent running, `SPIFFE_ENDPOINT_SOCKET` set)

---

## File Tree (target state)

```
spiffe-mtls-exporter-extension/
├── build.gradle.kts
├── README.md
├── docs/
│   └── implementation-plan.md          ← this file
└── src/
    ├── main/
    │   ├── java/io/opentelemetry/contrib/spiffe/mtls/
    │   │   ├── SpiffeConfigurableOption.java
    │   │   ├── SpiffeSslContextProvider.java
    │   │   └── SpiffeMtlsAutoConfigurationCustomizerProvider.java
    │   └── resources/META-INF/services/
    │       └── io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider
    └── test/
        └── java/io/opentelemetry/contrib/spiffe/mtls/
            ├── SpiffeConfigurableOptionTest.java
            ├── SpiffeMtlsAutoConfigurationCustomizerProviderTest.java
            └── SpiffeMtlsExtensionEndToEndTest.java
```

---

## Key Design Decisions

**SSLContext injection point**
`OtlpGrpcSpanExporter.toBuilder().setSslContext(SSLContext)` and the HTTP equivalent
`OtlpHttpSpanExporter.toBuilder().setSslContext(SSLContext)` are the supported OTel SDK hooks
for custom TLS. No reflection, no internal API usage.

**Rotation**
`java-spiffe-provider`'s `WorkloadApiClient` maintains a streaming gRPC subscription to the
Workload API and delivers new SVIDs via push. The `SSLContext` returned by
`SpiffeSslContextProvider.get()` is rebuilt on each export call, so rotation is picked up
automatically without restart.

**Shadow JAR shading**
`io.spiffe:java-spiffe-provider` and its gRPC transitive deps are shaded into the extension JAR
to avoid version conflicts with the host application's gRPC stack (same reason `gcp-auth-extension`
shades `google-auth-library`).

**No SAP/CF dependencies**
The module is fully generic. The CaaS extension (SAP-specific) is responsible for injecting
`OTEL_EXPORTER_SPIFFE_APP_ID` and `OTEL_EXPORTER_OTLP_ENDPOINT` at startup before this
extension initialises.
