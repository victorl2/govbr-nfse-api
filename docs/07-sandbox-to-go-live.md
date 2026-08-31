# 7. From Sandbox to Go-Live

> **Go-live happened on 2026-08-31.** The first legally valid NFS-e was issued by
> this service in `tpAmb=1`, authorised by SEFIN, DANFSe generated and the note
> confirmed in the emitter portal. The checklist below is kept as the record of
> how we got there; the Phase E items are ticked to what was actually verified.
>
> Two things learned in the process that are not obvious from the manuals:
> **séries from 50000 up are reserved for the Emissor Web** (own software is
> refused with `E0010`, so API emission needs a série ≤ 49999), and the **ADN
> distribution API answers in restrita but drops the connection in produção**
> with the same certificate SEFIN accepts, which is still unexplained.

The path from first sandbox call to legally issuing real invoices.

## 7.1 The two environments, restated

| Stage | Environment | Base hosts | DPS `tpAmb` | Notes |
|-------|-------------|------------|:-----------:|-------|
| Build & certify | **Produção Restrita** | `*.producaorestrita.nfse.gov.br` | `2` | Test data; documents have **no fiscal/legal value**. |
| Live | **Produção** | `sefin.nfse.gov.br`, `adn.nfse.gov.br` | `1` | Real invoices with legal effect. |

Switching environments should be **pure configuration** (base URL + `tpAmb` +
which certificate). No code changes. Design for that from the start.

## 7.2 Phase A — Foundations (no API calls yet)

- [ ] Confirm município adhesion (gate 0) — [02](02-prerequisites.md).
- [ ] Obtain **e-CNPJ A1** certificate; store securely.
- [ ] Vendor schemas (done: [`/schemas`](../schemas)) and the manuals
      (done: [`/reference`](../reference)).
- [ ] Map our service catalog → `cTribNac` codes (Anexo B).
- [ ] Decide `regTrib` values (Simples vs. regime especial) for our company.

## 7.3 Phase B — Wire up restrita

- [ ] Establish **mTLS** to `sefin.producaorestrita.nfse.gov.br` (a successful
      handshake to any GET endpoint proves the certificate works).
- [ ] Open the **cert-gated Swagger** and resolve every **[TO CONFIRM]** in these
      docs: exact JSON envelope for `POST /nfse`, gzip+base64 framing, signature
      algorithm (SHA-1/256), error/`cStat` catalog, credenciamento requirement,
      truststore roots.
- [ ] `GET /parametros_municipais/{codMun}/convenio` returns data for our city.
- [ ] Confirm whether credenciamento in restrita is required, and do it.

## 7.4 Phase C — First emission in restrita

- [ ] Build a minimal valid DPS for one real service we sell.
- [ ] Validate against `DPS_v1.01.xsd` locally.
- [ ] Sign (`infDPS`) and **self-verify** the signature locally.
- [ ] `POST /nfse` → receive an NFS-e. Persist `{chave, dps.xml, nfse.xml}`.
- [ ] `GET /nfse/{chave}` round-trips; `HEAD /dps/{id}` reports it exists.
- [ ] Fetch the **DANFSE** PDF for that key.

## 7.5 Phase D — Exercise the full lifecycle in restrita

- [ ] **Cancelamento** via `POST /nfse/{chave}/eventos` (TE101101 family).
- [ ] **Cancelamento por substituição** via `POST /nfse` with the `subst` block.
- [ ] **Reject paths:** force RN failures, capture `cStat` + motive, map them to
      our error model.
- [ ] **ADN distribution:** walk `GET /DFe/{NSU}` and reconcile our issued docs;
      read events via `GET /NFSe/{chave}/Eventos`.
- [ ] Cover our real tax cases: ISSQN withholding, Simples Nacional percentages,
      municipal benefits, and at least a smoke test of the `IBSCBS` block.

## 7.6 Phase E — Production cutover

Going live is mostly a **configuration flip**, but it is gated by one thing
outside our control (municipal production activation) and differs from restrita
in a few concrete ways.

### The production gate — município convênio *active in produção*

**[CONFIRMED, refined]** Restrita being green does **not** mean production is open
for our city. The município must have its **convênio activated in production** and
its parametrizações loaded (it does this in the *Painel Administrativo
Municipal*, per its own municipal legislation). Until then, `POST /nfse` in
production rejects for our `cLocEmi`. This gate is **not in our code** — it
depends on the city.

- [x] **Município = Rio de Janeiro (IBGE `3304557`) — gate already satisfied.**
      Rio mandatorily migrated to the national system on **2026-01-01**; emission
      is exclusively via the Emissor Nacional and Nota Carioca is discontinued for
      emission. Convênio is active in produção. *(Still: `GET
      /parametros_municipais/3304557/convenio` against `sefin.nfse.gov.br` to
      load live params before first emission.)*

### What genuinely differs from restrita

- [x] **Real ICP-Brasil certificate:** same type (e-CNPJ A1), but a genuine,
      valid, non-expired cert with the correct CNPJ raiz. Restrita may tolerate
      test certs; production will not.
- [x] **`tpAmb = 1`** in the DPS **and** base URLs → `*.nfse.gov.br`. The two
      must agree.
- [x] **Fresh numbering:** production `(série, nDPS)` is a clean sequence — do
      **not** carry over restrita numbers. Restrita documents have **no legal
      value** and are periodically wiped; there is **no data migration**.
- [ ] **DANFSE / QR-code / verification links** point to production endpoints.

### What is *not* required

- **No separate per-developer API homologação/credenciamento** for the national
  emitter (unlike legacy municipal systems like São Paulo). Access is
  **certificate-based** — a valid e-CNPJ is the credential. **[CONFIRMED]**: notes
  were issued in restrita and in produção with nothing but the e-CNPJ.

### Obligation timing (about *whether/when*, not *how*)

- [ ] For **Simples Nacional / MEI**, emission through the **Emissor Nacional is
      becoming mandatory** on a published timeline — confirm the deadline that
      applies to our company, since it may force the cutover date.

### Cutover steps

- [x] Repeat the mTLS handshake against **produção** hosts with the production
      certificate. *(`GET /ParametrosMunicipais` → 501 and `GET /dps/{id}` → 400/404,
      identical to restrita: the certificate is accepted and routing works.)*
- [x] Flip config: base URLs → `*.nfse.gov.br`, `tpAmb` → `1`. *(One
      `NFSE_PROFILE=producao`; the profile pairs host and `tpAmb` so they cannot drift.)*
- [x] Issue one **controlled real NFS-e** and verify the DANFSE. *(2026-08-31,
      `AUTHORIZED`, DANFSe rendered, note visible in the portal. It was a genuine
      invoice, so it was not cancelled.)*
- [ ] Turn on monitoring/alerting (below) before routing real volume.

## 7.7 Operational readiness for go-live

- **Numbering integrity:** `(série, nDPS)` must never repeat for different facts;
  persist the last number atomically — **still outstanding**, nothing in the
  service records which numbers were consumed. Recovery after an ambiguous
  failure IS implemented: `POST /nfse/send` looks the DPS id up with
  `GET /dps/{id}` and returns the already-created note instead of duplicating it.
- **Idempotent retries:** retry only on transport/5xx errors, never on business
  rejections. The deterministic DPS id makes retries safe.
- **Certificate expiry alarm:** A1 expires yearly → alert ≥30 days out.
- **Immutable storage** of every signed DPS + NFS-e + event (legal record).
- **Monitoring:** emission success rate, rejection reasons by `cStat`, latency of
  `POST /nfse`, ADN NSU lag, certificate days-to-expiry.
- **Contingency:** define behaviour when SEFIN is unavailable (queue & retry; the
  layout has contingency fields `cMotivoEmisTI`/`chNFSeRej`). **[TO CONFIRM]** the
  exact contingency rules in Anexo I.
- **Reconciliation job:** periodic ADN NSU walk to detect events/notes filed
  against us.

## 7.8 Outstanding items to resolve in restrita (the [TO CONFIRM] list)

1. ~~Exact `POST /nfse` request/response JSON envelope + gzip/base64 framing.~~
   **RESOLVED 2026-08-28**: request `{"dpsXmlGZipB64": gzip+base64(signed DPS)}`;
   201 → `{chaveAcesso, nfseXmlGZipB64, alertas[]}`; 400/403/500 →
   `{erros[]:{Codigo,Descricao}}` — note the LIVE API answers erros in
   **PascalCase**, contradicting its own Swagger (vendored:
   `reference/sefin-nacional-openapi-v1-restrita-20260828.json`).
2. ~~Signature algorithm.~~ **RESOLVED**: **RSA-SHA256** (the JDK verifier
   refuses SHA-1 outright; restrita accepted our SHA-256 signature — rejections
   were business rules, never signature).
3. Whether credenciamento is required (restrita and produção), and how.
4. ~~Truststore.~~ **RESOLVED**: the servers present a SERPRO chain anchored at
   **GlobalSign Root R46** (absent from JDK ≤21.0.2 cacerts) — vendored at
   `src/main/resources/truststore/extra-roots.pem`. Also: the API requires
   **HTTP/1.1** (h2 streams are reset).
5. Rate limits / SLA / contingency rules.
6. DANFSE: `GET /DANFSe` exists on SEFIN (see vendored spec); exact parameters
   still to confirm. The parametros-municipais API **moved to the ADN**:
   `adn.*/parametrizacao/{codMun}/convenio` etc.
7. ~~Event semantics per `TEnnnnnn` code.~~ **RESOLVED 2026-08-28** from the
   `xs:documentation` in `tiposEventos_v1.01.xsd` — full catalog in
   [05 §5.2](05-events.md). Cancelamento (`e101101`) and cancelamento por
   substituição (a `subst` DPS) are implemented; what remains is exercising
   them live in restrita.

**RESOLVED — first NFS-e emitted via API (2026-08-28):** E0312 was NOT an
environment gap: Rio administers the national code only WITH its municipal
complement — `cServ/cTribMun` (e.g. `010401` + `003` = elaboração de programa
sob encomenda; the "9-digit" service code = cTribNac+cTribMun). Confirmed by
walking the restrita Emissor Web, which also proved: an **all-inclusive Simples
optante does NOT send the IBSCBS group** (NT-004's mandate is the regime-normal
path), and the full **exportação** shape (foreign `toma` with `cNaoNIF`+`endExt`,
`comExt` with `mecAFComexP/T=01`, `movTempBens=1`, `mdic=0`, `tribISSQN=3` +
`cPaisResult`). Reference DPS accepted by SEFIN:
`src/test/resources/dps/dps-export-accepted.xml` (web, chave …1270635662);
our API then emitted chave `…5373588434` end to end (`POST /nfse/send` →
AUTHORIZED).
