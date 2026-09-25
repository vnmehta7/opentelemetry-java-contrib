plugins {
  id("otel.java-conventions")
  id("otel.publish-conventions")
  id("com.gradleup.shadow")
}

description = "OpenTelemetry extension that provides SPIFFE/mTLS support for OTLP exporters"
otelJava.moduleName.set("io.opentelemetry.contrib.spiffe.mtls")

val agent: Configuration by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}

dependencies {
  annotationProcessor("com.google.auto.service:auto-service")
  compileOnly("com.google.auto.service:auto-service-annotations")
  compileOnly("io.opentelemetry:opentelemetry-api")
  compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
  compileOnly("io.opentelemetry:opentelemetry-exporter-otlp")

  // Shaded into the shadow JAR — provides Workload API gRPC client and SVID rotation
  implementation("io.spiffe:java-spiffe-provider:0.8.12")

  testCompileOnly("com.google.auto.service:auto-service-annotations")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testImplementation("org.junit-pioneer:junit-pioneer")

  testImplementation("io.opentelemetry:opentelemetry-api")
  testImplementation("io.opentelemetry:opentelemetry-exporter-otlp")
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
  testImplementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
  testImplementation("org.mockito:mockito-inline")
  testImplementation("org.mockito:mockito-junit-jupiter")
  testImplementation("org.assertj:assertj-core")

  agent("io.opentelemetry.javaagent:opentelemetry-javaagent")
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      targets.all {
        testTask.configure {
          environment("OTEL_EXPORTER_SPIFFE_APP_ID", "")
          environment("OTEL_EXPORTER_SPIFFE_SOCKET", "")
          exclude("io/opentelemetry/contrib/spiffe/mtls/SpiffeMtlsExtensionEndToEndTest.class")
        }
      }
    }

    val integrationTest by registering(JvmTestSuite::class) {
      dependencies {
        implementation(project())
        implementation("org.testcontainers:testcontainers:1.20.4")
        implementation("org.mock-server:mockserver-netty:5.15.0")
        implementation("io.opentelemetry.proto:opentelemetry-proto:1.11.0-alpha")
      }

      targets.all {
        testTask.configure {
          dependsOn(tasks.shadowJar)
          include("io/opentelemetry/contrib/spiffe/mtls/SpiffeMtlsExtensionEndToEndTest.class")

          val agentJar = configurations.named("agent").map { it.singleFile.absolutePath }
          val extensionJarPath =
            tasks.shadowJar.flatMap { it.archiveFile }.map { it.asFile.absolutePath }

          jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf(
              "-javaagent:${agentJar.get()}",
              "-Dotel.javaagent.extensions=${extensionJarPath.get()}",
              "-Dotel.java.global-autoconfigure.enabled=true",
              "-Dotel.traces.exporter=otlp",
              "-Dotel.metrics.exporter=none",
              "-Dotel.logs.exporter=none",
              "-Dotel.exporter.otlp.protocol=grpc",
              "-Dotel.javaagent.debug=false"
            )
          })
        }
      }
    }
  }
}

tasks {
  named<JavaCompile>("compileTestJava") {
    options.compilerArgs.removeAll(listOf("-Werror"))
  }

  shadowJar {
    archiveClassifier.set("shadow")
    // Relocate shaded deps to avoid classpath conflicts with host application
    relocate("io.spiffe", "io.opentelemetry.contrib.spiffe.shaded.io.spiffe")
    relocate("io.grpc", "io.opentelemetry.contrib.spiffe.shaded.io.grpc")
  }

  jar {
    archiveClassifier.set("")
  }

  assemble {
    dependsOn(shadowJar)
  }
}
