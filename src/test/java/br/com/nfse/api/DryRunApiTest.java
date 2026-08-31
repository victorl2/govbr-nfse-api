package br.com.nfse.api;

import br.com.nfse.certificate.CertificateLoader;
import br.com.nfse.config.BrasiliaTime;
import br.com.nfse.danfse.DanfseGenerator;
import br.com.nfse.dps.DpsSchemaValidator;
import br.com.nfse.dryrun.DpsDryRunService;
import br.com.nfse.dryrun.DpsLinter;
import br.com.nfse.dryrun.SignatureVerifier;
import br.com.nfse.emission.NfseEmissionService;
import br.com.nfse.health.HealthCheck;
import br.com.nfse.event.EventBuilder;
import br.com.nfse.event.NfseEventService;
import br.com.nfse.event.PedRegEventoSchemaValidator;
import br.com.nfse.sefin.SefinClient;
import br.com.nfse.signing.EnvelopedXmlSigner;
import br.com.nfse.testsupport.DryRunTestSupport;
import br.com.nfse.testsupport.TestApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The internal dry-run endpoint over real HTTP. */
class DryRunApiTest {

    @TempDir
    static Path tempDir;
    static Path p12;

    @BeforeAll
    static void dummyCert() throws Exception {
        p12 = DryRunTestSupport.generateDummyCert(tempDir);
    }

    private TestApi api() {
        SefinClient sefinClient = new SefinClient(
                RestClient.builder().baseUrl("https://sefin.test/SefinNacional").build());
        EnvelopedXmlSigner signer =
                new EnvelopedXmlSigner(DryRunTestSupport.localProps(p12), DryRunTestSupport.loader(p12));
        SignatureVerifier verifier = new SignatureVerifier();
        DpsDryRunService dryRun = new DpsDryRunService(new DpsSchemaValidator(),
                new DpsLinter(DryRunTestSupport.localProps(p12)), signer, verifier);
        ApiRoutes routes = new ApiRoutes(
                new NfseEmissionService(DryRunTestSupport.builder(p12), dryRun, sefinClient,
                        DryRunTestSupport.numbering(), DryRunTestSupport.emissions()),
                new NfseEventService(new EventBuilder(DryRunTestSupport.localProps(p12), BrasiliaTime.clock()),
                        new PedRegEventoSchemaValidator(), signer, verifier, sefinClient),
                sefinClient, DryRunTestSupport.adn(), new DanfseGenerator(), dryRun,
                new CertificateLoader(DryRunTestSupport.localProps(p12)),
                new HealthCheck(new CertificateLoader(DryRunTestSupport.localProps(p12))),
                DryRunTestSupport.renderGate(),
                DryRunTestSupport.numbering(), DryRunTestSupport.emissions(),
                DryRunTestSupport.localProps(p12), br.com.nfse.http.Json.mapper());
        return TestApi.serving(routes::register);
    }

    @Test
    void validDpsReturnsValidReport() {
        try (TestApi api = api()) {
            var r = api.postXml("/internal/dry-run", DryRunTestSupport.validDps());
            assertEquals(200, r.status());
            assertEquals("true", r.at("valid"));
            assertEquals(0, r.json().get("findings").size());
        }
    }

    /**
     * The fixture is a Rio/Simples DPS; a caller expecting São Paulo + Não Optante
     * must get LOC001 and SN001 findings for the same document.
     */
    @Test
    void expectationsArriveAsQueryParameters() {
        try (TestApi api = api()) {
            var r = api.postXml("/internal/dry-run?expectedMunicipality=3550308&expectedOpSimpNac=1",
                    DryRunTestSupport.validDps());
            assertEquals(200, r.status());
            assertTrue(r.text().contains("LOC001"), r.text());
            assertTrue(r.text().contains("SN001"), r.text());
        }
    }

    @Test
    void omittedExpectationsSkipThoseChecks() {
        try (TestApi api = api()) {
            var r = api.postXml("/internal/dry-run", DryRunTestSupport.validDps());
            assertTrue(!r.text().contains("LOC001") && !r.text().contains("SN001"), r.text());
        }
    }

    @Test
    void malformedXmlIsReportedNotThrown() {
        try (TestApi api = api()) {
            var r = api.postXml("/internal/dry-run", "<DPS><unclosed>");
            assertEquals(200, r.status());
            assertEquals("false", r.at("valid"));
            assertTrue(r.text().contains("XSD000"), r.text());
        }
    }

    @Test
    void certificateMetadataIsExposedForDiagnostics() {
        try (TestApi api = api()) {
            var r = api.get("/internal/certificate");
            assertEquals(200, r.status());
            assertTrue(r.at("subject").contains("NFSE DUMMY"), r.text());
        }
    }
}
