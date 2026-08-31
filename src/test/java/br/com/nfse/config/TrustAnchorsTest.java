package br.com.nfse.config;

import org.junit.jupiter.api.Test;

import javax.net.ssl.X509ExtendedTrustManager;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustAnchorsTest {

    @Test
    void trustsTheJdkDefaultsPlusTheVendoredExtraRoots() {
        X509ExtendedTrustManager tm = TrustAnchors.compositeTrustManager();
        X509Certificate[] issuers = tm.getAcceptedIssuers();

        assertTrue(issuers.length > 50, "JDK default roots must remain trusted, got " + issuers.length);
        assertTrue(Arrays.stream(issuers).anyMatch(c ->
                        c.getSubjectX500Principal().getName().contains("GlobalSign Root R46")),
                "vendored GlobalSign Root R46 must be trusted (anchors the SERPRO/*.nfse.gov.br chain)");
    }
}
