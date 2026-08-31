package br.com.nfse.api;

import br.com.nfse.certificate.CertificateLoader;
import br.com.nfse.config.BrasiliaTime;
import br.com.nfse.danfse.DanfseGenerator;
import br.com.nfse.dps.DpsSchemaValidator;
import br.com.nfse.dryrun.DpsDryRunService;
import br.com.nfse.dryrun.DpsLinter;
import br.com.nfse.dryrun.SignatureVerifier;
import br.com.nfse.emission.NfseEmissionService;
import br.com.nfse.event.EventBuilder;
import br.com.nfse.event.NfseEventService;
import br.com.nfse.event.PedRegEventoSchemaValidator;
import br.com.nfse.health.HealthCheck;
import br.com.nfse.http.Json;
import br.com.nfse.sefin.SefinClient;
import br.com.nfse.signing.EnvelopedXmlSigner;
import br.com.nfse.store.EmissionStore;
import br.com.nfse.store.NumberingStore;
import br.com.nfse.testsupport.DryRunTestSupport;
import br.com.nfse.testsupport.TestApi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The numbering and stored-note endpoints over real HTTP. */
class NumberingApiTest {

    @TempDir
    static Path certDir;
    static Path p12;

    @TempDir
    Path dataDir;

    NumberingStore numbering;
    EmissionStore emissions;

    @BeforeAll
    static void dummyCert() throws Exception {
        p12 = DryRunTestSupport.generateDummyCert(certDir);
    }

    @BeforeEach
    void stores() {
        numbering = new NumberingStore(dataDir);
        emissions = new EmissionStore(dataDir, Json.mapper());
    }

    private TestApi api() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://sefin.test/SefinNacional");
        MockRestServiceServer.bindTo(builder).build();
        SefinClient sefinClient = new SefinClient(builder.build());
        var props = DryRunTestSupport.localProps(p12);
        CertificateLoader loader = new CertificateLoader(props);
        EnvelopedXmlSigner signer = new EnvelopedXmlSigner(props, loader);
        SignatureVerifier verifier = new SignatureVerifier();
        DpsDryRunService dryRun = new DpsDryRunService(new DpsSchemaValidator(),
                new DpsLinter(props), signer, verifier);
        ApiRoutes routes = new ApiRoutes(
                new NfseEmissionService(DryRunTestSupport.builder(p12), dryRun, sefinClient,
                        numbering, emissions),
                new NfseEventService(new EventBuilder(props, BrasiliaTime.clock()),
                        new PedRegEventoSchemaValidator(), signer, verifier, sefinClient),
                sefinClient, new DanfseGenerator(), dryRun, loader, new HealthCheck(loader),
                DryRunTestSupport.renderGate(), numbering, emissions, props, Json.mapper());
        return TestApi.serving(routes::register);
    }

    @Test
    void seedsAndReportsTheCounter() {
        try (TestApi api = api()) {
            var seeded = api.send("PUT", "/internal/numbering/1", "application/json",
                    "{\"lastConsumed\":18}");
            assertEquals(200, seeded.status(), seeded.text());
            assertEquals("18", seeded.at("1"));
            assertEquals("18", api.get("/internal/numbering").at("1"));
        }
    }

    /** Asking to lower the counter is an unsafe request, not a server fault. */
    @Test
    void refusingToLowerTheCounterIsABadRequestNotAServerError() {
        try (TestApi api = api()) {
            api.send("PUT", "/internal/numbering/1", "application/json", "{\"lastConsumed\":18}");

            var lowered = api.send("PUT", "/internal/numbering/1", "application/json",
                    "{\"lastConsumed\":5}");

            assertEquals(400, lowered.status(), lowered.text());
            assertTrue(lowered.at("message").contains("refusing to lower"), lowered.text());
            assertEquals("18", api.get("/internal/numbering").at("1"), "the counter must be unchanged");
        }
    }

    @Test
    void seedingWithoutABodyValueIsRejected() {
        try (TestApi api = api()) {
            var r = api.send("PUT", "/internal/numbering/1", "application/json", "{}");
            assertEquals(400, r.status(), r.text());
        }
    }

    @Test
    void readingAnUnknownAccessKeyIsNotFound() {
        try (TestApi api = api()) {
            assertEquals(404, api.get("/nfse/33045572212345678000195000000000000126081270635662").status());
        }
    }

    @Test
    void readsBackAStoredNoteAndItsXml() {
        String chave = "33045572212345678000195000000000000126081270635662";
        emissions.begin("DPS-TEST-1", "1", "19", "<DPS/>", BrasiliaTime.now());
        emissions.authorized("DPS-TEST-1", chave, "<NFSe>kept</NFSe>", BrasiliaTime.now());

        try (TestApi api = api()) {
            var record = api.get("/nfse/" + chave);
            assertEquals(200, record.status(), record.text());
            assertEquals("AUTHORIZED", record.at("status"));
            assertEquals("19", record.at("number"));

            var xml = api.get("/nfse/" + chave + "/xml");
            assertEquals(200, xml.status());
            assertTrue(xml.text().contains("<NFSe>kept</NFSe>"), xml.text());
        }
    }

    /** An operator reading /health must see a date, not a float of epoch seconds. */
    @Test
    void healthRendersTheExpiryAsAnIsoDate() {
        try (TestApi api = api()) {
            String notAfter = api.get("/health").at("certificate/notAfter");
            assertTrue(notAfter != null && notAfter.startsWith("20") && notAfter.contains("-"),
                    "expected an ISO-8601 instant, got: " + notAfter);
        }
    }
}
