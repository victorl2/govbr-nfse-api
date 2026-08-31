package br.com.nfse.api;

import br.com.nfse.certificate.CertificateLoader;
import br.com.nfse.config.BrasiliaTime;
import br.com.nfse.danfse.DanfseGenerator;
import br.com.nfse.dps.DpsSchemaValidator;
import br.com.nfse.dryrun.DpsDryRunService;
import br.com.nfse.dryrun.DpsLinter;
import br.com.nfse.dryrun.SignatureVerifier;
import br.com.nfse.emission.DpsBuilder;
import br.com.nfse.emission.EmitNfseRequest;
import br.com.nfse.emission.NfseEmissionService;
import br.com.nfse.event.EventBuilder;
import br.com.nfse.event.NfseEventService;
import br.com.nfse.event.PedRegEventoSchemaValidator;
import br.com.nfse.health.HealthCheck;
import br.com.nfse.http.ConcurrencyGate;
import br.com.nfse.sefin.SefinClient;
import br.com.nfse.signing.EnvelopedXmlSigner;
import br.com.nfse.testsupport.DryRunTestSupport;
import br.com.nfse.testsupport.GzipB64TestCodec;
import br.com.nfse.testsupport.TestApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * The fiscal API over real HTTP, against the JDK server the service actually
 * runs on. Replaces the MockMvc tests that went with Spring MVC.
 */
class NfseApiTest {

    private static final String CHAVE = "33045572212345678000195000000000000126081270635662";

    @TempDir
    static Path tempDir;
    static Path p12;

    MockRestServiceServer sefinMock;

    @BeforeAll
    static void dummyCert() throws Exception {
        p12 = DryRunTestSupport.generateDummyCert(tempDir);
    }

    /** The whole object graph, wired exactly as {@code NfseApplication} wires it. */
    private TestApi api() {
        return TestApi.serving(routesWithCert(p12)::register);
    }

    private ApiRoutes routesWithGate(ConcurrencyGate gate) {
        return routes(p12, gate);
    }

    private ApiRoutes routesWithCert(Path cert) {
        return routes(cert, DryRunTestSupport.renderGate());
    }

    private ApiRoutes routes(Path cert, ConcurrencyGate gate) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://sefin.test/SefinNacional");
        sefinMock = MockRestServiceServer.bindTo(builder).build();
        SefinClient sefinClient = new SefinClient(builder.build());
        var props = DryRunTestSupport.localProps(cert);
        CertificateLoader loader = new CertificateLoader(props);
        EnvelopedXmlSigner signer = new EnvelopedXmlSigner(props, loader);
        SignatureVerifier verifier = new SignatureVerifier();
        DpsDryRunService dryRun = new DpsDryRunService(new DpsSchemaValidator(),
                new DpsLinter(props), signer, verifier);
        NfseEmissionService emission = new NfseEmissionService(
                DryRunTestSupport.builder(p12), dryRun, sefinClient,
                DryRunTestSupport.numbering(), DryRunTestSupport.emissions());
        NfseEventService events = new NfseEventService(
                new EventBuilder(props, BrasiliaTime.clock()),
                new PedRegEventoSchemaValidator(), signer, verifier, sefinClient);
        return new ApiRoutes(emission, events, sefinClient, DryRunTestSupport.adn(), new DanfseGenerator(),
                dryRun, loader, new HealthCheck(loader), gate,
                DryRunTestSupport.numbering(), DryRunTestSupport.emissions(), props, br.com.nfse.http.Json.mapper());
    }

    @Test
    void validateAcceptsJsonAndReportsValid() {
        try (TestApi api = api()) {
            var r = api.postJson("/nfse/validate", DryRunTestSupport.sampleRequest());
            assertEquals(200, r.status());
            assertEquals("true", r.at("valid"));
            assertEquals(0, r.json().get("findings").size());
            assertNotNull(r.at("dpsXml"));
        }
    }

    @Test
    void sendAcceptsJsonSubmitsAndReturnsAuthorized() {
        try (TestApi api = api()) {
            sefinMock.expect(request -> { }).andRespond(
                    withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                            {"tipoAmbiente":2,"versaoAplicativo":"1.0","dataHoraProcessamento":"x",
                             "idDps":"1","chaveAcesso":"CHAVE123",
                             "nfseXmlGZipB64":"H4sIAEuikWoC/7PxcwtOVShLLSpOzLdVMtQzMFTStwMADbmk+hUAAAA="}
                            """));
            var r = api.postJson("/nfse/send", DryRunTestSupport.sampleRequest());
            assertEquals(200, r.status());
            assertEquals("AUTHORIZED", r.at("status"));
            assertEquals("CHAVE123", r.at("chaveAcesso"));
        }
    }

    @Test
    void sendWithoutIbsCbsComesBackRejectedLocally() {
        try (TestApi api = api()) {
            EmitNfseRequest req = DryRunTestSupport.sampleRequest();
            // regime-normal emitter without the (mandatory-for-them) IBS/CBS group
            EmitNfseRequest without = new EmitNfseRequest(
                    new EmitNfseRequest.Emitter("12345678000195", null, "3304557", "1", null, "0"),
                    req.dps(), req.service(), req.values(), null, null, null, null);
            var r = api.postJson("/nfse/send", without);
            assertEquals(200, r.status());
            assertEquals("REJECTED_LOCALLY", r.at("status"));
            assertTrue(r.text().contains("RTC001"), r.text());
        }
    }

    @Test
    void danfsePdfIsGeneratedFromAFetchedNote() throws Exception {
        try (TestApi api = api()) {
            String nfseXml = new String(
                    getClass().getResourceAsStream("/dps/nfse-export-sample.xml").readAllBytes(),
                    StandardCharsets.UTF_8);
            sefinMock.expect(request -> { }).andRespond(withStatus(HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"chaveAcesso\":\"CH1\",\"nfseXmlGZipB64\":\""
                            + GzipB64TestCodec.encode(nfseXml) + "\"}"));
            var r = api.get("/nfse/" + CHAVE + "/danfse");
            assertEquals(200, r.status());
            assertTrue(r.isPdf(), "response is not a PDF");
        }
    }

    /** A DANFSe can also be rendered from an XML the caller already holds. */
    @Test
    void danfsePdfIsGeneratedFromASuppliedXml() throws Exception {
        try (TestApi api = api()) {
            String nfseXml = new String(
                    getClass().getResourceAsStream("/dps/nfse-export-sample.xml").readAllBytes(),
                    StandardCharsets.UTF_8);
            var r = api.postXml("/nfse/danfse", nfseXml);
            assertEquals(200, r.status());
            assertTrue(r.isPdf(), "response is not a PDF");
        }
    }

    @Test
    void structurallyIncompleteJsonIsBadRequest() {
        try (TestApi api = api()) {
            var r = api.send("POST", "/nfse/send", "application/json", "{\"dps\": {\"serie\": \"1\"}}");
            assertEquals(400, r.status());
            assertTrue(r.at("message").contains("required"), r.text());
        }
    }

    @Test
    void malformedJsonIsBadRequestNotAServerError() {
        try (TestApi api = api()) {
            var r = api.send("POST", "/nfse/send", "application/json", "{not json");
            assertEquals(400, r.status());
        }
    }

    @Test
    void anUnknownPathIsNotFound() {
        try (TestApi api = api()) {
            assertEquals(404, api.get("/nfse/nope/nothing/here").status());
        }
    }

    @Test
    void healthReportsUpAndCarriesTheCertificateExpiry() {
        try (TestApi api = api()) {
            var r = api.get("/health");
            assertEquals(200, r.status());
            assertEquals("UP", r.at("status"));
            assertNotNull(r.at("certificate/daysToExpiry"),
                    "health must expose the expiry an operator needs to alert on");
        }
    }

    /**
     * Overload is answered with 529, distinct from the 503 that means "cannot
     * issue notes at all" — a caller can retry the first and must alert on the
     * second.
     */
    @Test
    void renderingAnswers529WhenTheGateIsSaturated() throws Exception {
        ConcurrencyGate saturated = new ConcurrencyGate(1, Duration.ofMillis(50));
        // Hold the only permit for the duration of the request under test.
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            try {
                saturated.call(() -> {
                    holding.countDown();
                    release.await();
                    return null;
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(holding.await(5, TimeUnit.SECONDS));

        try (TestApi api = TestApi.serving(routesWithGate(saturated)::register)) {
            String nfseXml = new String(
                    getClass().getResourceAsStream("/dps/nfse-export-sample.xml").readAllBytes(),
                    StandardCharsets.UTF_8);
            var r = api.postXml("/nfse/danfse", nfseXml);
            assertEquals(529, r.status(), r.text());
            assertTrue(r.at("message").contains("concurrent"), r.text());
        } finally {
            release.countDown();
            holder.join();
        }
    }

    /** An expired e-CNPJ signs nothing, so the probe has to fail rather than lie. */
    @Test
    void healthIs503WhenTheCertificateHasExpired() throws Exception {
        Path expired = DryRunTestSupport.generateCert(tempDir, "expired-api.p12", "-400d", 1);
        try (TestApi api = TestApi.serving(routesWithCert(expired)::register)) {
            var r = api.get("/health");
            assertEquals(503, r.status());
            assertEquals("DOWN", r.at("status"));
        }
    }
}
