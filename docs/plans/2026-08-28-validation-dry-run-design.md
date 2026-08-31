# Validation dry-run — design (2026-08-28)

Goal: before publishing an NFS-e, prove offline — as far as offline can prove —
that a DPS will be accepted by SEFIN. Implemented as `POST /internal/dry-run`.

## Pipeline

`DpsDryRunService` runs four stages over the submitted DPS XML and collects every
finding into one `DryRunReport { valid, findings[] }`. `valid` means no
ERROR-severity finding. Nothing is ever sent to gov.br.

| Stage | Component | Catches |
|-------|-----------|---------|
| `XSD` | `DpsSchemaValidator.validateCollecting` | Well-formedness + every violation of `DPS_v1.01.xsd`, with line numbers (not fail-fast). |
| `LINT` | `DpsLinter` | Predictable server-side rejections + caller-supplied emitter expectations (below), passed per request as `LintExpectations` (query params `expectedMunicipality` / `expectedOpSimpNac` on the endpoint). |
| `SIGN` | `EnvelopedXmlSigner` | Certificate/signing problems (missing `infDPS@Id`, cert not loadable…). |
| `VERIFY` | `SignatureVerifier` + re-XSD | Cryptographic self-check of the produced signature (the way SEFIN verifies: embedded KeyInfo cert) and proof the inserted `ds:Signature` kept the doc schema-valid. |

All stages run even when an earlier one failed (each catches a different class of
mistake); only signing is skipped when there is nothing to sign.

## Lint rules

| Code | Severity | Rule |
|------|----------|------|
| `ENV001` | ERROR | `tpAmb` must match the configured environment (`nfse.tp-amb`). |
| `RTC001` | ERROR | `IBSCBS` group required when `dhEmi` ≥ **2026-08-03** (IBS/CBS mandatory in production since then — RTC page, NT-004 + `tpRetPisCofins` of NT-007). |
| `SN001` | ERROR | `opSimpNac` must match the caller's `expectedOpSimpNac` (XSD enum: 1 = Não Optante, 2 = MEI, 3 = ME/EPP). Omit to disable. |
| `SN002` | WARN | `totTrib` without `pTotTribSN` — only when the expected situation is an optante (2/3). |
| `LOC001` | WARN | `cLocEmi` ≠ the caller's `expectedMunicipality`. Omit to disable. |
| `ID001` | WARN | `infDPS@Id` ≠ `"DPS"+cLocEmi(7)+tpInsc(1)+inscrição(14)+serie(5)+nDPS(15)`. |

## Official schema quirk (important)

`TSSerieDPS` is declared as pattern `^0{0,4}\d{1,5}$`. In XML Schema regex `^`/`$`
are **literal characters**, so spec-compliant validators (JDK Xerces, libxml2)
reject every real serie value; SEFIN's validator treats them as anchors. The
schema loader strips the anchors **at load time** — the vendored XSD files remain
byte-identical to the official `nfse-esquemas_xsd-v1-01-20260209.zip`. Covered by
`DpsSchemaValidatorCollectingTest.realWorldSerieValuesSurviveTheOfficialPatternQuirk`.
Without the shim, the pre-existing `validate()` path rejected every real DPS.

## What offline cannot prove

A clean dry-run is necessary, not sufficient. The E-rule catalog, municipal
parameters and the RTC calculator run server-side only. The authoritative final
stage is a real emission in **produção restrita** (no legal value); wire it into
this same report (a `RESTRITA` stage) once the `POST /nfse` envelope is resolved
(docs/07 §7.8). The emission path should reuse `DpsDryRunService` as its
pre-send gate.

## JSON emission layer (added same day)

`POST /nfse/validate` and `POST /nfse/send` accept a plain JSON sale
(`EmitNfseRequest`); `DpsBuilder` renders the DPS XML (tpAmb always from
config, Id composed per the key rule), then this same dry-run pipeline runs
with expectations derived from the request's own emitter block. `send` stops at
`NOT_SUBMITTED` (signed XML returned, `chaveAcesso` null) until the SEFIN
envelope is resolved; invalid requests come back `REJECTED_LOCALLY`.

## Fixture

`src/test/resources/dps/dps-valid.xml` is a minimal DPS that passes all four
stages (Simples/Rio, IBS/CBS filled, schema-valid) — kept valid on purpose; tests
derive invalid variants by string surgery.
