package br.com.nfse.config;

import br.com.nfse.signing.SignatureAlgorithm;

import java.time.Duration;

/**
 * All NFS-e integration settings. Built by {@link Settings#fromEnvironment()} —
 * the profile (local / restrita / producao) fixes the environment-specific
 * values, and only the certificate and optional overrides come from env vars.
 */
public record NfseProperties(
        Environment environment,
        int tpAmb,
        String sefinBaseUrl,
        String adnBaseUrl,
        boolean connectivityCheckOnStartup,
        Certificate certificate,
        Signature signature,
        Http http
) {
    public record Certificate(String path, String password, String type) {
        public String typeOrDefault() {
            return (type == null || type.isBlank()) ? "PKCS12" : type;
        }
    }

    public record Signature(SignatureAlgorithm algorithm) {
        public SignatureAlgorithm algorithmOrDefault() {
            return algorithm == null ? SignatureAlgorithm.RSA_SHA256 : algorithm;
        }
    }

    public record Http(Duration connectTimeout, Duration readTimeout) {
        public Duration connectTimeoutOrDefault() {
            return connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        }
        public Duration readTimeoutOrDefault() {
            return readTimeout == null ? Duration.ofSeconds(60) : readTimeout;
        }
    }

    public Http httpOrDefault() {
        return http == null ? new Http(null, null) : http;
    }
}
