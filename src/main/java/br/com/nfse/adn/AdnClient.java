package br.com.nfse.adn;

import br.com.nfse.sefin.SefinMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Client for the ADN (Ambiente de Dados Nacional) distribution API — the
 * national record of every document where this CNPJ appears as emitter, taker or
 * intermediary.
 *
 * <p>This is a different question from the local emission store: the store knows
 * what <em>this service</em> issued, while the ADN knows what <em>exists
 * nationally</em>, including notes issued from the portal or by another system,
 * and notes where someone else named us. Reconciling the two is how you find a
 * note you lost the response to.
 *
 * <p>Paths CONFIRMED live against produção restrita (2026-08-31), not only from
 * the manual: the base path is {@code /contribuintes}, which the manual omits.
 */
public class AdnClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient adn;

    public AdnClient(RestClient adn) {
        this.adn = adn;
    }

    /**
     * {@code GET /contribuintes/DFe/{nsu}} — the documents that came after
     * {@code nsu}. The cursor is EXCLUSIVE and confirmed so live: asking for 0
     * returns from NSU 1, asking for 1 returns from NSU 2. Walk it by passing the
     * last NSU you received until {@link Distribuicao#exhausted()} is true.
     */
    public Distribuicao distribuicao(long nsu) {
        return adn.get()
                .uri("/contribuintes/DFe/{nsu}", nsu)
                .exchange((request, response) -> parse(response));
    }

    private Distribuicao parse(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        JsonNode body = MAPPER.readTree(response.getBody());
        String status = text(body, "StatusProcessamento");

        // "No documents from this NSU on" arrives as 404 + E2220. That is the
        // normal end of the walk, not a failure — treating it as an error would
        // make every complete listing look broken.
        if (response.getStatusCode() == HttpStatus.NOT_FOUND
                || "NENHUM_DOCUMENTO_LOCALIZADO".equals(status)) {
            return new Distribuicao(status == null ? "NENHUM_DOCUMENTO_LOCALIZADO" : status,
                    text(body, "TipoAmbiente"), List.of(), messages(body), nsuMaisAlto(List.of()));
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("o ADN respondeu " + response.getStatusCode()
                    + (messages(body).isEmpty() ? "" : ": " + messages(body)));
        }

        List<Documento> documentos = new ArrayList<>();
        JsonNode lote = body.path("LoteDFe");
        if (lote.isArray()) {
            for (JsonNode item : lote) {
                documentos.add(new Documento(
                        item.path("NSU").asLong(),
                        text(item, "ChaveAcesso"),
                        text(item, "TipoDocumento"),
                        text(item, "DataHoraGeracao"),
                        decode(text(item, "ArquivoXml"))));
            }
        }
        return new Distribuicao(status, text(body, "TipoAmbiente"),
                List.copyOf(documentos), messages(body), nsuMaisAlto(documentos));
    }

    private static long nsuMaisAlto(List<Documento> documentos) {
        return documentos.stream().mapToLong(Documento::nsu).max().orElse(0L);
    }

    /** The ADN frames each document the same way SEFIN does: XML → gzip → Base64. */
    private static String decode(String gzipB64) {
        if (gzipB64 == null || gzipB64.isBlank()) {
            return null;
        }
        try (GZIPInputStream gzip = new GZIPInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(gzipB64)))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("falha ao descompactar o XML vindo do ADN: " + e.getMessage(), e);
        }
    }

    private static List<SefinMessage> messages(JsonNode body) {
        List<SefinMessage> messages = new ArrayList<>();
        for (String field : List.of("Erros", "Alertas")) {
            JsonNode list = body.path(field);
            if (list.isArray()) {
                for (JsonNode item : list) {
                    messages.add(new SefinMessage(
                            text(item, "Codigo"), text(item, "Descricao"), null));
                }
            }
        }
        return List.copyOf(messages);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** One batch of the distribution walk. */
    public record Distribuicao(
            String status,
            String ambiente,
            List<Documento> documentos,
            List<SefinMessage> mensagens,
            long ultimoNsu
    ) {
        /** True when there is nothing after the NSU that was asked for. */
        public boolean exhausted() {
            return documentos.isEmpty();
        }
    }

    /** One document in the national record. {@code xml} is already decoded. */
    public record Documento(
            long nsu,
            String chaveAcesso,
            String tipoDocumento,
            String dataHoraGeracao,
            String xml
    ) {
    }
}
