package br.com.nfse.event;

import br.com.nfse.config.BrasiliaTime;
import br.com.nfse.dryrun.SignatureVerifier;
import br.com.nfse.dryrun.Stage;
import br.com.nfse.sefin.SefinClient;
import br.com.nfse.signing.EnvelopedXmlSigner;
import br.com.nfse.testsupport.DryRunTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class NfseEventServiceTest {

    private static final String CHAVE = "33045572212345678000195000000000000126081270635662";
    private static final String EVENTOS_URL = "https://sefin.test/SefinNacional/nfse/" + CHAVE + "/eventos";
    /** gzip+base64 of a minimal evento document. */
    private static final String EVENTO_GZIP_B64 = "H4sIAEuikWoC/7PxcwtOVShLLSpOzLdVMtQzMFTStwMADbmk+hUAAAA=";

    @TempDir
    static Path tempDir;
    static Path p12;

    MockRestServiceServer server;

    @BeforeAll
    static void dummyCert() throws Exception {
        p12 = DryRunTestSupport.generateDummyCert(tempDir);
    }

    private NfseEventService service() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://sefin.test/SefinNacional");
        server = MockRestServiceServer.bindTo(builder).build();
        return new NfseEventService(
                new EventBuilder(DryRunTestSupport.localProps(p12), BrasiliaTime.clock()),
                new PedRegEventoSchemaValidator(),
                new EnvelopedXmlSigner(DryRunTestSupport.localProps(p12), DryRunTestSupport.loader(p12)),
                new SignatureVerifier(),
                new SefinClient(builder.build()));
    }

    private static CancelEventRequest cancellation() {
        return new CancelEventRequest("12345678000195", null, "1",
                "Erro na emissao: valor do servico incorreto");
    }

    @Test
    void cancellationIsSignedSubmittedAndTheRegisteredEventComesBack() {
        NfseEventService service = service();
        server.expect(requestTo(EVENTOS_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"tipoAmbiente":2,"versaoAplicativo":"1.0","dataHoraProcessamento":"x",
                         "eventoXmlGZipB64":"%s"}
                        """.formatted(EVENTO_GZIP_B64)));

        EventResponse response = service.cancel(CHAVE, cancellation());
        assertEquals(EventStatus.REGISTERED, response.status());
        assertTrue(response.pedidoXml().contains("Signature"), "the submitted pedido is signed");
        assertNotNull(response.eventoXml(), "the registered evento XML must be returned");
        server.verify();
    }

    /** The eventos endpoint reports a single {@code erro} object, not the {@code erros[]} of emission. */
    @Test
    void aSefinRejectionBecomesSefinStageFindings() {
        NfseEventService service = service();
        server.expect(requestTo(EVENTOS_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"tipoAmbiente":2,"versaoAplicativo":"1.0","dataHoraProcessamento":"x",
                         "erro":{"Codigo":"E0501","Descricao":"prazo de cancelamento expirado","Complemento":null}}
                        """));

        EventResponse response = service.cancel(CHAVE, cancellation());
        assertEquals(EventStatus.REJECTED_BY_SEFIN, response.status());
        assertTrue(response.findings().stream()
                        .anyMatch(f -> f.stage() == Stage.SEFIN && "E0501".equals(f.code())),
                "expected the SEFIN erro surfaced as a finding, got: " + response.findings());
        server.verify();
    }

    /** The dry run signs and checks the pedido but must never claim it was registered. */
    @Test
    void validatingACancellationNeverSubmitsItNorClaimsItWasRegistered() {
        NfseEventService service = service();
        EventResponse response = service.validateCancellation(CHAVE, cancellation());

        assertEquals(EventStatus.VALIDATED, response.status());
        assertTrue(response.valid());
        assertTrue(response.pedidoXml().contains("Signature"), "the dry run still proves signing works");
        assertNull(response.eventoXml(), "nothing was registered, so there is no evento");
        server.verify(); // zero expectations: no HTTP call may have happened
    }

    /**
     * xMotivo has a 15-character minimum in the XSD. A pedido that cannot pass the
     * schema must never reach SEFIN — the same rule the emission path follows.
     */
    @Test
    void aLocallyInvalidCancellationNeverReachesSefin() {
        NfseEventService service = service();
        EventResponse response = service.cancel(CHAVE,
                new CancelEventRequest("12345678000195", null, "1", "curto"));

        assertEquals(EventStatus.REJECTED_LOCALLY, response.status());
        assertFalse(response.valid());
        assertTrue(response.findings().stream().anyMatch(f -> f.stage() == Stage.XSD),
                "expected an XSD finding, got: " + response.findings());
        server.verify(); // zero expectations: no HTTP call may have happened
    }

    @Test
    void aTransportFailureIsSubmitFailedNotAnException() {
        NfseEventService service = service();
        server.expect(requestTo(EVENTOS_URL)).andExpect(method(POST))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withException(new java.net.SocketTimeoutException("timed out")));

        EventResponse response = service.cancel(CHAVE, cancellation());
        assertEquals(EventStatus.SUBMIT_FAILED, response.status());
        assertTrue(response.findings().stream().anyMatch(f -> f.stage() == Stage.SEFIN),
                "expected a SEFIN-stage finding: " + response.findings());
    }
}
