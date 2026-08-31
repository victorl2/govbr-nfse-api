package br.com.nfse.emission;

import br.com.nfse.dps.DpsSchemaValidator;
import br.com.nfse.dryrun.DpsDryRunService;
import br.com.nfse.dryrun.DpsLinter;
import br.com.nfse.dryrun.SignatureVerifier;
import br.com.nfse.http.Json;
import br.com.nfse.sefin.SefinClient;
import br.com.nfse.signing.EnvelopedXmlSigner;
import br.com.nfse.store.EmissionRecord;
import br.com.nfse.store.EmissionStore;
import br.com.nfse.store.NumberingStore;
import br.com.nfse.testsupport.DryRunTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Emission is only finished when the note is *written down*. These tests cover
 * the two things the service could not do before: hand out a número that has
 * never been used, and keep the authorised document afterwards.
 */
class EmissionPersistenceTest {

    private static final String NFSE_GZIP_B64 = "H4sIAEuikWoC/7PxcwtOVShLLSpOzLdVMtQzMFTStwMADbmk+hUAAAA=";

    @TempDir
    static Path certDir;
    static Path p12;

    @TempDir
    Path dataDir;

    MockRestServiceServer server;
    NumberingStore numbering;
    EmissionStore emissions;

    @BeforeAll
    static void dummyCert() throws Exception {
        p12 = DryRunTestSupport.generateDummyCert(certDir);
    }

    NfseEmissionService service;

    @BeforeEach
    void wire() {
        numbering = new NumberingStore(dataDir);
        emissions = new EmissionStore(dataDir, Json.mapper());
        RestClient.Builder builder = RestClient.builder().baseUrl("https://sefin.test/SefinNacional");
        server = MockRestServiceServer.bindTo(builder).build();
        var props = DryRunTestSupport.localProps(p12);
        DpsDryRunService dryRun = new DpsDryRunService(
                new DpsSchemaValidator(), new DpsLinter(props),
                new EnvelopedXmlSigner(props, DryRunTestSupport.loader(p12)),
                new SignatureVerifier());
        service = new NfseEmissionService(DryRunTestSupport.builder(p12), dryRun,
                new SefinClient(builder.build()), numbering, emissions);
    }

    private void sefinAuthorizes(String chave) {
        server.expect(request -> { }).andRespond(
                withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"chaveAcesso\":\"" + chave + "\",\"nfseXmlGZipB64\":\""
                                + NFSE_GZIP_B64 + "\"}"));
    }

    /** A request that omits the número gets one allocated rather than rejected. */
    @Test
    void allocatesTheNumberWhenTheCallerOmitsIt() {
        numbering.seed("1", 18);   // 1–18 already consumed in restrita
        sefinAuthorizes("CHAVE-19");

        SendNfseResponse response = service.send(withNumber(null));

        assertEquals(EmissionStatus.AUTHORIZED, response.status());
        assertEquals(19, numbering.lastAllocated("1"));
        assertTrue(response.dpsXml().contains("<nDPS>19</nDPS>"),
                "the built document should carry the allocated número");
    }

    @Test
    void consecutiveEmissionsNeverReuseANumber() {
        // Both expectations up front: MockRestServiceServer refuses new ones once
        // a request has been made.
        sefinAuthorizes("CHAVE-1");
        sefinAuthorizes("CHAVE-2");

        service.send(withNumber(null));
        service.send(withNumber(null));

        assertEquals(2, numbering.lastAllocated("1"));
        assertEquals(2, emissions.recent(10).size());
        assertEquals(List.of("2", "1"),
                emissions.recent(10).stream().map(EmissionRecord::number).toList());
    }

    /** Nothing was sent, so the número must go back rather than leaving a gap. */
    @Test
    void releasesTheNumberWhenTheDocumentIsRejectedLocally() {
        EmitNfseRequest invalid = regimeNormalWithoutIbsCbs();

        SendNfseResponse response = service.send(invalid);

        assertEquals(EmissionStatus.REJECTED_LOCALLY, response.status());
        assertEquals(0, numbering.lastAllocated("1"), "a locally rejected document must not spend a número");
        assertEquals(0, emissions.recent(10).size(), "nothing was sent, so nothing is recorded");
    }

    @Test
    void persistsTheAuthorizedNoteAndItsSignedDps() {
        sefinAuthorizes("CHAVE-42");

        SendNfseResponse response = service.send(withNumber("42"));

        EmissionRecord record = emissions.byChave("CHAVE-42").orElseThrow();
        assertEquals(EmissionRecord.Status.AUTHORIZED, record.status());
        assertEquals("42", record.number());
        assertEquals("1", record.serie());
        assertNotNull(record.createdAt());
        assertTrue(emissions.dpsXml(record.dpsId()).orElseThrow().contains("<Signature"),
                "the stored DPS must be the signed one that was actually submitted");
        assertNotNull(emissions.nfseXml(record.dpsId()).orElse(null),
                "the authorised NFS-e must be kept");
    }

    /** SEFIN refused it: the número is spent and that has to be visible. */
    @Test
    void recordsARejectionBySefin() {
        server.expect(request -> { }).andRespond(
                withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"erros\":[{\"Codigo\":\"E0312\",\"Descricao\":\"cTribMun ausente\"}]}"));

        SendNfseResponse response = service.send(withNumber("7"));

        assertEquals(EmissionStatus.REJECTED_BY_SEFIN, response.status());
        EmissionRecord record = emissions.recent(1).get(0);
        assertEquals(EmissionRecord.Status.REJECTED_BY_SEFIN, record.status());
        assertEquals("7", record.number());
    }

    /**
     * Re-sending the same (série, número) must not produce a second note: the
     * dpsId is deterministic, so the stored record answers instead.
     */
    @Test
    void repeatingAnAlreadyAuthorizedNumberReturnsTheStoredNote() {
        sefinAuthorizes("CHAVE-99");
        SendNfseResponse first = service.send(withNumber("99"));
        assertEquals(EmissionStatus.AUTHORIZED, first.status());

        // No SEFIN expectation this time: a second submission would fail the mock.
        SendNfseResponse second = service.send(withNumber("99"));

        assertEquals(EmissionStatus.AUTHORIZED, second.status());
        assertEquals(first.chaveAcesso(), second.chaveAcesso());
        assertEquals(1, emissions.recent(10).size(), "the note must not be recorded twice");
    }

    private EmitNfseRequest withNumber(String number) {
        EmitNfseRequest base = DryRunTestSupport.sampleRequest();
        return new EmitNfseRequest(base.emitter(),
                new EmitNfseRequest.Dps("1", number, base.dps().dhEmi(), base.dps().dCompet()),
                base.service(), base.values(), base.ibsCbs(), base.tomador(),
                base.intermediario(), base.comercioExterior(), base.substituicao());
    }

    private EmitNfseRequest regimeNormalWithoutIbsCbs() {
        EmitNfseRequest base = withNumber(null);
        return new EmitNfseRequest(
                new EmitNfseRequest.Emitter("12345678000195", null, "3304557", "1", null, "0"),
                base.dps(), base.service(), base.values(), null, null, null, null);
    }
}
