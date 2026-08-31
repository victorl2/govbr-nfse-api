# 8. Java Design

> **Superseded in part (2026-08-28).** This began as a pre-implementation
> proposal; the service is now built and exercised live. Two things changed from
> the plan below:
>
> 1. **There is no framework.** Spring Boot, Tomcat, hibernate-validator and
>    actuator were removed. The HTTP surface is ~250 lines over the JDK's
>    `com.sun.net.httpserver` (`br.com.nfse.http`), routes are registered in
>    `api/ApiRoutes`, and the object graph is wired by hand in
>    `NfseApplication.main` — every collaborator already took its dependencies
>    through a constructor, so the container was only buying class-loading.
>    Measured effect: **9,735 → 4,142 classes at startup, 194 → 114 MiB under
>    load, a 93.5 → 76 MB image**. `spring-web`'s `RestClient` is the one piece
>    kept, because SefinClient's live behaviour was proven through it.
> 2. **Configuration is code, not YAML.** `config/Settings` pairs each profile's
>    SEFIN host with its `tpAmb` so the two cannot drift; only the certificate
>    and optional overrides come from the environment.
>
> The goals, layering and fiscal design below still hold.

## 8.1 Goals

- Turn a **simple internal request** (who, what service, how much) into a
  **legally valid NFS-e**, with the SEFIN/signing complexity fully encapsulated.
- Environment is **pure configuration** (restrita ↔ produção).
- Every fiscal artifact (signed DPS, NFS-e, events) is **persisted immutably**.
- The transport/envelope layer is **swappable** behind an interface (we have
  unconfirmed envelope details).

## 8.2 Suggested stack

| Concern | Choice | Why |
|---------|--------|-----|
| Framework | **None** — Java 21 on the JDK HTTP server | Records, virtual threads; a container bought only class-loading for eleven endpoints. |
| HTTP client | **`RestClient`** over Apache HttpClient 5 | Clean mTLS config; synchronous fits `POST /nfse`. |
| XML binding | **JAXB** (generated from the XSDs) | Type-safe DPS/NFS-e from the official schemas. |
| XML signature | **JDK `javax.xml.crypto.dsig`** (or Apache Santuario) | Standard XMLDSig; no exotic deps. |
| XSD validation | `javax.xml.validation.Schema` | Pre-send validation against `DPS_v1.01.xsd`. |
| Persistence | Postgres + Spring Data JPA | Store invoice records + blobs. |
| Migrations | Flyway | Versioned schema. |
| Secrets | Env / Vault / cloud secrets | Keep `.pfx` + password out of the repo. |

> JAXB classes can be generated from the vendored schemas with `xjc`
> (`schemas/1.01/DPS_v1.01.xsd`, `NFSe_v1.01.xsd`, `tiposEventos_v1.01.xsd`).
> Wire this into the build (e.g. `jaxb2-maven-plugin`).

## 8.3 Module / package layout (proposed)

```
br.<company>.nfse
├─ config        SSL/mTLS context, base-URL + tpAmb properties, cert loading
├─ certificate   PKCS12 loader, expiry checks, key access
├─ dps
│  ├─ model      domain request (our simple input) + JAXB DPS types
│  ├─ builder    domain → JAXB DPS tree
│  └─ validator  XSD validation
├─ signing       XMLDSig signer + local self-verifier  (algorithm = config)
├─ transport     envelope (gzip/base64 + JSON), RestClient calls            ← swappable
├─ sefin         SefinClient: emit / getNfse / getDps(head) / events / params
├─ adn           AdnClient: DFe(NSU) walk, events; reconciliation job
├─ danfse        PDF retrieval
├─ events        cancel / substitute / manifestação request builders
├─ store         JPA entities + repos (invoice, dps_xml, nfse_xml, event, nsu)
└─ api           our REST controllers (internal-facing)
```

## 8.4 Core internal API (first cut)

```
POST /nfse/validate       build + full offline dry-run of a JSON sale (IMPLEMENTED)
POST /nfse/send           issue an NFS-e from the same JSON body (IMPLEMENTED —
                          SEFIN transport stubbed, returns NOT_SUBMITTED; §7.8)
GET  /nfse/{chave}        fetch stored NFS-e + status
POST /nfse/{chave}/cancel        register a cancelamento event
POST /nfse/{chave}/replace       cancel-by-substitution (new DPS)
GET  /nfse/{chave}/danfse        the PDF
```

`POST /nfse/send` pipeline:

```
validate input
  → build DPS (JAXB)        [dps.builder]
  → XSD-validate            [dps.validator]
  → sign infDPS + self-verify   [signing]
  → envelope (gzip+b64+JSON)    [transport]   ← exact framing TBD in restrita
  → POST /nfse (mTLS)           [sefin]
  → on accept: persist {chave, dps.xml, nfse.xml, cStat}   [store]
  → on reject: map cStat+motive → error                    [store: attempt log]
```

## 8.5 Persistence model (minimum)

| Entity | Key fields |
|--------|-----------|
| `Invoice` | `chaveAcesso` (PK), `dpsId`, `serie`, `nDPS`, `cStat`, `dhProc`, `status` |
| `InvoiceXml` | `chaveAcesso`, `dpsXml` (blob), `nfseXml` (blob) |
| `InvoiceEvent` | `chaveAcesso`, `tipoEvento`, `numSeq`, `eventXml`, `dhProc` |
| `Numbering` | `serie` → last `nDPS` (atomic increment, unique constraint) |
| `AdnCursor` | last consumed `NSU` |

Enforce a **unique constraint on `dpsId`** — the deterministic id is our
idempotency guard.

## 8.6 Cross-cutting

- **Config profiles:** `restrita` / `producao` carry base URLs, `tpAmb`, cert
  alias. Selecting a profile selects the environment — nothing else changes.
- **Retries:** wrap `sefin` calls with retry **only** on transport/5xx;
  business rejections are terminal.
- **Observability:** Micrometer metrics (emission success rate, `cStat`
  histogram, `POST /nfse` latency, cert days-to-expiry, ADN NSU lag).
- **Testing:** unit-test the signer against a known-good signed sample;
  XSD-validate generated DPS in tests; integration tests run against **produção
  restrita** in CI (with a restrita certificate secret).

## 8.6.1 Certificate strategy: dummy vs. real (dev / restrita / prod)

The certificate has **two uses** ([06](06-signing-and-security.md#61-two-uses-of-one-certificate)),
and only the *remote-validated* ones require a real ICP-Brasil cert:

| Layer | Validated by | Self-signed dummy OK? |
|-------|--------------|:----------------------:|
| DPS build + XSD validation | nobody (local) | ✅ (no cert involved) |
| **XMLDSig signing mechanics** | us, locally | ✅ — issuer is irrelevant to canonicalization/digest/signature |
| **mTLS handshake** to `*.nfse.gov.br` | SEFIN/ADN (trusts only ICP-Brasil) | ❌ — handshake refused |
| **XML signature** accepted by SEFIN | SEFIN (validates signer chain) | ❌ — rejected |

Therefore the cert is a **swappable config input** with three modes:

| Profile | Certificate | Targets | Use |
|---------|-------------|---------|-----|
| `local` | **self-signed dummy** (`scripts/generate-dummy-cert.sh`) | mock SEFIN (WireMock) / no network | build, sign, self-verify, client wiring |
| `restrita` | **real e-CNPJ A1** | `*.producaorestrita.nfse.gov.br`, `tpAmb=2` | real calls; resolves the `[TO CONFIRM]` items. Safe — restrita notes have no legal value |
| `producao` | **real e-CNPJ A1** | `*.nfse.gov.br`, `tpAmb=1` | live emission |

A dummy cert is for *convenience* (keep the real cert off laptops/CI), **not**
safety — using the real cert in restrita is the intended dev setup and cannot
issue a real invoice (`tpAmb=2`). The signing module must be testable end-to-end
with a dummy cert so most development needs no real cert and no network.

## 8.7 Build order (maps to [07](07-sandbox-to-go-live.md))

1. `config` + `certificate` + a trivial **mTLS GET** to prove the cert (Phase B).
2. `dps.model/builder/validator` + JAXB generation (Phase C prep).
3. `signing` + local self-verify (Phase C prep).
4. `transport` + `sefin.emit` → first restrita NFS-e (Phase C).
5. `store` + internal `POST /invoices`.
6. `events` (cancel/substitute) + `danfse` (Phase D).
7. `adn` reconciliation job (Phase D).
8. Production profile + cutover (Phase E).

> Do **not** start step 4 before the restrita Swagger has resolved the envelope
> and signature-algorithm unknowns — building against guessed framing is the most
> likely source of wasted effort.
