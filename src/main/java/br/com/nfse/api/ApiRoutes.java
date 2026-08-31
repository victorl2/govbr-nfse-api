package br.com.nfse.api;

import br.com.nfse.adn.AdnClient;
import br.com.nfse.certificate.CertificateLoader;
import br.com.nfse.config.NfseProperties;
import br.com.nfse.danfse.DanfseGenerator;
import br.com.nfse.dryrun.DpsDryRunService;
import br.com.nfse.dryrun.LintExpectations;
import br.com.nfse.emission.EmitNfseRequest;
import br.com.nfse.emission.NfseEmissionService;
import br.com.nfse.emission.RequestValidation;
import br.com.nfse.event.CancelEventRequest;
import br.com.nfse.event.NfseEventService;
import br.com.nfse.health.HealthCheck;
import br.com.nfse.health.HealthReport;
import br.com.nfse.http.ConcurrencyGate;
import br.com.nfse.http.HttpApi;
import br.com.nfse.http.Response;
import br.com.nfse.sefin.SefinClient;
import br.com.nfse.store.EmissionStore;
import br.com.nfse.store.NumberingStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every HTTP route the service exposes, in one place.
 *
 * <p>Three groups: the fiscal API under {@code /nfse}, internal tooling under
 * {@code /internal} (diagnostics and the DPS dry-run — not part of the fiscal
 * contract), and {@code /health} for the container probe.
 */
public final class ApiRoutes {

    private final NfseEmissionService emissionService;
    private final NfseEventService eventService;
    private final SefinClient sefinClient;
    private final DanfseGenerator danfseGenerator;
    private final AdnClient adnClient;
    private final DpsDryRunService dryRunService;
    private final CertificateLoader certificateLoader;
    private final HealthCheck healthCheck;
    private final ConcurrencyGate renderGate;
    private final NumberingStore numbering;
    private final EmissionStore emissions;
    private final NfseProperties props;
    private final ObjectMapper json;

    public ApiRoutes(NfseEmissionService emissionService, NfseEventService eventService,
                     SefinClient sefinClient, AdnClient adnClient,
                     DanfseGenerator danfseGenerator,
                     DpsDryRunService dryRunService, CertificateLoader certificateLoader,
                     HealthCheck healthCheck, ConcurrencyGate renderGate,
                     NumberingStore numbering, EmissionStore emissions,
                     NfseProperties props, ObjectMapper json) {
        this.emissionService = emissionService;
        this.eventService = eventService;
        this.sefinClient = sefinClient;
        this.adnClient = adnClient;
        this.danfseGenerator = danfseGenerator;
        this.dryRunService = dryRunService;
        this.certificateLoader = certificateLoader;
        this.healthCheck = healthCheck;
        this.renderGate = renderGate;
        this.numbering = numbering;
        this.emissions = emissions;
        this.props = props;
        this.json = json;
    }

    public void register(HttpApi api) {
        // ---- emission
        api.route("POST", "/nfse/validate", req ->
                ok(emissionService.validate(emitRequest(req.bodyAs(EmitNfseRequest.class)))));
        api.route("POST", "/nfse/send", req ->
                ok(emissionService.send(emitRequest(req.bodyAs(EmitNfseRequest.class)))));

        // ---- DANFSe: from SEFIN by access key, or from an XML the caller holds.
        // Rendering is gated: it is CPU-bound and allocation-heavy, so unbounded
        // concurrency turns a spike into memory pressure rather than throughput.
        api.route("GET", "/nfse/{chaveAcesso}/danfse", req -> {
            String nfseXml = sefinClient.getNfseXml(req.path("chaveAcesso"));
            return Response.pdf(renderGate.call(() -> danfseGenerator.generate(nfseXml)));
        });
        api.route("POST", "/nfse/danfse", req -> {
            String nfseXml = req.body();
            return Response.pdf(renderGate.call(() -> danfseGenerator.generate(nfseXml)));
        });

        // ---- events. DELETE reads naturally; POST is accepted on the same path
        // because a cancellation carries a mandatory body and proxies strip
        // bodies from DELETE.
        api.route(List.of("DELETE", "POST"), "/nfse/{chaveAcesso}/cancel", req ->
                ok(eventService.cancel(req.path("chaveAcesso"), cancelRequest(req.bodyAs(CancelEventRequest.class)))));
        api.route("POST", "/nfse/{chaveAcesso}/cancel/validate", req ->
                ok(eventService.validateCancellation(
                        req.path("chaveAcesso"), cancelRequest(req.bodyAs(CancelEventRequest.class)))));
        api.route("GET", "/nfse/{chaveAcesso}/eventos/{tipoEvento}/{numSeqEvento}", req ->
                Response.xml(sefinClient.getEventXml(req.path("chaveAcesso"), req.path("tipoEvento"),
                        Integer.parseInt(req.path("numSeqEvento")))));

        // ---- the local record. A note is ours to keep for five years, so it is
        // readable here without going back to SEFIN.
        api.route("GET", "/nfse/{chaveAcesso}", req ->
                ok(emissions.byChave(req.path("chaveAcesso")).orElseThrow(() ->
                        new HttpApi.NotFound("no emission recorded for access key "
                                + req.path("chaveAcesso")))));
        api.route("GET", "/nfse/{chaveAcesso}/xml", req -> {
            var record = emissions.byChave(req.path("chaveAcesso")).orElseThrow(() ->
                    new HttpApi.NotFound("no emission recorded for access key " + req.path("chaveAcesso")));
            return Response.xml(emissions.nfseXml(record.dpsId()).orElseThrow(() ->
                    new HttpApi.NotFound("the note's XML was not stored for " + record.dpsId())));
        });
        api.route("GET", "/internal/emissions", req ->
                ok(emissions.recent(limit(req.query("limit"), 50))));

        // ---- o registro NACIONAL, que é outra pergunta: /internal/emissions diz
        // o que este serviço emitiu; a distribuição do ADN diz o que existe para
        // este CNPJ, inclusive notas emitidas pelo portal ou por outro sistema, e
        // notas em que outra pessoa nos indicou. É por aqui que se reencontra uma
        // nota cuja resposta se perdeu.
        api.route("GET", "/nfse/distribuicao", req -> {
            long nsu = nsu(req.query("nsu"));
            AdnClient.Distribuicao lote = adnClient.distribuicao(nsu);
            boolean comXml = Boolean.parseBoolean(req.query("comXml"));
            return ok(comXml ? lote : semXml(lote));
        });

        // ---- numbering. Readable always; seedable because séries 1–18 were
        // issued before this counter existed and must not be handed out again.
        api.route("GET", "/internal/numbering", req -> ok(numberingState()));
        api.route("PUT", "/internal/numbering/{serie}", req -> {
            SeedRequest seed = req.bodyAs(SeedRequest.class);
            if (seed == null || seed.lastConsumed() == null) {
                throw new HttpApi.BadRequest("lastConsumed is required");
            }
            try {
                numbering.seed(req.path("serie"), seed.lastConsumed());
            } catch (IllegalArgumentException e) {
                // Refusing to lower a counter is the caller asking for something
                // unsafe, not the service failing — 400, not 500.
                throw new HttpApi.BadRequest(e.getMessage());
            }
            return ok(numberingState());
        });

        // ---- internal tooling
        api.route("POST", "/internal/dry-run", req ->
                ok(dryRunService.dryRun(req.body(),
                        new LintExpectations(req.query("expectedMunicipality"), req.query("expectedOpSimpNac")))));
        api.route("GET", "/internal/certificate", req -> ok(certificateLoader.info()));
        api.route("GET", "/internal/connectivity", req -> ok(connectivity()));

        // 503 when the certificate is unusable — an orchestrator must be able to
        // tell "running" from "able to issue a note".
        api.route("GET", "/health", req -> {
            HealthReport report = healthCheck.report();
            return Response.json(report.httpStatus(), json.writeValueAsBytes(report));
        });
    }

    private static long nsu(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0) {
                throw new HttpApi.BadRequest("nsu não pode ser negativo");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new HttpApi.BadRequest("nsu inválido: " + raw);
        }
    }

    /**
     * Sem o XML por padrão: cada documento traz alguns KB, e a pergunta usual é
     * "quais notas existem", não "me devolva todas elas". {@code ?comXml=true}
     * traz o documento inteiro.
     */
    private static AdnClient.Distribuicao semXml(AdnClient.Distribuicao lote) {
        List<AdnClient.Documento> resumo = lote.documentos().stream()
                .map(d -> new AdnClient.Documento(
                        d.nsu(), d.chaveAcesso(), d.tipoDocumento(), d.dataHoraGeracao(), null))
                .toList();
        return new AdnClient.Distribuicao(
                lote.status(), lote.ambiente(), resumo, lote.mensagens(), lote.ultimoNsu());
    }

    /** Non-sensitive certificate metadata plus a live mTLS probe. */
    private Map<String, Object> connectivity() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("environment", props.environment());
        result.put("tpAmb", props.tpAmb());
        result.put("sefinBaseUrl", props.sefinBaseUrl());
        try {
            result.put("sefinReachable", sefinClient.ping());
        } catch (Exception e) {
            result.put("sefinReachable", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private static EmitNfseRequest emitRequest(EmitNfseRequest request) {
        reject(RequestValidation.problems(request));
        return request;
    }

    private static CancelEventRequest cancelRequest(CancelEventRequest request) {
        reject(RequestValidation.problems(request));
        return request;
    }

    private static void reject(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new HttpApi.BadRequest(String.join("; ", problems));
        }
    }

    private Response ok(Object body) throws Exception {
        return Response.json(200, json.writeValueAsBytes(body));
    }

    /** Last número allocated per série — what the next emission will build on. */
    private Map<String, Long> numberingState() {
        Map<String, Long> state = new LinkedHashMap<>();
        numbering.series().stream().sorted()
                .forEach(serie -> state.put(serie, numbering.lastAllocated(serie)));
        return state;
    }

    private static int limit(String raw, int fallback) {
        try {
            return raw == null ? fallback : Math.max(1, Math.min(500, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            throw new HttpApi.BadRequest("limit must be a number, was '" + raw + "'");
        }
    }

    /** Body of {@code PUT /internal/numbering/{serie}}. */
    public record SeedRequest(Long lastConsumed) {
    }
}
