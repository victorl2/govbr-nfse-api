package br.com.nfse.emission;

import br.com.nfse.dps.DpsSchemaValidator;
import br.com.nfse.dryrun.DpsDryRunService;
import br.com.nfse.dryrun.DpsLinter;
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
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class NfseEmissionServiceTest {

    private static final String EMIT_URL = "https://sefin.test/SefinNacional/nfse";
    private static final String DPS_URL =
            "https://sefin.test/SefinNacional/dps/DPS330455721234567800019500001000000000000001";
    private static final String NFSE_URL = "https://sefin.test/SefinNacional/nfse/CHAVE123";
    /** gzip+base64 of a minimal NFS-e document. */
    private static final String NFSE_GZIP_B64 = "H4sIAEuikWoC/7PxcwtOVShLLSpOzLdVMtQzMFTStwMADbmk+hUAAAA=";

    @TempDir
    static Path tempDir;
    static Path p12;

    MockRestServiceServer server;

    @BeforeAll
    static void dummyCert() throws Exception {
        p12 = DryRunTestSupport.generateDummyCert(tempDir);
    }

    private NfseEmissionService service() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://sefin.test/SefinNacional");
        server = MockRestServiceServer.bindTo(builder).build();
        DpsDryRunService dryRun = new DpsDryRunService(
                new DpsSchemaValidator(),
                new DpsLinter(DryRunTestSupport.localProps(p12)),
                new EnvelopedXmlSigner(DryRunTestSupport.localProps(p12), DryRunTestSupport.loader(p12)),
                new SignatureVerifier());
        return new NfseEmissionService(
                DryRunTestSupport.builder(p12), dryRun, new SefinClient(builder.build()),
                DryRunTestSupport.numbering(), DryRunTestSupport.emissions());
    }

    @Test
    void validateReturnsCleanReportAndTheBuiltXml() {
        ValidateNfseResponse response = service().validate(DryRunTestSupport.sampleRequest());
        assertTrue(response.valid(), "expected clean validation, got: " + response.findings());
        assertEquals(0, response.findings().size());
        assertTrue(response.dpsXml().contains("<infDPS"), "built DPS should be returned");
        server.verify(); // validate never talks to SEFIN
    }

    @Test
    void sendSubmitsTheSignedDpsAndReturnsAuthorized() {
        NfseEmissionService service = service();
        server.expect(requestTo(EMIT_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"tipoAmbiente":2,"versaoAplicativo":"1.0","dataHoraProcessamento":"x",
                         "idDps":"1","chaveAcesso":"CHAVE123","nfseXmlGZipB64":"H4sIAEuikWoC/7PxcwtOVShLLSpOzLdVMtQzMFTStwMADbmk+hUAAAA="}
                        """));

        SendNfseResponse response = service.send(DryRunTestSupport.sampleRequest());
        assertEquals(EmissionStatus.AUTHORIZED, response.status());
        assertTrue(response.valid());
        assertEquals("CHAVE123", response.chaveAcesso());
        assertNotNull(response.nfseXml(), "the decoded NFS-e XML must be returned");
        assertTrue(response.dpsXml().contains("Signature"), "the signed DPS is returned too");
        server.verify();
    }

    @Test
    void sefinRejectionBecomesSefinStageFindings() {
        NfseEmissionService service = service();
        server.expect(requestTo(EMIT_URL)).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"tipoAmbiente":2,"versaoAplicativo":"1.0","dataHoraProcessamento":"x",
                         "erros":[{"codigo":"E0100","descricao":"regra de negócio","complemento":null}]}
                        """));

        SendNfseResponse response = service.send(DryRunTestSupport.sampleRequest());
        assertEquals(EmissionStatus.REJECTED_BY_SEFIN, response.status());
        assertTrue(response.findings().stream()
                        .anyMatch(f -> f.stage() == Stage.SEFIN && "E0100".equals(f.code())),
                "expected the SEFIN erro surfaced as a finding, got: " + response.findings());
        server.verify();
    }

    @Test
    void locallyInvalidRequestsNeverReachSefin() {
        NfseEmissionService service = service();
        EmitNfseRequest req = DryRunTestSupport.sampleRequest();
        // regime-normal emitter without the (mandatory-for-them) IBS/CBS group
        EmitNfseRequest without = new EmitNfseRequest(
                new EmitNfseRequest.Emitter("12345678000195", null, "3304557", "1", null, "0"),
                req.dps(), req.service(), req.values(), null, null, null, null);

        SendNfseResponse response = service.send(without);
        assertEquals(EmissionStatus.REJECTED_LOCALLY, response.status());
        assertFalse(response.valid());
        assertTrue(response.findings().stream().anyMatch(f -> "RTC001".equals(f.code())));
        server.verify(); // zero expectations: no HTTP call may have happened
    }

    /**
     * A timeout does not mean the note was not created — SEFIN may have committed it
     * and lost the answer. Reusing the (série, número) for a second attempt would be a
     * duplicate emission, so the DPS id is looked up before giving up: if a note exists
     * for it, that note IS this request's result.
     */
    @Test
    void aTimeoutAfterSefinAlreadyCreatedTheNoteRecoversItInsteadOfDuplicating() {
        NfseEmissionService service = service();
        server.expect(requestTo(EMIT_URL)).andExpect(method(POST))
                .andRespond(withException(new java.net.SocketTimeoutException("timed out")));
        server.expect(requestTo(DPS_URL)).andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
                        {"tipoAmbiente":2,"versaoAplicativo":"1.0","dataHoraProcessamento":"x",
                         "idDps":"DPS330455721234567800019500001000000000000001","chaveAcesso":"CHAVE123"}
                        """));
        server.expect(requestTo(NFSE_URL)).andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(
                        "{\"chaveAcesso\":\"CHAVE123\",\"nfseXmlGZipB64\":\"" + NFSE_GZIP_B64 + "\"}"));

        SendNfseResponse response = service.send(DryRunTestSupport.sampleRequest());
        assertEquals(EmissionStatus.AUTHORIZED, response.status());
        assertEquals("CHAVE123", response.chaveAcesso());
        assertNotNull(response.nfseXml(), "the recovered note's XML must come back too");
        assertTrue(response.findings().stream().anyMatch(f -> "NET002".equals(f.code())),
                "the recovery must be reported, not hidden: " + response.findings());
        server.verify();
    }

    @Test
    void aTimeoutWithNoNoteAtSefinStaysSubmitFailed() {
        NfseEmissionService service = service();
        server.expect(requestTo(EMIT_URL)).andExpect(method(POST))
                .andRespond(withException(new java.net.SocketTimeoutException("timed out")));
        server.expect(requestTo(DPS_URL)).andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(
                        "{\"erro\":{\"Codigo\":\"E0001\",\"Descricao\":\"não encontrado\"}}"));

        SendNfseResponse response = service.send(DryRunTestSupport.sampleRequest());
        assertEquals(EmissionStatus.SUBMIT_FAILED, response.status());
        assertNull(response.chaveAcesso());
        assertTrue(response.findings().stream().anyMatch(f -> f.stage() == Stage.SEFIN),
                "expected a SEFIN-stage finding describing the failure: " + response.findings());
        server.verify();
    }

    /** When the probe itself is unreachable we must not claim the note is absent. */
    @Test
    void aTimeoutWhoseRecoveryProbeAlsoFailsStaysSubmitFailed() {
        NfseEmissionService service = service();
        server.expect(requestTo(EMIT_URL)).andExpect(method(POST))
                .andRespond(withException(new java.net.SocketTimeoutException("timed out")));
        server.expect(requestTo(DPS_URL)).andExpect(method(GET))
                .andRespond(withException(new java.net.SocketTimeoutException("timed out too")));

        SendNfseResponse response = service.send(DryRunTestSupport.sampleRequest());
        assertEquals(EmissionStatus.SUBMIT_FAILED, response.status());
        assertNull(response.chaveAcesso());
        server.verify();
    }
}
