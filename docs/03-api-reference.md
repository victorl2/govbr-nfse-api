# 3. API Reference

Endpoints we will integrate with. Sources: *Manual de Contribuintes – Emissor
Público Nacional* (SEFIN) and *Guia das APIs do ADN* — both mirrored in
[`/reference`](../reference). Paths are **[CONFIRMED]** from those manuals;
request/response **envelope details** are **[STANDARD]/[TO CONFIRM]** until we
read the cert-gated Swagger.

Base URLs: see [01](01-overview-and-architecture.md#14-the-two-environments).
All calls require **mTLS with our ICP-Brasil certificate**.

## 3.1 SEFIN Nacional — Emissor Público

### API NFS-e

| Method | Path | Purpose |
|--------|------|---------|
| **POST** | `/nfse` | **[CONFIRMED]** Synchronous emission. Receives a DPS, runs business rules, returns the generated **NFS-e XML** or a rejection. If the DPS carries the access key of an existing NFS-e, it performs **cancel-by-substitution** + issues the replacement. |
| **GET** | `/nfse/{chaveAcesso}` | **[CONFIRMED]** Fetch an NFS-e by its 50-digit access key. |

`POST /nfse` is the heart of the integration. Request body conveys the **signed
DPS XML**; response conveys the **signed NFS-e XML**.

- **[CONFIRMED 2026-08-28, live against restrita]** Request:
  `{"dpsXmlGZipB64": "<gzip+base64 of the signed DPS XML>"}`. Success (201):
  `{tipoAmbiente, versaoAplicativo, dataHoraProcessamento, idDps, chaveAcesso,
  nfseXmlGZipB64, alertas[]}`. Rejection (400/403/500): `{..., erros[]}` where
  each item is `{Codigo, Descricao, Complemento}` — **PascalCase on the live
  API**, lowercase in its own Swagger; parse both. Full spec vendored at
  `reference/sefin-nacional-openapi-v1-restrita-20260828.json`.
- Transport: **HTTP/1.1 only** (the front-end resets h2 streams) and the server
  chain is SERPRO/GlobalSign Root R46 (vendored in
  `src/main/resources/truststore/extra-roots.pem`).
- The **parametros municipais API moved to the ADN**:
  `adn.*/parametrizacao/{codMun}/convenio`, `/{codMun}/{codigoServico}/{competencia}/aliquota`
  etc.; SEFIN's `GET /ParametrosMunicipais` answers 501 (useful as an mTLS probe).
- Historical note (superseded): the framing below was confirmed from the
  restrita Swagger. Build the transport layer behind an interface so this is a
  one-line change.

### API DPS (recover access key from a DPS identifier)

| Method | Path | Purpose |
|--------|------|---------|
| **GET** | `/dps/{id}` | **[CONFIRMED]** Returns the NFS-e access key for a DPS id — **only** if the connecting certificate is an actor (prestador/tomador/intermediário) on that NFS-e. |
| **HEAD** | `/dps/{id}` | **[CONFIRMED]** Returns only *whether* an NFS-e was generated for that DPS id (any valid certificate). Use for **idempotency / retry-safety**. |

`{id}` = the 45-char DPS identifier — see
[04](04-dps-and-nfse-layout.md#43-identifiers--access-keys). After a
timeout/ambiguous failure the service calls `GET /dps/{id}` automatically to
find out whether the note was actually created, rather than blindly retrying —
see **Idempotency** in §3.4.

### API Eventos

| Method | Path | Purpose |
|--------|------|---------|
| **POST** | `/nfse/{chaveAcesso}/eventos` | **[CONFIRMED live]** Generic event registration (cancelamento, manifestação, etc.). Body = signed *Pedido de Registro de Evento* XML. |
| **GET** | `/nfse/{chaveAcesso}/eventos/{tipoEvento}/{numSeqEvento}` | **[CONFIRMED live]** One specific event occurrence. |
| ~~GET~~ | ~~`/nfse/{chaveAcesso}/eventos`~~ | **405 on the live API** — the manual lists it, it does not exist. |
| ~~GET~~ | ~~`/nfse/{chaveAcesso}/eventos/{tipoEvento}`~~ | **404 on the live API.** |

See [05-events.md](05-events.md) for event types and semantics.

**[CONFIRMED 2026-08-28, from the restrita Swagger]** Request:
`{"pedidoRegistroEventoXmlGZipB64": "<gzip+base64 of the signed pedido XML>"}`.
Success (201): `{tipoAmbiente, versaoAplicativo, dataHoraProcessamento,
eventoXmlGZipB64}` — the generated *Evento* document. Rejection: `ResponseErro`
with a **single `erro` object**, not the `erros[]` array the emission endpoint
returns; `SefinClient` parses both shapes.

The consultation path takes the **6-digit numeric** event type:
`GET /nfse/{chave}/eventos/{tipoEvento}/{numSeqEvento}` (101101 = cancelamento,
105102 = cancelamento por substituição; `numSeqEvento` is 1 for events that can
only occur once).

The pedido's `infPedReg@Id` is **`"PRE" + chave(50) + tipoEvento(6)`**
(TSIdPedRegEvt). Note it does *not* include the 3-digit `nPedRegEvento` that the
generated Evento's own id carries (TSIdEvento, `"EVT" + 59` digits) — SEFIN
assigns that sequence itself. Implemented in `EventBuilder` /
`NfseEventService`, exposed as **`DELETE /nfse/{chave}/cancel`** (POST accepted
on the same path, since a cancellation carries a mandatory body), a
`POST /nfse/{chave}/cancel/validate` dry run, and
`GET /nfse/{chave}/eventos/{tipo}/{seq}`. The consultation's live response shape
differs from its Swagger — see [05 §5.4](05-events.md).

### API Parâmetros Municipais

| Method | Path | Purpose |
|--------|------|---------|
| **GET** | `/parametros_municipais/{codMun}/convenio` | **[CONFIRMED]** Convênio parameters of a município. |
| **GET** | `/parametros_municipais/{codMun}/{codigoServico}` | **[CONFIRMED]** Alíquotas, regimes especiais, deduções/reduções by service subitem. |
| **GET** | `/parametros_municipais/{codMun}/{CPF/CNPJ}` | **[CONFIRMED]** Retentions a contributor must collect for that município. |
| **GET** | `/parametros_municipais/{codMun}/{CPF/CNPJ}` | **[CONFIRMED]** Municipal benefits a contributor is entitled to. |

`{codMun}` = 7-digit IBGE code. Cache these; they change rarely.

## 3.2 ADN — Contribuintes (Distribuição)

For pulling documents where we are an actor and reconciling events.

| Method | Path | Purpose |
|--------|------|---------|
| **GET** | `/DFe/{NSU}` | **[CONFIRMED]** Returns the DF-e (service fiscal document) for a given **NSU** (sequential number). Caller passes an NSU; the national system returns the associated DF-e. CNPJ-raiz validated against the certificate; a query parameter allows specifying a CNPJ different from the cert's, within the same raiz. |
| **GET** | `/NFSe/{ChaveAcesso}/Eventos` | **[CONFIRMED]** All *Evento*-type DF-e linked to an access key. |

**NSU (Número Sequencial Único):** the ADN assigns an incrementing NSU to every
document shared with us. Distribution is a **pull, walk-the-NSU** model: persist
the last NSU we consumed and request forward to retrieve new documents
(our own issued notes, events, notes where we are tomador, etc.). This is how we
reconcile and detect documents issued against us.

> **RESOLVED (2026-08-28):** there is NO DANFSe API anymore — NT-008 (v1.02,
> 14/07/2026) suspended the generation API on 2026-08-03 precisely because
> emitter software must render the PDF itself from the NFS-e XML. Implemented
> in `DanfseGenerator` (`GET /nfse/{chave}/danfse` and `POST /nfse/danfse`);
> the QR/consulta URL is `…/ConsultaPublica?tpc=1&chave={chave}` (no slash
> before the query string — decoded from a real portal QR; NT-008 prints it
> with a slash but the portal encodes without), and restrita notes carry the
> mandatory "NFS-e SEM VALIDADE JURÍDICA" banner (which also moves both header
> title lines up — measured, not guessed). The layout replicates the official
> DANFSe v2.0 template byte-for-byte in geometry (coordinates, fonts and QR read
> out of two reference PDFs: one produção, one restrita), embedding Arial Bold +
> Microsoft Sans Serif when the host has them and the vendored Liberation Sans
> otherwise. `destinatário`/`intermediário` render as one-line "NÃO
> IDENTIFICADO" bands when absent and as full person blocks when present — the
> latter push everything below down by exactly 49.30pt while the signature
> footer stays anchored to the page bottom. A name too long for its cell wraps
> to a second line (7.92pt below) and pushes the rest down the same way; the
> destinatário block is the only one without an "Indicador Municipal" column.
> Verified against three official PDFs (produção; restrita with intermediário;
> restrita with destinatário + intermediário): **0 text/position deviations** on
> all three, on macOS and in Docker.

### IBS/CBS reference data

`IBSCBS.cIndOp` (indicador de operação) is a **closed catalog keyed by NBS code
and competência**, not published as a table. The Emissor Web fetches it from
`…/emissornacional/api/emissaodps/RecuperarListaDeIndOp?DataCompetencia=YYYY-MM-DD&NBS=<9 digits>`
(26 codes for NBS 115022000, e.g. `100301` = "Demais serviços, em operações
onerosas — local do domicílio principal do adquirente"). An invalid code is
rejected with **E0901**. Two more rules learned live: the taker's address is
mandatory for several indicadores (**E0234**), and its município must match the
one registered for that CNPJ (**E0921**).

The DANFSe prints municipality names that the NFS-e XML does not carry (it has
only the IBGE code), so the IBGE table is vendored at
`src/main/resources/danfse/municipios-ibge.csv`.

### Optional value groups (confirmed live 2026-08-28)

| Group | Where | Notes |
|-------|-------|-------|
| `vDescCondIncond` | `valores`, after `vServPrest` | `vDescIncond` + `vDescCond`. **SEFIN subtracts BOTH from `vLiq`** — proven live: vServ 10000, discounts 500 + 250, `vLiq` came back 9250. |
| `vDedRed` | `valores`, after the discounts | A **CHOICE**, not a sequence: a flat `pDR`, OR a flat `vDR`, OR an itemised `documentos` list — exactly one. Modelled by `DeducaoReducao`, whose constructor refuses any other combination. |
| `tribFed` | `trib`, between `tribMun` and `totTrib` | `piscofins` (CST, base, alíquotas, values, `tpRetPisCofins`) plus `vRetCP` / `vRetIRRF` / `vRetCSLL`. |
| `infoCompl` | last child of `serv` | Order is `docRef`, `xPed`, `gItemPed`, `xInfComp`. `xInfComp` is 2000 chars and is what the DANFSe prints under INFORMAÇÕES COMPLEMENTARES. |

**A Simples all-inclusive optante gets no ISSQN apuração back even on a domestic
ISS-due note**: nota 17 (`tribISSQN=1`, Rio) returned `infNFSe/valores` carrying
only `vLiq`. `vBC`/`pAliqAplic`/`vISSQN`/`vTotalRet` are rendered when a note
does carry them (a regime-normal emitter), and correctly stay "-" otherwise.

## 3.3 DANFSE

| Method | Path | Purpose |
|--------|------|---------|
| GET | `…/danfse/{chaveAcesso}` | **[STANDARD/TO CONFIRM]** Render the printable PDF (DANFSE) for an access key. Confirm exact path/params in the DANFSE Swagger. |

## 3.4 Cross-cutting concerns

- **Auth:** mTLS on every call (client cert = our e-CNPJ). No bearer
  tokens/OAuth; the certificate *is* the identity.
- **Content type:** JSON request/response envelopes
  ([CONFIRMED]); XML documents embedded within.
- **Errors:** rejections come back with a status (`cStat`) + human-readable
  motive describing which **business rule (RN)** failed. The RN catalog lives in
  **Anexo I** (DPS/NFS-e) and **Anexo II** (eventos). Map `cStat` codes to our
  own error model and log the raw motive.
- **Idempotency:** there is no idempotency key. It is achieved through the
  deterministic **DPS id** (município+CNPJ+série+número). **Implemented**: when
  `POST /nfse` fails in transit, the emission service looks the id up with
  `GET /dps/{id}` before reporting failure — a timeout is ambiguous, and SEFIN may
  have committed the note and lost the answer. If a note exists, it *is* that
  request's result and comes back `AUTHORIZED` (finding `NET002`) instead of being
  duplicated by a retry. If the lookup 404s, `SUBMIT_FAILED` + `NET003` says the
  same série/número is safe to reuse; if the lookup itself fails, `NET005` says so
  explicitly rather than implying the note is absent. Never reuse a
  (série, número) for a different economic fact.
- **Time:** every timestamp we write (`dhEmi`, `dCompet`, `dhEvento`) is
  **horário de Brasília** (`America/Sao_Paulo`), taken from an injected `Clock`,
  never from the host zone — a UTC container would otherwise stamp a note emitted
  at 21:00 BRT on the last of the month into the next month's competência. The
  DANFSe likewise re-expresses whatever offset the XML carries in Brasília time.
- **Rate limits / SLA:** **[TO CONFIRM]** not stated in the taxpayer manuals;
  check the Swagger and convênio terms. Design the client with timeouts,
  bounded retries (only on transport errors, never on business rejections), and
  backoff.
