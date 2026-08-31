package br.com.nfse.certificate;

import java.time.Instant;

/** Non-sensitive view of the loaded certificate (never exposes the private key). */
public record CertificateInfo(
        String subject,
        String issuer,
        Instant notAfter,
        long daysToExpiry
) {}
