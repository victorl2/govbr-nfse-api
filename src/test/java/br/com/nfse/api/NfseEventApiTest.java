package br.com.nfse.api;

import br.com.nfse.certificate.CertificateLoader;
import br.com.nfse.config.BrasiliaTime;
import br.com.nfse.danfse.DanfseGenerator;
import br.com.nfse.dps.DpsSchemaValidator;
import br.com.nfse.dryrun.DpsDryRunService;
import br.com.nfse.dryrun.DpsLinter;
import br.com.nfse.dryrun.SignatureVerifier;
import br.com.nfse.emission.NfseEmissionService;
import br.com.nfse.event.CancelEventRequest;
import br.com.nfse.event.EventBuilder;
import br.com.nfse.event.NfseEventService;
import br.com.nfse.event.PedRegEventoSchemaValidator;
import br.com.nfse.health.HealthCheck;
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

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** The event endpoints over real HTTP. */
class NfseEventApiTest {

    private static final String CHAVE = "33045572212345678000195000000000000126081270635662";

    @TempDir
    static Path tempDir;
    static Path p12;

    MockRestServiceServer sefinMock;

    @BeforeAll
    static void dummyCert() throws Exception {
        p12 = DryRunTestSupport.generateDummyCert(tempDir);
    }

    private TestApi api() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://sefin.test/SefinNacional");
        sefinMock = MockRestServiceServer.bindTo(builder).build();
        SefinClient sefinClient = new SefinClient(builder.build());
        EnvelopedXmlSigner signer =
                new EnvelopedXmlSigner(DryRunTestSupport.localProps(p12), DryRunTestSupport.loader(p12));
        SignatureVerifier verifier = new SignatureVerifier();
        DpsDryRunService dryRun = new DpsDryRunService(new DpsSchemaValidator(),
                new DpsLinter(DryRunTestSupport.localProps(p12)), signer, verifier);
        NfseEventService events = new NfseEventService(
                new EventBuilder(DryRunTestSupport.localProps(p12), BrasiliaTime.clock()),
                new PedRegEventoSchemaValidator(), signer, verifier, sefinClient);
        NfseEmissionService emission = new NfseEmissionService(
                DryRunTestSupport.builder(p12), dryRun, sefinClient,
                DryRunTestSupport.numbering(), DryRunTestSupport.emissions());
        ApiRoutes routes = new ApiRoutes(emission, events, sefinClient, DryRunTestSupport.adn(), new DanfseGenerator(),
                dryRun, new CertificateLoader(DryRunTestSupport.localProps(p12)),
                new HealthCheck(new CertificateLoader(DryRunTestSupport.localProps(p12))),
                DryRunTestSupport.renderGate(),
                DryRunTestSupport.numbering(), DryRunTestSupport.emissions(),
                DryRunTestSupport.localProps(p12), br.com.nfse.http.Json.mapper());
        return TestApi.serving(routes::register);
    }

    private static CancelEventRequest cancellation() {
        return new CancelEventRequest("12345678000195", null, "1",
                "Erro na emissao: valor do servico incorreto");
    }

    private void expectRegisteredEvent() {
        sefinMock.expect(request -> { }).andRespond(
                withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(
                        "{\"tipoAmbiente\":2,\"eventoXmlGZipB64\":\""
                                + GzipB64TestCodec.encode("<evento versao=\"1.01\"/>") + "\"}"));
    }

    @Test
    void cancellingANoteRegistersTheEvent() {
        try (TestApi api = api()) {
            expectRegisteredEvent();
            var r = api.send("DELETE", "/nfse/" + CHAVE + "/cancel", "application/json",
                    "{\"cnpjAutor\":\"12345678000195\",\"cMotivo\":\"1\","
                            + "\"xMotivo\":\"Erro na emissao: valor do servico incorreto\"}");
            assertEquals(200, r.status());
            assertEquals("REGISTERED", r.at("status"));
            assertEquals("<evento versao=\"1.01\"/>", r.at("eventoXml"));
            assertTrue(r.at("pedidoXml").contains("Signature"));
        }
    }

    /**
     * A body on DELETE is legal but routinely stripped by proxies and unsupported by
     * some HTTP clients, and the motive is mandatory — so the same path accepts POST.
     */
    @Test
    void thePostAliasExistsForClientsThatCannotSendABodyOnDelete() {
        try (TestApi api = api()) {
            expectRegisteredEvent();
            var r = api.postJson("/nfse/" + CHAVE + "/cancel", cancellation());
            assertEquals(200, r.status());
            assertEquals("REGISTERED", r.at("status"));
        }
    }

    /** A motive too short for the XSD is refused locally, without troubling SEFIN. */
    @Test
    void aCancellationWithAnUnusableMotiveIsRejectedLocally() {
        try (TestApi api = api()) {
            var r = api.postJson("/nfse/" + CHAVE + "/cancel",
                    new CancelEventRequest("12345678000195", null, "1", "curto"));
            assertEquals(200, r.status());
            assertEquals("REJECTED_LOCALLY", r.at("status"));
            assertTrue(r.text().contains("XSD"), r.text());
            sefinMock.verify(); // nothing may have been sent
        }
    }

    /** An author is mandatory: without one the request never reaches the builder. */
    @Test
    void aCancellationWithoutAnAuthorIsBadRequest() {
        try (TestApi api = api()) {
            var r = api.postJson("/nfse/" + CHAVE + "/cancel",
                    new CancelEventRequest(null, null, "1", "Erro na emissao do documento"));
            assertEquals(400, r.status());
            assertTrue(r.at("message").contains("cnpjAutor"), r.text());
        }
    }

    @Test
    void aRegisteredEventCanBeReadBack() {
        try (TestApi api = api()) {
            sefinMock.expect(request -> { }).andRespond(
                    withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(
                            "{\"tipoAmbiente\":2,\"eventoXmlGZipB64\":\""
                                    + GzipB64TestCodec.encode("<evento versao=\"1.01\"/>") + "\"}"));
            var r = api.get("/nfse/" + CHAVE + "/eventos/101101/1");
            assertEquals(200, r.status());
            assertEquals("<evento versao=\"1.01\"/>", r.text());
        }
    }
}
