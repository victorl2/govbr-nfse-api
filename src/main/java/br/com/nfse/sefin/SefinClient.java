package br.com.nfse.sefin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client for the SEFIN Nacional (Emissor Público) API. Paths are CONFIRMED from
 * the Manual de Contribuintes – Emissor Público (see docs/03 §3.1).
 *
 * <p>Every envelope here is confirmed against the live restrita API, not only
 * against the vendored Swagger — and they disagree in three places: emission
 * errors come back PascalCase, the eventos endpoints answer a single {@code erro}
 * object instead of an {@code erros} array, and the event consultation answers an
 * {@code eventos[]} list whose {@code arquivoXml} is double-encoded.
 */
public class SefinClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient sefin;

    public SefinClient(RestClient sefin) {
        this.sefin = sefin;
    }

    /**
     * mTLS reachability probe. The parametros-municipais API moved to the ADN
     * (parametrizacao) — SEFIN's {@code GET /ParametrosMunicipais} now answers
     * 501 — so ANY HTTP status here proves TLS + client-cert + routing work.
     */
    public boolean ping() {
        return sefin.get()
                .uri("/ParametrosMunicipais")
                .exchange((request, response) -> response.getStatusCode().value() > 0);
    }

    /** Like {@link #getNfse}, but unwraps the envelope and returns the decoded NFS-e XML. */
    public String getNfseXml(String chaveAcesso) {
        try {
            JsonNode body = MAPPER.readTree(getNfse(chaveAcesso));
            JsonNode payload = body.path("nfseXmlGZipB64");
            if (payload.isMissingNode() || payload.isNull()) {
                throw new IllegalStateException("SEFIN answer for " + chaveAcesso + " carries no nfseXmlGZipB64");
            }
            return GzipB64.decode(payload.asText());
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse NFS-e envelope: " + e.getMessage(), e);
        }
    }

    /** GET /nfse/{chaveAcesso} — fetch an issued NFS-e by its 50-digit access key. */
    public String getNfse(String chaveAcesso) {
        return sefin.get()
                .uri("/nfse/{c}", chaveAcesso)
                .exchange((request, response) -> {
                    // "Esta chave não existe AQUI" é uma resposta, não uma falha:
                    // acontece sempre que se pede a um ambiente uma chave do
                    // outro. Deixar virar 500 esconde a causa atrás de um erro
                    // interno e de um JSON cru da SEFIN.
                    if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
                        throw new UnknownAccessKey(chaveAcesso);
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("SEFIN answered "
                                + response.getStatusCode() + " for GET /nfse/" + chaveAcesso);
                    }
                    return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                });
    }

    /** The access key is not in the environment this client is pointed at. */
    public static class UnknownAccessKey extends RuntimeException {
        public UnknownAccessKey(String chaveAcesso) {
            super("a SEFIN não conhece a chave " + chaveAcesso
                    + " neste ambiente — confira se ela é do ambiente para onde este serviço aponta");
        }
    }

    /** HEAD /dps/{id} — true if an NFS-e was already generated for this DPS id (idempotency probe). */
    public boolean dpsExists(String dpsId) {
        return sefin.head()
                .uri("/dps/{id}", dpsId)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .is2xxSuccessful();
    }

    /**
     * GET /dps/{id} — the access key of the NFS-e generated for this DPS id, or
     * empty when SEFIN answers 404 (no note was generated). This is the
     * idempotency probe: the DPS id is deterministic (município + inscrição +
     * série + número), so after an ambiguous failure it answers "did my note
     * actually get created?" without risking a duplicate emission.
     *
     * <p>Transport failures propagate — being unable to ask is not the same
     * answer as "no note exists".
     */
    public Optional<String> findChaveByDpsId(String dpsId) {
        return sefin.get()
                .uri("/dps/{id}", dpsId)
                .exchange((request, response) -> {
                    if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
                        return Optional.empty();
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException(
                                "SEFIN answered " + response.getStatusCode() + " for GET /dps/" + dpsId);
                    }
                    JsonNode body = MAPPER.readTree(response.getBody());
                    JsonNode chave = body.path("chaveAcesso");
                    return chave.isMissingNode() || chave.isNull()
                            ? Optional.empty() : Optional.of(chave.asText());
                });
    }

    /**
     * POST /nfse — synchronous emission. Envelope CONFIRMED from the restrita
     * Swagger (reference/sefin-nacional-openapi-v1-restrita-20260828.json):
     * request {@code {"dpsXmlGZipB64": gzip+base64(signed DPS XML)}}; 201 returns
     * {@code chaveAcesso} + {@code nfseXmlGZipB64} + alertas; 400/403/500 return
     * {@code erros[]}.
     */
    public SefinEmissionResult emit(String signedDpsXml) {
        return sefin.post()
                .uri("/nfse")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("dpsXmlGZipB64", GzipB64.encode(signedDpsXml)))
                .exchange((request, response) -> parseEmissionResponse(response));
    }

    /**
     * POST /nfse/{chaveAcesso}/eventos — registers a Pedido de Registro de Evento
     * synchronously. Request {@code {"pedidoRegistroEventoXmlGZipB64": ...}};
     * 201 returns {@code eventoXmlGZipB64} with the generated Evento document.
     */
    public SefinEventResult registerEvent(String chaveAcesso, String signedPedidoXml) {
        return sefin.post()
                .uri("/nfse/{c}/eventos", chaveAcesso)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("pedidoRegistroEventoXmlGZipB64", GzipB64.encode(signedPedidoXml)))
                .exchange((request, response) -> parseEventResponse(response));
    }

    /**
     * GET /nfse/{chaveAcesso}/eventos/{tipoEvento}/{numSeqEvento} — one registered
     * event, decoded. {@code tipoEvento} is the 6-digit code (see
     * {@code EventType}); {@code numSeqEvento} is 1 for events that occur once.
     *
     * <p>Only this fully-qualified path exists: the manual's {@code …/eventos} and
     * {@code …/eventos/{tipo}} variants answer 405 and 404 on the live API
     * (probed in restrita, 2026-08-28).
     */
    public String getEventXml(String chaveAcesso, String tipoEvento, int numSeqEvento) {
        SefinEventResult result = sefin.get()
                .uri("/nfse/{c}/eventos/{t}/{n}", chaveAcesso, tipoEvento, numSeqEvento)
                .exchange((request, response) -> parseEventResponse(response));
        if (!result.accepted() || result.eventoXml() == null) {
            throw new IllegalStateException("SEFIN returned no event for " + chaveAcesso + "/"
                    + tipoEvento + "/" + numSeqEvento + ": " + result.messages());
        }
        return result.eventoXml();
    }

    /**
     * The event consultation answers a shape its own Swagger does not describe
     * (observed live in restrita, 2026-08-28): an {@code eventos[]} list whose
     * {@code arquivoXml} is DOUBLE-encoded — plain base64 wrapping the gzip+base64
     * text, so it needs one base64 decode before {@link GzipB64#decode}.
     */
    private static String eventoFromList(JsonNode body) {
        JsonNode eventos = body.path("eventos");
        if (!eventos.isArray() || eventos.isEmpty()) {
            return null;
        }
        JsonNode arquivo = eventos.get(0).path("arquivoXml");
        if (arquivo.isMissingNode() || arquivo.isNull()) {
            return null;
        }
        String gzipB64 = new String(Base64.getDecoder().decode(arquivo.asText()), StandardCharsets.UTF_8);
        return GzipB64.decode(gzipB64);
    }

    private SefinEventResult parseEventResponse(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) throws IOException {
        JsonNode body = MAPPER.readTree(response.getBody());
        if (response.getStatusCode().is2xxSuccessful()) {
            // registration answers eventoXmlGZipB64; consultation answers eventos[].arquivoXml
            String eventoXml = body.hasNonNull("eventoXmlGZipB64")
                    ? GzipB64.decode(body.get("eventoXmlGZipB64").asText())
                    : eventoFromList(body);
            return new SefinEventResult(true, eventoXml, messages(body.path("alertas")));
        }
        List<SefinMessage> erros = new ArrayList<>(messages(body.path("erros")));
        // the eventos endpoints answer a single "erro" object, unlike emission's array
        JsonNode erro = body.hasNonNull("erro") ? body.get("erro") : body.path("Erro");
        if (erro.isObject()) {
            erros.add(new SefinMessage(
                    anyCase(erro, "codigo"), anyCase(erro, "descricao"), anyCase(erro, "complemento")));
        }
        if (erros.isEmpty()) {
            erros = List.of(new SefinMessage("HTTP_" + response.getStatusCode().value(),
                    "SEFIN answered " + response.getStatusCode() + " without a parseable erro", null));
        }
        return new SefinEventResult(false, null, List.copyOf(erros));
    }

    private SefinEmissionResult parseEmissionResponse(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) throws IOException {
        JsonNode body = MAPPER.readTree(response.getBody());
        if (response.getStatusCode() == HttpStatus.CREATED) {
            return new SefinEmissionResult(true,
                    body.path("chaveAcesso").asText(null),
                    body.hasNonNull("nfseXmlGZipB64") ? GzipB64.decode(body.get("nfseXmlGZipB64").asText()) : null,
                    messages(body.path("alertas")));
        }
        List<SefinMessage> erros = messages(body.path("erros"));
        if (erros.isEmpty()) {
            erros = List.of(new SefinMessage("HTTP_" + response.getStatusCode().value(),
                    "SEFIN answered " + response.getStatusCode() + " without a parseable erros list", null));
        }
        return new SefinEmissionResult(false, null, null, erros);
    }

    /** The live API answers PascalCase (Codigo/Descricao) despite its Swagger saying lowercase — accept both. */
    private static List<SefinMessage> messages(JsonNode array) {
        List<SefinMessage> out = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode m : array) {
                out.add(new SefinMessage(
                        anyCase(m, "codigo"), anyCase(m, "descricao"), anyCase(m, "complemento")));
            }
        }
        return List.copyOf(out);
    }

    private static String anyCase(JsonNode node, String lower) {
        String pascal = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        JsonNode value = node.hasNonNull(lower) ? node.get(lower) : node.path(pascal);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
