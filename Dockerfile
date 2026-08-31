# syntax=docker/dockerfile:1
#
# Three stages: build the jar, jlink a minimal musl runtime, assemble on busybox.
# The runtime carries only the modules this service uses, which is what makes the
# image a fifth of a stock JRE one.
#
# Stage 2c trains a Leyden AOT cache (Java 25, JEP 483 class loading/linking +
# JEP 515 method profiles). It is what makes the container start in ~270 ms
# instead of ~700 ms. The training run drives the real service against a
# throwaway keypair and a WireMock SEFIN, so the build needs no e-CNPJ and never
# calls gov.br. Measured numbers and the trade-off are in the README.

# ---------------------------------------------------------------- 1. build
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src src
# Produces target/nfse.jar (Main-Class + Class-Path: lib/) and target/lib/.
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

# Build-time only, for stage 2c. The keypair is a throwaway: the AOT training run
# has to reach a ready state, and reaching it must not require the real e-CNPJ.
RUN mkdir -p /train \
 && keytool -genkeypair -alias train -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
      -dname "CN=NFSE AOT TRAINING, OU=BUILD, O=NFSE, C=BR" -validity 3650 \
      -storetype PKCS12 -keystore /train/train.p12 -storepass changeit -keypass changeit
RUN --mount=type=cache,target=/root/.m2 mvn -q -B \
      dependency:copy -Dartifact=org.wiremock:wiremock-standalone:3.13.2 \
      -DoutputDirectory=/train -Dmdep.stripVersion=true

# ---------------------------------------------------------- 2. jlink runtime
# Built on an Alpine JDK so the runtime links against musl and can be dropped
# into a plain alpine image. Temurin specifically: Corretto and Zulu were
# measured (identical startup, render and memory to within noise) but their musl
# builds need binutils at jlink time and a libz.so.1 copied into the final image,
# so they cost portability for nothing.
FROM eclipse-temurin:25-jdk-alpine AS runtime

# Module set derived with jdeps against the packaged application, plus two that
# no static analysis can see:
#   jdk.crypto.ec  — EC cipher suites; without it the TLS handshake with
#                    sefin.nfse.gov.br fails, since mTLS negotiates ECDHE.
#   jdk.localedata — the pt-BR locale. DANFSe money formatting asks for
#                    Locale.of("pt","BR"); with only the root locale in the
#                    image, "R$ 10.000,00" silently becomes "R$ 10,000.00".
RUN jlink \
      --add-modules java.base,java.compiler,java.desktop,java.naming,java.net.http,\
java.prefs,java.sql,java.xml.crypto,jdk.httpserver,jdk.jfr,jdk.unsupported,\
jdk.crypto.ec,jdk.localedata \
      --include-locales=en,pt \
      --strip-debug --no-man-pages --no-header-files --compress=zip-9 \
      --output /javaruntime

# ------------------------------------------------------- 2b. privilege dropper
# su-exec is a ~10 KB static helper; busybox has no package manager, so it is
# lifted from Alpine rather than installed in the final image.
FROM alpine:3.20 AS tools
RUN apk add --no-cache su-exec

# ------------------------------------------------------------- 2c. AOT training
# Runs the real service and drives it through the paths that matter — DANFSe
# render, emission, cancellation — then stops it with SIGTERM so the JVM writes
# the AOT cache on clean exit. The cache therefore holds the classes and method
# profiles of this application, not a synthetic approximation.
#
# Two JVMs run here: WireMock on this stage's JDK, and the application on the
# SAME jlink runtime the final image ships (/jlink) — an AOT cache is only valid
# for the JVM that created it, so training on anything else would produce a cache
# the final image silently ignores.
FROM eclipse-temurin:25-jdk-alpine AS train
COPY --from=runtime /javaruntime /jlink
WORKDIR /app
COPY --from=build /app/target/lib/ ./lib/
COPY --from=build /app/target/nfse.jar ./app.jar
COPY --from=build /train/ /train/
COPY training/ /train/
COPY src/test/resources/dps/nfse-export-sample.xml /train/nfse-sample.xml
RUN mv /train/evento-cancelamento-sample.xml /train/evento-sample.xml \
 && chmod +x /train/train.sh
# Deliberately WITHOUT the AOT flags the final image carries: this stage is where
# the cache gets written, so it must run on a plain JVM.
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -Duser.timezone=America/Sao_Paulo"
RUN /train/train.sh

# ------------------------------------------------------------- 3. final image
# busybox rather than alpine: 1.7 MB instead of 8.8 MB, and the only thing the
# jlink runtime links against outside its own directory is musl libc (verified
# with ldd), so that single file is all it needs. The trade-off is no `apk` for
# ad-hoc debugging tools — busybox still provides sh, ps, wget and friends.
FROM busybox:musl
ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=runtime /lib/ld-musl-*.so.1 /lib/
COPY --from=tools /sbin/su-exec /sbin/su-exec
COPY --from=runtime /javaruntime ${JAVA_HOME}

RUN echo 'nfse:x:1000:1000::/app:/bin/sh' >> /etc/passwd \
 && echo 'nfse:x:1000:' >> /etc/group \
 && mkdir -p /var/lib/nfse \
 && chown 1000:1000 /var/lib/nfse

# The numbering counters and the copy of every note issued. This is the only
# state the service keeps, and it is not reconstructible: losing it loses the
# next número and the local record of documents that must be kept for five
# years. MOUNT IT. The entrypoint re-owns it, so a fresh root-owned volume works.
#
# VOLUME is declared deliberately, despite the orphan anonymous volumes it
# creates when nobody passes -v: without it a forgotten mount writes fiscal
# documents to the container layer, where `docker rm` destroys them. An awkward
# anonymous volume is recoverable; the container layer is not. (Measured: the
# declaration costs no measurable startup time either way.)
ENV NFSE_DATA_DIR=/var/lib/nfse
VOLUME ["/var/lib/nfse"]

# Horário de Brasília for logs. No tzdata package is needed: the JDK carries its
# own tzdb, so naming the zone to the JVM is enough. (The documents we sign never
# depended on this anyway — every fiscal timestamp goes through BrasiliaTime.)
# Headless: the DANFSe rasterises its QR code through java.awt with no display.
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -Duser.timezone=America/Sao_Paulo \
-XX:AOTCache=/app/app.aot -XX:AOTMode=auto"

WORKDIR /app
# Dependencies first (they change rarely, so the layer stays cached), then the
# application jar. Everything else the service needs at runtime — XSDs,
# truststore, DANFSe fonts, the IBGE table — travels inside that jar.
COPY --from=build /app/target/lib/ ./lib/
COPY --from=build /app/target/nfse.jar ./app.jar
# The AOT cache. AOTMode=auto is deliberate: if the cache is ever unreadable or
# does not match the runtime, the JVM logs it and starts normally rather than
# refusing to boot. A slow start beats no start for a fiscal service.
COPY --from=train /app/app.aot ./app.aot
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

# Deliberately no `USER` here: the entrypoint starts as root only long enough to
# make the mounted certificate readable, then execs the JVM as `nfse`. Pass
# `--user` to skip that entirely — see the note in the entrypoint.
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -q -O- http://localhost:8080/health | grep -q '"status":"UP"' || exit 1

# The e-CNPJ certificate is NOT baked into the image — mount it and pass:
#   docker run -p 8080:8080 \
#     -v /secure/ecnpj.p12:/secure/ecnpj.p12:ro \
#     -e NFSE_PROFILE=restrita \
#     -e NFSE_CERT_PATH=/secure/ecnpj.p12 \
#     -e NFSE_CERT_PASSWORD=... \
#     nfse
# The mount may stay root-owned and mode 600; the entrypoint handles it.
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
