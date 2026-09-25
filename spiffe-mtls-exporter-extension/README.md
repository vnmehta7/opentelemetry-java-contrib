# spiffe-mtls-exporter-extension

Automatically configures mutual TLS (mTLS) on all OTLP exporters using X.509 SVIDs obtained from
the [SPIFFE Workload API](https://github.com/spiffe/spiffe/blob/main/standards/SPIFFE_Workload_API.md).
Works with any SPIFFE-compatible workload identity system — [SAP ZTIS](https://github.com/SAP/project-ztis),
[SPIRE](https://github.com/spiffe/spire), or any other compliant agent.

No code changes are required in the instrumented application.

## What it does

- Connects to the SPIFFE Workload API via [`java-spiffe-provider`](https://github.com/spiffe/java-spiffe)
- Selects the correct X.509-SVID (by SPIFFE ID suffix or first-SVID default)
- Builds an in-memory `SSLContext` — private keys never touch disk
- Injects the `SSLContext` into all configured OTLP exporters (gRPC and HTTP, spans/metrics/logs)
- Handles cert rotation automatically via the Workload API push stream — no restart required

## Prerequisites

- A SPIFFE-compatible agent running on the node (e.g. SPIRE agent, SAP ZTIS sidecar)
- The `SPIFFE_ENDPOINT_SOCKET` environment variable set to the Workload API Unix socket path
  (e.g. `unix:/run/spire/sockets/agent.sock`), or configured via `OTEL_EXPORTER_SPIFFE_SOCKET`

## Configuration

| Environment variable | System property | Default | Description |
|---|---|---|---|
| `OTEL_EXPORTER_SPIFFE_APP_ID` | `otel.exporter.spiffe.app.id` | _(first SVID)_ | Substring match against the SVID's SPIFFE ID URI used to select the correct certificate when the Workload API returns multiple SVIDs |
| `OTEL_EXPORTER_SPIFFE_SOCKET` | `otel.exporter.spiffe.socket` | `$SPIFFE_ENDPOINT_SOCKET` | Path to the Workload API Unix domain socket |

## Usage as a Java agent extension

Drop the shadow JAR alongside the OTel Java agent:

```bash
java \
  -javaagent:opentelemetry-javaagent.jar \
  -Dotel.javaagent.extensions=spiffe-mtls-exporter-extension-<version>-shadow.jar \
  -Dotel.exporter.otlp.endpoint=https://collector.internal:4317 \
  -Dotel.exporter.spiffe.app.id=my-service \
  -jar myapp.jar
```

## Usage as a library dependency

Add to `build.gradle.kts`:

```kotlin
implementation("io.opentelemetry.contrib:spiffe-mtls-exporter-extension:<version>")
```

With `otel.java.global-autoconfigure.enabled=true` the extension activates automatically via SPI.

## Relation to SAP CaaS OTel Java Extension

This module is generic — it has no SAP or Cloud Foundry dependencies. The
[SAP CaaS OTel Java Extension](https://github.com/SAP/caas-opentelemetry-java-extension) is
responsible for injecting `OTEL_EXPORTER_SPIFFE_APP_ID` and `OTEL_EXPORTER_OTLP_ENDPOINT` at
startup from CF service bindings, then this extension takes over for the mTLS handshake.

## Implementation notes

See [`docs/implementation-plan.md`](docs/implementation-plan.md) for the full task list, design
decisions, and open items.
