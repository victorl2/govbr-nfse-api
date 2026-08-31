#!/bin/sh
# AOT training run — executed at image build time, never in production.
#
# Drives the real service through the paths that matter (DANFSe render, emission,
# cancellation) so the AOT cache holds the classes and method profiles those paths
# actually use. Nothing real is touched: the certificate is a throwaway keypair
# generated during the build, and SEFIN is a WireMock stub, so the build needs no
# e-CNPJ and makes no call to gov.br.
#
# The JVM writes the AOT cache when the application exits cleanly, so the run ends
# with SIGTERM and waits for the shutdown hook.
set -e

RENDERS=${TRAIN_RENDERS:-60}
EMISSIONS=${TRAIN_EMISSIONS:-15}
APP_JAVA=${APP_JAVA:-/jlink/bin/java}
CHAVE=$(grep -oE 'Id="NFS[0-9]+"' /train/nfse-sample.xml | head -1 | sed 's/Id="NFS//;s/"//')

echo "==> baking the gzip+base64 payloads into the SEFIN stub"
NFSE_B64=$(gzip -c /train/nfse-sample.xml | base64 | tr -d '\n')
EVT_B64=$(gzip -c /train/evento-sample.xml | base64 | tr -d '\n')
for f in /train/wiremock/mappings/*.json; do
    sed -i "s|@@NFSE_GZIP_B64@@|${NFSE_B64}|g; s|@@EVENTO_GZIP_B64@@|${EVT_B64}|g" "$f"
done

echo "==> starting WireMock (SEFIN stub) on :8089"
# --disable-http2-plain: the production client speaks HTTP/1.1 over TLS, and h2c here
# makes WireMock drop idle connections under the client's pool, which surfaces as
# a ClosedChannelException mid-training.
java -jar /train/wiremock-standalone.jar --port 8089 --root-dir /train/wiremock \
     --disable-banner --no-request-journal --disable-http2-plain \
     >/train/wiremock.log 2>&1 &
WIREMOCK=$!
wait_for() {
    for _ in $(seq 1 300); do
        wget -q -O /dev/null "$1" 2>/dev/null && return 0
        sleep 0.1
    done
    echo "TIMED OUT waiting for $1"
    return 1
}
# Fail loudly. A silent timeout here trains against a dead stub, which still
# produces a plausible-looking cache with none of the emission paths in it.
wait_for http://localhost:8089/__admin/mappings || {
    echo "--- WireMock log ---"; cat /train/wiremock.log; exit 1; }

echo "==> starting the service under AOT recording"
NFSE_PROFILE=local \
NFSE_CERT_PATH=/train/train.p12 \
NFSE_CERT_PASSWORD=changeit \
NFSE_DATA_DIR=/train/data \
NFSE_CONNECTIVITY_CHECK=true \
  "$APP_JAVA" -XX:AOTCacheOutput=/app/app.aot -jar /app/app.jar &
APP=$!
wait_for http://localhost:8080/health || { echo "the service never became ready"; exit 1; }

post() { wget -q -O /dev/null --header="Content-Type: $1" --post-file="$2" "$3"; }
# Read paths are warm-up, not assertions: a transient pool hiccup must not fail
# the build, but it must be visible.
get_soft() {
    for _ in 1 2 3; do
        wget -q -O /dev/null "$1" && return 0
        sleep 0.3
    done
    echo "WARN: training could not reach $1"
}

echo "==> proving the stubbed emission path end to end"
STATUS=$(wget -q -O - --header="Content-Type: application/json" \
    --post-file=/train/sale.json http://localhost:8080/nfse/send \
    | tr ',' '\n' | grep -o '"status":"[A-Z_]*"' | head -1)
echo "    first send -> ${STATUS:-<no status>}"
case "$STATUS" in
    *AUTHORIZED*) ;;
    *) echo "training expected an AUTHORIZED emission from the WireMock SEFIN, got: $STATUS"
       cat /train/wiremock.log | tail -20
       exit 1 ;;
esac

echo "==> ${RENDERS} DANFSe renders"
i=0; while [ "$i" -lt "$RENDERS" ]; do
    post application/xml /train/nfse-sample.xml http://localhost:8080/nfse/danfse
    i=$((i + 1))
done

echo "==> ${EMISSIONS} validate + send + cancel cycles"
i=0; while [ "$i" -lt "$EMISSIONS" ]; do
    post application/json /train/sale.json http://localhost:8080/nfse/validate
    post application/json /train/sale.json http://localhost:8080/nfse/send
    post application/json /train/cancel.json "http://localhost:8080/nfse/${CHAVE}/cancel/validate"
    post application/json /train/cancel.json "http://localhost:8080/nfse/${CHAVE}/cancel"
    i=$((i + 1))
done

echo "==> read paths"
get_soft "http://localhost:8080/nfse/${CHAVE}/danfse"
get_soft "http://localhost:8080/nfse/${CHAVE}"
get_soft 'http://localhost:8080/internal/emissions?limit=20'
get_soft http://localhost:8080/internal/numbering
get_soft http://localhost:8080/internal/certificate
get_soft http://localhost:8080/health

echo "==> stopping the service so the JVM writes the cache"
kill -TERM "$APP"
wait "$APP" || true
kill -TERM "$WIREMOCK" 2>/dev/null || true

[ -s /app/app.aot ] || { echo "AOT cache was not written"; exit 1; }
ls -la /app/app.aot
