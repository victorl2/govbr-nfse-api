#!/bin/sh
# Starts the service as the unprivileged `nfse` user while still being able to
# read a certificate the host mounted as root-owned and mode 600 — which is what
# a correctly-protected e-CNPJ key file looks like.
#
# The host's file is never modified: it is copied to a container-private path,
# and only that copy is re-owned. Bind mounts are the real file on Linux, so
# chown/chmod on the mount point would silently change the operator's key.
set -e

APP_USER=nfse
PRIVATE_CERT=/run/nfse/cert.p12
if [ "$(id -u)" = "0" ]; then
    # A freshly created volume arrives root-owned, and the JVM runs as nfse.
    # Only the mount point itself is re-owned, never its contents recursively:
    # the emission records are fiscal documents, not ours to rewrite on boot.
    if [ -n "${NFSE_DATA_DIR}" ]; then
        mkdir -p "${NFSE_DATA_DIR}"
        chown "${APP_USER}" "${NFSE_DATA_DIR}"
    fi
    if [ -n "${NFSE_CERT_PATH}" ] && [ -f "${NFSE_CERT_PATH}" ]; then
        mkdir -p "$(dirname "${PRIVATE_CERT}")"
        cp "${NFSE_CERT_PATH}" "${PRIVATE_CERT}"
        chown "${APP_USER}" "${PRIVATE_CERT}"
        chmod 400 "${PRIVATE_CERT}"
        NFSE_CERT_PATH="${PRIVATE_CERT}"
        export NFSE_CERT_PATH
    fi
    # Drop privileges for the JVM itself: the application never runs as root.
    exec su-exec "${APP_USER}" java -jar /app/app.jar "$@"
fi

# Started with an explicit --user: respect it and change nothing. The caller is
# then responsible for the certificate being readable by that uid.
exec java -jar /app/app.jar "$@"
