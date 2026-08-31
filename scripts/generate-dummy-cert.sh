#!/usr/bin/env bash
#
# Generates a SELF-SIGNED PKCS#12 keystore for LOCAL development only.
#
# This dummy cert exercises the full build/sign/self-verify pipeline offline.
# It is NOT valid for produção restrita or produção — gov.br trusts only the
# ICP-Brasil chain, so the mTLS handshake and SEFIN signature validation will
# reject it. For those, use the real e-CNPJ A1 (docs/08 §8.6.1).
#
set -euo pipefail

OUT="${1:-certs/dummy.p12}"
PASS="${NFSE_CERT_PASSWORD:-changeit}"

mkdir -p "$(dirname "$OUT")"

keytool -genkeypair \
  -alias nfse-dummy \
  -keyalg RSA -keysize 2048 \
  -sigalg SHA256withRSA \
  -dname "CN=NFSE DUMMY DEV:00000000000000, OU=DEV, O=NFSE, C=BR" \
  -validity 825 \
  -storetype PKCS12 \
  -keystore "$OUT" \
  -storepass "$PASS" -keypass "$PASS"

echo
echo "Dummy PKCS#12 written to: $OUT"
echo "Password: \$NFSE_CERT_PASSWORD (default 'changeit')"
echo "Run the app with:  NFSE_PROFILE=local NFSE_CERT_PATH=$OUT java -jar target/nfse.jar"
