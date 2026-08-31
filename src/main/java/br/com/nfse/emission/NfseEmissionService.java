package br.com.nfse.emission;

import br.com.nfse.config.BrasiliaTime;
import br.com.nfse.dryrun.DpsDryRunService;
import br.com.nfse.dryrun.DryRunOutcome;
import br.com.nfse.dryrun.DryRunReport;
import br.com.nfse.dryrun.LintExpectations;
import br.com.nfse.dryrun.Stage;
import br.com.nfse.dryrun.ValidationFinding;
import br.com.nfse.sefin.SefinClient;
import br.com.nfse.sefin.SefinEmissionResult;
import br.com.nfse.sefin.SefinMessage;
import br.com.nfse.store.EmissionRecord;
import br.com.nfse.store.EmissionStore;
import br.com.nfse.store.NumberingStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@code POST /nfse/*} pipeline: build the DPS from the JSON request, run
 * the full offline dry-run (XSD → lint → sign → self-verify), and — once the
 * SEFIN envelope is confirmed (docs/07 §7.8) — submit. Until then {@code send}
 * stops at {@link EmissionStatus#NOT_SUBMITTED} with the signed document.
 *
 * <p>The lint expectations are derived from the request itself (the emitter
 * declares its municipality and Simples situation), so the checks prove the
 * built document is internally consistent with what was asked.
 */
public class NfseEmissionService {

    private final DpsBuilder builder;
    private final DpsDryRunService dryRunService;
    private final SefinClient sefinClient;
    private final NumberingStore numbering;
    private final EmissionStore emissions;

    public NfseEmissionService(DpsBuilder builder, DpsDryRunService dryRunService,
                               SefinClient sefinClient, NumberingStore numbering,
                               EmissionStore emissions) {
        this.builder = builder;
        this.dryRunService = dryRunService;
        this.sefinClient = sefinClient;
        this.numbering = numbering;
        this.emissions = emissions;
    }

    /**
     * A dry run reserves nothing. When the request omits the número it is shown
     * the one it *would* get, so the preview is realistic without spending it.
     */
    public ValidateNfseResponse validate(EmitNfseRequest request) {
        EmitNfseRequest previewed = request.dps().number() == null || request.dps().number().isBlank()
                ? withNumber(request, String.valueOf(numbering.lastAllocated(request.dps().serie()) + 1))
                : request;
        String dpsXml = builder.build(previewed);
        DryRunReport report = dryRunService.dryRun(dpsXml, expectationsFrom(previewed));
        return new ValidateNfseResponse(report.valid(), report.findings(), dpsXml);
    }

    public SendNfseResponse send(EmitNfseRequest request) {
        boolean allocated = request.dps().number() == null || request.dps().number().isBlank();
        EmitNfseRequest numbered = allocated
                ? withNumber(request, String.valueOf(numbering.next(request.dps().serie())))
                : request;
        try {
            return sendNumbered(numbered, allocated);
        } catch (RuntimeException e) {
            // The number was never committed to a document that left the process.
            releaseIfAllocated(numbered, allocated);
            throw e;
        }
    }

    private SendNfseResponse sendNumbered(EmitNfseRequest request, boolean allocated) {
        String dpsXml = builder.build(request);
        DryRunOutcome outcome = dryRunService.run(dpsXml, expectationsFrom(request));
        DryRunReport report = outcome.report();
        String bestXml = outcome.signedDpsXml() != null ? outcome.signedDpsXml() : dpsXml;
        if (!report.valid()) {
            // Nothing was sent, so the número goes back rather than leaving a gap.
            releaseIfAllocated(request, allocated);
            return new SendNfseResponse(
                    EmissionStatus.REJECTED_LOCALLY, false, report.findings(), bestXml, null, null);
        }

        List<ValidationFinding> findings = new ArrayList<>(report.findings());
        String dpsId = builder.dpsId(request);
        String now = BrasiliaTime.now();

        // Claiming the id is the idempotency guard: the same (CNPJ, série, número)
        // always yields the same dpsId, so a repeat is recognised before it is sent.
        try {
            emissions.begin(dpsId, request.dps().serie(), request.dps().number(), bestXml, now);
        } catch (EmissionStore.DuplicateEmission duplicate) {
            return alreadyEmitted(dpsId, findings, bestXml);
        }

        SefinEmissionResult sefin;
        try {
            sefin = sefinClient.emit(outcome.signedDpsXml());
        } catch (Exception e) {
            return recoverOrFail(request, e, findings, bestXml);
        }

        for (SefinMessage m : sefin.messages()) {
            findings.add(sefin.accepted()
                    ? ValidationFinding.warn(Stage.SEFIN, m.code(), messageText(m))
                    : ValidationFinding.error(Stage.SEFIN, m.code(), messageText(m)));
        }
        if (!sefin.accepted()) {
            emissions.finished(dpsId, EmissionRecord.Status.REJECTED_BY_SEFIN,
                    describe(sefin.messages()), BrasiliaTime.now());
            return new SendNfseResponse(
                    EmissionStatus.REJECTED_BY_SEFIN, true, findings, bestXml, null, null);
        }
        emissions.authorized(dpsId, sefin.chaveAcesso(), sefin.nfseXml(), BrasiliaTime.now());
        return new SendNfseResponse(EmissionStatus.AUTHORIZED, true, findings,
                bestXml, sefin.chaveAcesso(), sefin.nfseXml());
    }

    /**
     * This (série, número) was submitted before. Answering from the store is the
     * whole point of recording it: re-sending would either duplicate the note or
     * be refused by SEFIN, and neither tells the caller what actually exists.
     */
    private SendNfseResponse alreadyEmitted(String dpsId, List<ValidationFinding> findings, String bestXml) {
        EmissionRecord existing = emissions.byDpsId(dpsId).orElseThrow(() ->
                new IllegalStateException("DPS id " + dpsId + " is claimed but has no record"));
        findings.add(ValidationFinding.warn(Stage.SEFIN, "DUP001",
                "this série/número was already submitted (" + existing.status()
                        + ") — returning the stored result instead of emitting again"));
        return switch (existing.status()) {
            case AUTHORIZED -> new SendNfseResponse(EmissionStatus.AUTHORIZED, true, findings,
                    emissions.dpsXml(dpsId).orElse(bestXml), existing.chaveAcesso(),
                    emissions.nfseXml(dpsId).orElse(null));
            case REJECTED_BY_SEFIN -> new SendNfseResponse(EmissionStatus.REJECTED_BY_SEFIN, true,
                    findings, emissions.dpsXml(dpsId).orElse(bestXml), null, null);
            default -> new SendNfseResponse(EmissionStatus.SUBMIT_FAILED, true, findings,
                    emissions.dpsXml(dpsId).orElse(bestXml), existing.chaveAcesso(), null);
        };
    }

    private void releaseIfAllocated(EmitNfseRequest request, boolean allocated) {
        if (allocated) {
            numbering.release(request.dps().serie(), Long.parseLong(request.dps().number()));
        }
    }

    private static EmitNfseRequest withNumber(EmitNfseRequest request, String number) {
        EmitNfseRequest.Dps dps = request.dps();
        return new EmitNfseRequest(request.emitter(),
                new EmitNfseRequest.Dps(dps.serie(), number, dps.dhEmi(), dps.dCompet()),
                request.service(), request.values(), request.ibsCbs(), request.tomador(),
                request.intermediario(), request.comercioExterior(), request.substituicao());
    }

    private static String describe(List<SefinMessage> messages) {
        return messages.stream().map(m -> m.code() + " " + m.description())
                .collect(java.util.stream.Collectors.joining("; "));
    }

    /**
     * A failed submission is ambiguous: SEFIN may have committed the note and lost
     * the answer on the way back. Retrying the same (série, número) would then be a
     * duplicate emission, and picking a new número would leave an orphaned note. So
     * the deterministic DPS id is looked up — if a note exists for it, that note is
     * this request's result and is returned as {@code AUTHORIZED}.
     *
     * <p>If the probe cannot be answered either, we stay {@code SUBMIT_FAILED}:
     * "I could not ask" must never be reported as "no note was created".
     */
    private SendNfseResponse recoverOrFail(EmitNfseRequest request, Exception cause,
                                           List<ValidationFinding> findings, String bestXml) {
        findings.add(ValidationFinding.error(Stage.SEFIN, "NET001",
                "submission to SEFIN failed: " + cause.getMessage()));
        String dpsId = builder.dpsId(request);
        try {
            Optional<String> chave = sefinClient.findChaveByDpsId(dpsId);
            if (chave.isEmpty()) {
                findings.add(ValidationFinding.warn(Stage.SEFIN, "NET003",
                        "no NFS-e exists for DPS id " + dpsId + " — the submission did not reach SEFIN,"
                                + " so this request may be retried with the same série/número"));
                // The probe proved nothing arrived, so the claim must go too —
                // otherwise the retry this finding invites would be refused as a
                // duplicate of a submission that never happened.
                emissions.discard(dpsId);
                return new SendNfseResponse(
                        EmissionStatus.SUBMIT_FAILED, true, findings, bestXml, null, null);
            }
            findings.add(ValidationFinding.warn(Stage.SEFIN, "NET002",
                    "the submission failed in transit but SEFIN had already generated the NFS-e for DPS id "
                            + dpsId + " — recovered by access key instead of emitting a duplicate"));
            String nfseXml = null;
            try {
                nfseXml = sefinClient.getNfseXml(chave.get());
            } catch (Exception e) {
                findings.add(ValidationFinding.warn(Stage.SEFIN, "NET004",
                        "the recovered note's XML could not be fetched: " + e.getMessage()
                                + " — fetch it later with GET /nfse/" + chave.get()));
            }
            emissions.authorized(dpsId, chave.get(), nfseXml, BrasiliaTime.now());
            return new SendNfseResponse(
                    EmissionStatus.AUTHORIZED, true, findings, bestXml, chave.get(), nfseXml);
        } catch (Exception probeFailure) {
            findings.add(ValidationFinding.error(Stage.SEFIN, "NET005",
                    "could not determine whether the note was created: " + probeFailure.getMessage()
                            + " — check GET /dps/" + dpsId + " before retrying, or the retry may duplicate it"));
            // Deliberately left SUBMIT_FAILED in the store, not rolled back: this
            // número may or may not exist at SEFIN, and the record is the only
            // reminder that it must be checked before anyone reuses it.
            emissions.finished(dpsId, EmissionRecord.Status.SUBMIT_FAILED,
                    "unresolved: " + probeFailure.getMessage(), BrasiliaTime.now());
            return new SendNfseResponse(
                    EmissionStatus.SUBMIT_FAILED, true, findings, bestXml, null, null);
        }
    }

    private static String messageText(SefinMessage m) {
        return m.description() + (m.complement() == null ? "" : " (" + m.complement() + ")");
    }

    private static LintExpectations expectationsFrom(EmitNfseRequest request) {
        return new LintExpectations(
                request.emitter().municipality(), request.emitter().opSimpNac());
    }
}
