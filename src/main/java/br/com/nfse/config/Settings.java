package br.com.nfse.config;

import br.com.nfse.signing.SignatureAlgorithm;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Builds {@link NfseProperties} from the environment, replacing Spring Boot's
 * profile + YAML binding.
 *
 * <p>The settings that differ between environments are not really configuration —
 * they are facts about each SEFIN environment (which host, which {@code tpAmb}),
 * and getting them out of step is exactly the mistake that would file a note in
 * the wrong environment. So they live here, chosen by {@code NFSE_PROFILE}, and
 * only the genuinely deployment-specific values (the certificate, and optional
 * URL/timeout overrides) come from environment variables.
 */
public final class Settings {

    private Settings() {
    }

    /** Reads {@code NFSE_PROFILE} (default {@code local}) and its environment. */
    public static NfseProperties fromEnvironment() {
        return fromEnvironment(env("NFSE_PROFILE", "local"));
    }

    public static NfseProperties fromEnvironment(String profile) {
        Environment environment = switch (profile.toLowerCase()) {
            case "producao" -> Environment.PRODUCAO;
            case "restrita" -> Environment.RESTRITA;
            case "local" -> Environment.LOCAL;
            default -> throw new IllegalArgumentException(
                    "unknown NFSE_PROFILE '" + profile + "' — expected local, restrita or producao");
        };

        // tpAmb and the host must agree: 1 = produção (legally valid notes),
        // 2 = restrita/homologação. They are paired here so they cannot drift.
        String sefin;
        String adn;
        int tpAmb;
        boolean probeOnStartup;
        switch (environment) {
            case PRODUCAO -> {
                sefin = "https://sefin.nfse.gov.br/SefinNacional";
                adn = "https://adn.nfse.gov.br";
                tpAmb = 1;
                probeOnStartup = true;
            }
            case RESTRITA -> {
                sefin = "https://sefin.producaorestrita.nfse.gov.br/SefinNacional";
                adn = "https://adn.producaorestrita.nfse.gov.br";
                tpAmb = 2;
                probeOnStartup = true;
            }
            default -> {
                sefin = "http://localhost:8089/SefinNacional";
                adn = "http://localhost:8089";
                tpAmb = 2;
                probeOnStartup = false;
            }
        }

        return new NfseProperties(
                environment,
                tpAmb,
                env("NFSE_SEFIN_BASE_URL", sefin),
                env("NFSE_ADN_BASE_URL", adn),
                Boolean.parseBoolean(env("NFSE_CONNECTIVITY_CHECK", String.valueOf(probeOnStartup))),
                new NfseProperties.Certificate(
                        env("NFSE_CERT_PATH", "certs/dummy.p12"),
                        env("NFSE_CERT_PASSWORD", "changeit"),
                        env("NFSE_CERT_TYPE", "PKCS12")),
                // RSA-SHA256: the JDK's XMLDSig verifier refuses SHA-1 outright and
                // SEFIN accepts SHA-256 (resolved live 2026-08-28).
                new NfseProperties.Signature(SignatureAlgorithm.RSA_SHA256),
                new NfseProperties.Http(
                        Duration.ofSeconds(Long.parseLong(env("NFSE_CONNECT_TIMEOUT_SECONDS", "10"))),
                        Duration.ofSeconds(Long.parseLong(env("NFSE_READ_TIMEOUT_SECONDS", "60")))));
    }

    /**
     * Where the numbering counters and emission records live. This directory is
     * the only state the service keeps, and losing it means losing both the
     * numbering position and the local copy of every note issued — mount it.
     */
    public static Path dataDir() {
        return Path.of(env("NFSE_DATA_DIR", "data"));
    }

    /** The port the HTTP API listens on. */
    public static int port() {
        return Integer.parseInt(env("NFSE_PORT", "8080"));
    }

    /**
     * How many DANFSe renders may run at once. A render is CPU-bound, so more of
     * them in flight than there are cores buys no throughput and costs memory —
     * measured: 16 concurrent renders shed over half the requests in a 96 MiB
     * container. {@code availableProcessors()} already respects the cgroup CPU
     * quota, so this tracks the box the service was actually given.
     */
    public static int maxConcurrentRenders() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Integer.parseInt(env("NFSE_MAX_CONCURRENT_RENDERS", String.valueOf(Math.max(2, cores))));
    }

    /** How long a render may queue for a slot before the caller gets a 529. */
    public static Duration renderQueueTimeout() {
        return Duration.ofSeconds(Long.parseLong(env("NFSE_RENDER_QUEUE_TIMEOUT_SECONDS", "10")));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
