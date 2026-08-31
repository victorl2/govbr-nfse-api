package br.com.nfse.dryrun;

import br.com.nfse.dps.DpsSchemaValidator;
import br.com.nfse.signing.EnvelopedXmlSigner;
import br.com.nfse.testsupport.DryRunTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DpsDryRunServiceTest {

    @TempDir
    static Path tempDir;
    static Path p12;

    @BeforeAll
    static void dummyCert() throws Exception {
        p12 = DryRunTestSupport.generateDummyCert(tempDir);
    }

    private DpsDryRunService service() {
        return new DpsDryRunService(
                new DpsSchemaValidator(),
                new DpsLinter(DryRunTestSupport.localProps(p12)),
                new EnvelopedXmlSigner(DryRunTestSupport.localProps(p12), DryRunTestSupport.loader(p12)),
                new SignatureVerifier());
    }

    /**
     * {@code POST /internal/dry-run} parses XML the caller supplies, so a DOCTYPE
     * must be refused as a finding — never resolved into a local file read or an
     * outbound request.
     */
    @Test
    void aDocumentDeclaringAnExternalEntityIsRejectedNotResolved() throws Exception {
        Path secret = tempDir.resolve("dry-run-secret.txt");
        java.nio.file.Files.writeString(secret, "TOP-SECRET-CONTENT");
        String attack = DryRunTestSupport.validDps()
                .replaceFirst("(<\\?xml[^>]*\\?>)?", "<!DOCTYPE DPS [<!ENTITY xxe SYSTEM \""
                        + secret.toUri() + "\">]>")
                .replace("<xDescServ>", "<xDescServ>&xxe;");

        DryRunReport report = service().dryRun(attack, DryRunTestSupport.rioSimples());
        assertFalse(report.valid(), "a DOCTYPE-bearing document must not pass");
        assertTrue(report.findings().stream().anyMatch(f -> f.stage() == Stage.XSD),
                "expected an XSD-stage finding, got: " + report.findings());
        assertTrue(report.findings().stream().noneMatch(f -> f.message().contains("TOP-SECRET")),
                "the entity must never be resolved: " + report.findings());
    }

    @Test
    void validDpsPassesEveryStage() {
        DryRunReport report = service().dryRun(DryRunTestSupport.validDps(), DryRunTestSupport.rioSimples());
        assertTrue(report.valid(), "expected a clean pass, got: " + report.findings());
        assertEquals(0, report.findings().size());
    }

    @Test
    void lintErrorFailsTheRunButSigningStillExercised() {
        // regime-normal expectations: missing IBSCBS is an error for that profile
        String xml = DryRunTestSupport.validDps().replaceAll("(?s)<IBSCBS>.*</IBSCBS>", "");
        DryRunReport report = service().dryRun(xml, new LintExpectations("3304557", "1"));

        assertFalse(report.valid());
        assertTrue(report.findings().stream().anyMatch(f -> "RTC001".equals(f.code())));
        assertTrue(report.findings().stream()
                        .noneMatch(f -> (f.stage() == Stage.SIGN || f.stage() == Stage.VERIFY)
                                && f.severity() == Severity.ERROR),
                "signing and verification should still succeed: " + report.findings());
    }

    @Test
    void unparseableInputYieldsInvalidReport() {
        DryRunReport report = service().dryRun("this is not xml", DryRunTestSupport.rioSimples());
        assertFalse(report.valid());
        assertFalse(report.findings().isEmpty());
    }

    @Test
    void missingInfDpsIdIsReportedAsSignError() {
        String xml = DryRunTestSupport.validDps()
                .replace(" Id=\"DPS330455721234567800019500001000000000000001\"", "");
        DryRunReport report = service().dryRun(xml, DryRunTestSupport.rioSimples());
        assertFalse(report.valid());
        assertTrue(report.findings().stream()
                .anyMatch(f -> f.stage() == Stage.SIGN && f.severity() == Severity.ERROR));
    }
}
