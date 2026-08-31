package br.com.nfse.health;

import br.com.nfse.certificate.CertificateLoader;
import br.com.nfse.testsupport.DryRunTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Health is about the one thing that silently stops every emission: the e-CNPJ
 * certificate. A hardcoded "UP" cannot see it expiring.
 */
class HealthCheckTest {

    @TempDir
    Path tempDir;

    private HealthCheck checkFor(Path p12) {
        return new HealthCheck(new CertificateLoader(DryRunTestSupport.localProps(p12)));
    }

    @Test
    void reportsUpWithTheExpiryDateForAValidCertificate() throws Exception {
        Path p12 = DryRunTestSupport.generateDummyCert(tempDir);

        HealthReport report = checkFor(p12).report();

        assertEquals("UP", report.status());
        assertEquals(200, report.httpStatus());
        assertNotNull(report.certificate(), "the report must carry the certificate's expiry");
        assertTrue(report.certificate().daysToExpiry() > 300,
                "a freshly generated 365-day cert should have most of its life left");
        assertTrue(report.warnings().isEmpty(), report.warnings().toString());
    }

    @Test
    void warnsButStaysUpWhenTheCertificateExpiresSoon() throws Exception {
        // 20 days of life left: still perfectly usable, so killing the container
        // would be wrong — but the operator has to be told.
        Path p12 = DryRunTestSupport.generateCert(tempDir, "soon.p12", "-1d", 21);

        HealthReport report = checkFor(p12).report();

        assertEquals("UP", report.status());
        assertEquals(200, report.httpStatus());
        assertEquals(1, report.warnings().size(), report.warnings().toString());
        assertTrue(report.warnings().get(0).contains("expires"), report.warnings().toString());
    }

    @Test
    void reportsDownWhenTheCertificateHasAlreadyExpired() throws Exception {
        Path p12 = DryRunTestSupport.generateCert(tempDir, "expired.p12", "-400d", 1);

        HealthReport report = checkFor(p12).report();

        assertEquals("DOWN", report.status());
        assertEquals(503, report.httpStatus());
        assertTrue(report.warnings().toString().contains("expired"), report.warnings().toString());
    }

    @Test
    void reportsDownWhenTheCertificateCannotBeLoaded() {
        HealthReport report = checkFor(tempDir.resolve("does-not-exist.p12")).report();

        assertEquals("DOWN", report.status());
        assertEquals(503, report.httpStatus());
        assertTrue(report.warnings().toString().toLowerCase().contains("certificate"),
                report.warnings().toString());
    }
}
