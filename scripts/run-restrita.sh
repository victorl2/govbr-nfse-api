#!/usr/bin/env bash
#
# Runs the service against PRODUÇÃO RESTRITA with the real e-CNPJ A1.
#
# The certificate password is read from the macOS Keychain so it never appears
# in files, environment exports, or shell history. Store it once with:
#
#   security add-generic-password -a nfse -s nfse-cert -w
#
# (prompts for the password interactively). The certificate itself is expected
# at ~/.nfse/ecnpj.p12 (chmod 600) or wherever NFSE_CERT_PATH points.
#
set -euo pipefail

CERT="${NFSE_CERT_PATH:-$HOME/.nfse/ecnpj.p12}"
if [[ ! -f "$CERT" ]]; then
  echo "certificate not found: $CERT" >&2
  exit 1
fi

PASS="$(security find-generic-password -a nfse -s nfse-cert -w)" || {
  echo "no Keychain entry — store the password first:" >&2
  echo "  security add-generic-password -a nfse -s nfse-cert -w" >&2
  exit 1
}

if [[ ! -f target/nfse.jar ]]; then
  echo "target/nfse.jar not found — run: mvn -DskipTests package" >&2
  exit 1
fi

exec env NFSE_PROFILE=restrita NFSE_CERT_PATH="$CERT" NFSE_CERT_PASSWORD="$PASS" \
  java -jar target/nfse.jar
