package br.com.nfse.certificate;

import br.com.nfse.config.NfseProperties;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Enumeration;

/**
 * Loads the ICP-Brasil e-CNPJ (or, locally, a self-signed dummy) PKCS#12 keystore
 * once and caches the resulting {@link KeyMaterial}.
 *
 * <p>The same key material is used for two things — mTLS transport and XMLDSig —
 * see docs/06 §6.1. Issuer only matters for the remote checks, so a dummy cert is
 * fine for local dev (docs/08 §8.6.1).
 */
public class CertificateLoader {

    private final NfseProperties props;
    private volatile KeyMaterial cached;

    public CertificateLoader(NfseProperties props) {
        this.props = props;
    }

    public synchronized KeyMaterial load() {
        if (cached != null) {
            return cached;
        }
        NfseProperties.Certificate cfg = props.certificate();
        Path path = Path.of(cfg.path());
        if (!Files.exists(path)) {
            throw new IllegalStateException(
                    "Certificate not found at '" + path.toAbsolutePath() + "'. "
                            + "For local dev run ./scripts/generate-dummy-cert.sh; "
                            + "for restrita/produção set NFSE_CERT_PATH and NFSE_CERT_PASSWORD.");
        }
        char[] password = cfg.password() == null ? new char[0] : cfg.password().toCharArray();
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore ks = KeyStore.getInstance(cfg.typeOrDefault());
            ks.load(in, password);
            String alias = firstKeyAlias(ks);
            PrivateKey key = (PrivateKey) ks.getKey(alias, password);
            X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
            cached = new KeyMaterial(ks, password, alias, cert, key);
            return cached;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load certificate: " + e.getMessage(), e);
        }
    }

    /** Non-sensitive metadata for health/diagnostics. */
    public CertificateInfo info() {
        X509Certificate c = load().certificate();
        long days = ChronoUnit.DAYS.between(Instant.now(), c.getNotAfter().toInstant());
        return new CertificateInfo(
                c.getSubjectX500Principal().getName(),
                c.getIssuerX500Principal().getName(),
                c.getNotAfter().toInstant(),
                days);
    }

    private static String firstKeyAlias(KeyStore ks) throws Exception {
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String a = aliases.nextElement();
            if (ks.isKeyEntry(a)) {
                return a;
            }
        }
        throw new IllegalStateException("No private-key entry found in keystore");
    }
}
