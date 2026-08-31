package br.com.nfse.dryrun;

import br.com.nfse.signing.EnvelopedXmlSigner;
import br.com.nfse.testsupport.DryRunTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureVerifierTest {

    private static final String FIXTURE_ID = "DPS330455721234567800019500001000000000000001";

    @TempDir
    static Path tempDir;
    static Path p12;

    private final SignatureVerifier verifier = new SignatureVerifier();

    @BeforeAll
    static void dummyCert() throws Exception {
        p12 = DryRunTestSupport.generateDummyCert(tempDir);
    }

    private Document signedFixture() {
        Document doc = DryRunTestSupport.parse(DryRunTestSupport.validDps());
        new EnvelopedXmlSigner(DryRunTestSupport.localProps(p12), DryRunTestSupport.loader(p12))
                .signEnveloped(doc, FIXTURE_ID);
        return doc;
    }

    @Test
    void freshlySignedDocumentVerifies() {
        assertEquals(List.of(), verifier.verify(signedFixture()));
    }

    @Test
    void tamperingAfterSigningIsDetected() {
        Document doc = signedFixture();
        doc.getElementsByTagNameNS("http://www.sped.fazenda.gov.br/nfse", "vServ")
                .item(0).setTextContent("999999.00");
        List<ValidationFinding> findings = verifier.verify(doc);
        assertTrue(findings.stream().anyMatch(
                f -> f.stage() == Stage.VERIFY && f.severity() == Severity.ERROR),
                "tampered document must fail verification, got: " + findings);
    }

    @Test
    void unsignedDocumentReportsMissingSignature() {
        Document doc = DryRunTestSupport.parse(DryRunTestSupport.validDps());
        assertTrue(verifier.verify(doc).stream().anyMatch(f -> "SIG001".equals(f.code())));
    }
}
