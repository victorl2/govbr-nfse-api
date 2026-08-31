package br.com.nfse.adn;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Os corpos aqui reproduzem o que o ADN de produção restrita realmente devolveu
 * em 2026-08-31, com o CNPJ trocado por um de teste.
 */
class AdnClientTest {

    private final RestClient.Builder builder =
            RestClient.builder().baseUrl("https://adn.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AdnClient client = new AdnClient(builder.build());

    private static String gzipB64(String xml) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                gzip.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void readsTheBatchAndDecodesEachDocument() {
        String nfse = "<NFSe versao=\"1.01\"><infNFSe/></NFSe>";
        server.expect(requestTo("https://adn.test/contribuintes/DFe/0"))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
                        {"StatusProcessamento":"DOCUMENTOS_LOCALIZADOS",
                         "LoteDFe":[
                           {"NSU":1,"ChaveAcesso":"33045572212345678000195000000000000126081270635662",
                            "TipoDocumento":"NFSE","ArquivoXml":"%s",
                            "DataHoraGeracao":"2026-08-28T14:37:38.9"},
                           {"NSU":2,"ChaveAcesso":"33045572212345678000195000000000000126081270635662",
                            "TipoDocumento":"EVENTO","ArquivoXml":"%s",
                            "DataHoraGeracao":"2026-08-28T18:01:20.053"}],
                         "Alertas":[],"Erros":[],"TipoAmbiente":"HOMOLOGACAO",
                         "VersaoAplicativo":"1.0.0.0","DataHoraProcessamento":"2026-08-31T14:19:13-03:00"}
                        """.formatted(gzipB64(nfse), gzipB64("<evento/>"))));

        AdnClient.Distribuicao lote = client.distribuicao(0);

        assertEquals("DOCUMENTOS_LOCALIZADOS", lote.status());
        assertEquals("HOMOLOGACAO", lote.ambiente());
        assertEquals(2, lote.documentos().size());
        assertEquals(nfse, lote.documentos().get(0).xml());
        assertEquals("EVENTO", lote.documentos().get(1).tipoDocumento());
        assertFalse(lote.exhausted());
    }

    /**
     * O fim da caminhada. O ADN devolve 404 com E2220, e tratar isso como falha
     * faria toda listagem completa parecer quebrada — foi confirmado ao vivo.
     */
    @Test
    void endOfTheWalkIsNotAFailure() {
        server.expect(requestTo("https://adn.test/contribuintes/DFe/12"))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                        {"StatusProcessamento":"NENHUM_DOCUMENTO_LOCALIZADO","LoteDFe":[],"Alertas":[],
                         "Erros":[{"Mensagem":{},"Codigo":"E2220",
                                   "Descricao":"Nenhum documento localizado - não existem documentos fiscais"}],
                         "TipoAmbiente":"HOMOLOGACAO","VersaoAplicativo":"1.0.0.0"}
                        """));

        AdnClient.Distribuicao lote = client.distribuicao(12);

        assertTrue(lote.exhausted());
        assertEquals("NENHUM_DOCUMENTO_LOCALIZADO", lote.status());
        assertEquals("E2220", lote.mensagens().get(0).code());
    }

    /** O NSU mais alto do lote é o cursor da próxima chamada. */
    @Test
    void reportsTheCursorForTheNextCall() {
        server.expect(requestTo("https://adn.test/contribuintes/DFe/5"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
                        {"StatusProcessamento":"DOCUMENTOS_LOCALIZADOS",
                         "LoteDFe":[{"NSU":6,"ChaveAcesso":"c6","TipoDocumento":"NFSE","ArquivoXml":null},
                                    {"NSU":9,"ChaveAcesso":"c9","TipoDocumento":"NFSE","ArquivoXml":null}],
                         "TipoAmbiente":"HOMOLOGACAO"}
                        """));

        AdnClient.Distribuicao lote = client.distribuicao(5);

        assertEquals(9, lote.ultimoNsu());
        assertNull(lote.documentos().get(0).xml(), "documento sem XML não deve explodir");
    }
}
