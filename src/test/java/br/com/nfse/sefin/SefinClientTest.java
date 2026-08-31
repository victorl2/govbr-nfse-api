package br.com.nfse.sefin;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class SefinClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://sefin.test/SefinNacional");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final SefinClient client = new SefinClient(builder.build());

    @Test
    void emitPostsTheConfirmedEnvelopeAndParsesSuccess() {
        String nfseXml = "<NFSe versao=\"1.01\"><infNFSe/></NFSe>";
        server.expect(requestTo("https://sefin.test/SefinNacional/nfse"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.dpsXmlGZipB64").value(GzipB64.encode("<DPS/>")))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"tipoAmbiente":2,"versaoAplicativo":"1.0","dataHoraProcessamento":"2026-08-28T12:00:00-03:00",
                         "idDps":"DPS1","chaveAcesso":"33045572026082612345","nfseXmlGZipB64":"%s",
                         "alertas":[{"codigo":"A0001","descricao":"aviso","complemento":"x"}]}
                        """.formatted(GzipB64.encode(nfseXml))));

        SefinEmissionResult result = client.emit("<DPS/>");
        assertTrue(result.accepted());
        assertEquals("33045572026082612345", result.chaveAcesso());
        assertEquals(nfseXml, result.nfseXml());
        assertEquals("A0001", result.messages().get(0).code());
    }

    /**
     * The LIVE restrita API answers erros in PascalCase ({@code Codigo}/{@code Descricao}),
     * contradicting its own Swagger (lowercase). Observed 2026-08-28 on a real E0322
     * rejection. Both casings must parse.
     */
    @Test
    void emitParsesA400RejectionIntoMessagesEvenInPascalCase() {
        server.expect(requestTo("https://sefin.test/SefinNacional/nfse")).andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"tipoAmbiente":2,"versaoAplicativo":"SefinNacional_1.6.0","dataHoraProcessamento":"2026-08-28T12:00:00-03:00",
                         "erros":[{"Codigo":"E0322","Descricao":"É obrigatório informar na DPS um item da NBS"}]}
                        """));

        SefinEmissionResult result = client.emit("<DPS/>");
        assertFalse(result.accepted());
        assertEquals("E0322", result.messages().get(0).code());
        assertEquals("É obrigatório informar na DPS um item da NBS", result.messages().get(0).description());
    }

    /** The old parametros path is gone (moved to ADN); any HTTP answer from /ParametrosMunicipais proves the mTLS gate. */
    @Test
    void pingTreatsAnyHttpResponseAsReachable() {
        server.expect(requestTo("https://sefin.test/SefinNacional/ParametrosMunicipais"))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.NOT_IMPLEMENTED));
        assertTrue(client.ping());
    }

    @Test
    void getNfseXmlDecodesTheEnvelope() {
        String nfseXml = "<NFSe versao=\"1.01\"><infNFSe/></NFSe>";
        server.expect(requestTo("https://sefin.test/SefinNacional/nfse/CH1")).andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"tipoAmbiente":2,"chaveAcesso":"CH1","nfseXmlGZipB64":"%s"}
                        """.formatted(GzipB64.encode(nfseXml)), MediaType.APPLICATION_JSON));
        assertEquals(nfseXml, client.getNfseXml("CH1"));
    }

    /**
     * The event consultation's LIVE shape (restrita, 2026-08-28) is nothing like its
     * Swagger: it answers an {@code eventos[]} list whose {@code arquivoXml} is
     * DOUBLE-encoded — base64 of the gzip+base64 text, not the gzip+base64 itself.
     */
    @Test
    void getEventXmlDecodesTheDoubleEncodedArquivoXmlOfTheLiveShape() {
        String eventoXml = "<evento versao=\"1.01\"><infEvento/></evento>";
        String arquivoXml = java.util.Base64.getEncoder().encodeToString(
                GzipB64.encode(eventoXml).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.expect(requestTo("https://sefin.test/SefinNacional/nfse/CH1/eventos/101101/1"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"dataHoraProcessamento":"2026-08-28T18:01:11-03:00","tipoAmbiente":2,
                         "versaoAplicativo":"SefinNacional_1.6.0",
                         "eventos":[{"chaveAcesso":"CH1","tipoEvento":101101,"numeroPedidoRegistroEvento":1,
                                     "dataHoraRecebimento":"2026-08-28T18:00:44.887","arquivoXml":"%s"}]}
                        """.formatted(arquivoXml), MediaType.APPLICATION_JSON));

        assertEquals(eventoXml, client.getEventXml("CH1", "101101", 1));
    }

    /** The registration answer uses the documented single-encoded field instead. */
    @Test
    void registerEventDecodesTheSingleEncodedEventoField() {
        String eventoXml = "<evento versao=\"1.01\"><infEvento/></evento>";
        server.expect(requestTo("https://sefin.test/SefinNacional/nfse/CH1/eventos"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.pedidoRegistroEventoXmlGZipB64").value(GzipB64.encode("<pedRegEvento/>")))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"tipoAmbiente":2,"versaoAplicativo":"SefinNacional_1.6.0","eventoXmlGZipB64":"%s"}
                        """.formatted(GzipB64.encode(eventoXml))));

        SefinEventResult result = client.registerEvent("CH1", "<pedRegEvento/>");
        assertTrue(result.accepted());
        assertEquals(eventoXml, result.eventoXml());
    }

    @Test
    void getNfseFetchesByAccessKey() {
        server.expect(requestTo("https://sefin.test/SefinNacional/nfse/123")).andExpect(method(GET))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        assertEquals("{\"ok\":true}", client.getNfse("123"));
    }
}
