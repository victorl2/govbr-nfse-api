# nfse

In-house service to issue **NFS-e** (Nota Fiscal de Serviço eletrônica) through
the Brazilian **Sistema Nacional NFS-e** (padrão nacional). Stack: **Java 25**,
no framework.

**Current status: emitting, cancelling and replacing notes against produção
restrita.** Certificate loading, mTLS, XMLDSig signing, XSD validation +
business-rule lint, synchronous emission, cancellation and substitution events,
local DANFSe rendering and durable numbering + emission records are all
implemented and exercised live. Remaining for production go-live: credenciamento,
the real service-catalog mapping, and the profile flip — see
[docs/07](docs/07-sandbox-to-go-live.md).

**No framework.** The service runs on the JDK's own HTTP server and wires its
objects by hand in `NfseApplication.main`, which reads as the whole
architecture. Spring Boot, Tomcat, hibernate-validator and actuator were removed
in favour of ~250 lines under `br.com.nfse.http`; `spring-web`'s `RestClient` is
kept for the SEFIN transport, whose live behaviour was proven through it. The
result: **9,735 → 4,142 classes at startup, 194 → 114 MiB under load, 93.5 → 78
MB image**, with the DANFSe still byte-for-byte identical to the official PDFs.
(The shipped image is 131 MB today: the AOT cache below trades 53 MB of disk for
a 2.6× faster start.)

Measured on the shipped container: **~570 ms** end to end from `docker run` to a
serving `/health` — ~265 ms of that inside the container, the rest Docker
creating it. A DANFSe renders in **~200 ms** cold and **~56 ms** warm. Startup is
short because the image carries a trained Leyden AOT cache; see below.

<sub>Benchmark host: MacBook Pro (M1 Pro, 10 cores — 8P/2E — 32 GB, macOS 26.4)
running Docker Desktop 24.0.7, with 10 CPUs and 7.7 GiB given to the Linux VM;
in-container JVM is Temurin 25.0.4 on aarch64/musl, SerialGC by ergonomics.
A DANFSe render is CPU-bound, so throughput scales with cores — expect lower
figures on a 2-vCPU cloud instance. The memory thresholds are not CPU-dependent
and should carry over.</sub>

> **Run it with `-m 192m`.** It is the smallest limit that never pressures the
> collector, at every load from 1 to 16 concurrent renders. Memory here buys
> throughput, not latency: at 8 concurrent renders 192m sustains **35 req/s**
> against 8.5 at 128m and 4 at 96m. Going higher barely helps — use 256m only if
> more than 8 renders will ever be in flight at once.
>
> Do not shrink it below 128m. A 64m box renders fine, shows flat memory and no
> latency drift, and is then **OOM-killed after ~384 requests** — small boxes
> here fail late and without warning, so soak any limit before trusting it.

### Where startup time goes

The image ships a **Leyden AOT cache** (Java 25: JEP 483 class loading/linking,
JEP 515 method profiles), trained at build time. It is the single largest win
available here, measured same-source and interleaved:

| | without the cache | with it |
|---|---|---|
| container start → serving `/health` | 680 ms | **265 ms** |
| `docker run` → serving `/health` | 1,000 ms | **571 ms** |
| first DANFSe render (cold) | 307 ms | **199 ms** |
| warm DANFSe render (p50) | **51 ms** | 56 ms |
| image | **78 MB** | 131 MB |
| peak RSS, 20 renders at `-m 192m` | 188 MiB | **180 MiB** |

The trade-off is the warm render: replaying recorded profiles costs about **5 ms
(~10%) on steady-state renders**, and training harder does not remove it (60
renders in the training run measured the same as 1). `-XX:-AOTReplayTraining`
buys the warm render back and gives up ~50 ms of startup, if a deployment ever
prefers that. The cache also *lowers* memory, because mapped archive regions
replace heap-allocated metadata and there is less class-parsing garbage.

`AOTMode=auto` is deliberate: a cache that does not match the runtime is logged
and ignored rather than fatal. A slow start beats no start for a fiscal service —
which also means a broken cache is invisible without the CI assertion that it maps.

**How it is trained.** Stage 2c of the Dockerfile runs the real service and drives
it through the paths that matter — 60 DANFSe renders, then 15 validate + send +
cancel cycles — and stops it with SIGTERM so the JVM writes the cache on clean
exit. Nothing real is touched: the certificate is a throwaway keypair generated
during the build, and SEFIN is a **WireMock stub** (`training/`) returning the
gzip+base64 envelopes the live API returns. **The build needs no e-CNPJ and never
calls gov.br.** The training asserts its own first emission comes back
`AUTHORIZED`, because a stub that silently fails still produces a plausible-looking
cache with none of the emission paths in it.

Before the cache, the remaining startup cost was measured phase by phase:

| phase | cost |
|---|---|
| certificate — PKCS12 parse + RSA key | 67 ms |
| TLS — SSLContext, truststore, managers | 114 ms |
| XSD schema compilation (two trees) | 106 ms |
| all remaining wiring | 3 ms |

None of that can be precomputed at build time — the certificate only exists at run
time, and a compiled `javax.xml.validation.Schema` is not serialisable. What the
three do have is independence, so they run concurrently on virtual threads and
startup costs the longest rather than the sum.

Also measured and rejected, recorded so they are not tried again blind:

- **The JDK vendor.** Corretto 25 and Zulu 25 were built and benchmarked against
  Temurin: identical startup, render latency and memory to within noise, and
  byte-identical DANFSe output. Both also need `binutils` at jlink time and a
  `libz.so.1` copied into the final image, which Temurin's self-contained musl
  build does not. No reason to switch.
- **Plain CDS** (`jlink --generate-cds-archive`): 680 → 578 ms for +29 MB. Real,
  but the AOT cache subsumes it. Worth knowing that a jlink'd runtime ships **no
  CDS archive at all** unless asked — the default is slower than a stock JRE.
- **`-XX:TieredStopAtLevel=1`**: 34 ms off startup, but warm renders go 60 → 98 ms.
  A permanent throughput loss to save a one-off cost.
- **Background pre-warming** of PDFBox and the DPS pipeline: 431 ms vs 430 ms. The
  first render is slow because the JVM is still compiling, not because of a
  one-time initialisation that can be pre-paid.
- **CRaC**: the fastest option measured (~150 ms), rejected because a checkpoint
  is an unencrypted memory dump — a planted secret was found in plaintext in
  `pages-1.img` — and it needs a privileged container.

## Where to start

→ **[docs/README.md](docs/README.md)** — the full integration documentation
(architecture, prerequisites, API reference, DPS/NFS-e layout, events, signing,
sandbox-to-go-live, and the Java design).

## Build & run

Requires **JDK 25** and Maven.

```bash
# 1. Generate a self-signed dummy cert for local dev (no real e-CNPJ needed)
./scripts/generate-dummy-cert.sh

# 2. Build + test (the signing pipeline is verified with the dummy cert)
mvn package          # -> target/nfse.jar + target/lib/

# 3a. Run locally (dummy cert, no gov.br network)
NFSE_PROFILE=local NFSE_CERT_PATH=certs/dummy.p12 java -jar target/nfse.jar

# 3b. Run against produção restrita (REAL e-CNPJ A1 — proves mTLS, Phase B)
NFSE_PROFILE=restrita NFSE_CERT_PATH=/secure/ecnpj.p12 NFSE_CERT_PASSWORD=*** \
  java -jar target/nfse.jar
# then: curl localhost:8080/internal/certificate   and   /internal/connectivity
```

**Emission API (JSON)** — describe the sale in JSON; the service builds the DPS
XML, validates (XSD → business-rule lint), signs and self-verifies. Callers
never touch XML:

```bash
curl -s -X POST localhost:8080/nfse/validate -H 'Content-Type: application/json' -d @sale.json
# → {"valid": true|false, "findings": [...], "dpsXml": "<DPS...>"}

curl -s -X POST localhost:8080/nfse/send -H 'Content-Type: application/json' -d @sale.json
# → {"status": "AUTHORIZED"|"REJECTED_BY_SEFIN"|"REJECTED_LOCALLY"|"SUBMIT_FAILED",
#    "valid": ..., "findings": [...], "dpsXml": "<signed DPS>",
#    "chaveAcesso": "...", "nfseXml": "<NFSe...>"}
```

`/nfse/send` submits the signed DPS to SEFIN synchronously and returns
`AUTHORIZED` (with `chaveAcesso` + the NFS-e XML), `REJECTED_BY_SEFIN`,
`REJECTED_LOCALLY` or `SUBMIT_FAILED`. See the request shape in
`EmitNfseRequest` (emitter, dps numbering, service, values, optional ibsCbs,
tomador, intermediario, comercioExterior and substituicao — service export is a
first-class flow). `values` also carries `descontos`
(`incondicionado`/`condicionado` — SEFIN subtracts both from `vLiq`),
`deducaoReducao` and `tributacaoFederal` (PIS/COFINS plus the IRRF/CSLL/CP a
corporate customer withholds); `service.infoCompl` carries `xInfComp` (printed
in INFORMAÇÕES COMPLEMENTARES), `docRef`, `xPed` and its item numbers. `intermediario` takes the same shape as `tomador`; a
destinatário that differs from the adquirente goes in `ibsCbs.dest` with
`indDest=1`; `substituicao` (`chSubstda` + `cMotivo` + `xMotivo`) replaces an
existing note — SEFIN cancels it and issues this one in its place.

A submission that fails **in transit** is ambiguous, so the service looks the
deterministic DPS id up with `GET /dps/{id}` before reporting failure: if SEFIN
had already created the note, it comes back `AUTHORIZED` rather than being
duplicated by a retry (see the `NET002`…`NET005` findings).

**Numbering and the local record** — `dps.number` is **optional**. Omitted, the
service allocates the next número for the série from a durable counter, so two
callers or a restart cannot collide. Supplying one explicitly is still allowed,
and is how you retry a failed submission on the número it already used: the same
(CNPJ, série, número) yields the same DPS id, which is claimed before anything is
sent, so a repeat returns the stored result instead of emitting twice.

Every submission is written down before it leaves, and the authorised note and
its signed DPS are kept — Brazil requires them for five years, and without a
local copy a lost response means walking the ADN feed to find your own note.

```bash
# what the counters stand at, and seeding one whose numbers predate this store
curl -s localhost:8080/internal/numbering                      # → {"1": 18}
curl -s -X PUT localhost:8080/internal/numbering/1 \
     -H 'Content-Type: application/json' -d '{"lastConsumed":18}'
# lowering a counter is refused with 400 — it would re-issue a número SEFIN has

curl -s localhost:8080/nfse/{chaveAcesso}          # the stored record
curl -s localhost:8080/nfse/{chaveAcesso}/xml      # the authorised NFS-e, as filed
curl -s 'localhost:8080/internal/emissions?limit=20'   # recent attempts, newest first
```

> **Mount `/var/lib/nfse`.** It holds the counters and every note issued, and
> neither is reconstructible. A note rejected locally releases its número; one
> rejected *by SEFIN* keeps it, because that número is spent.

**Cancelling and events** — an issued note is voided through a signed *Pedido de
Registro de Evento* (docs/05). Cancellation has a municipal deadline; past it,
replace the note with a `substituicao` DPS instead.

```bash
curl -s -X DELETE localhost:8080/nfse/{chaveAcesso}/cancel \
  -H 'Content-Type: application/json' \
  -d '{"cnpjAutor":"...","cMotivo":"1","xMotivo":"Erro na emissao: ..."}'
# → {"status":"REGISTERED"|"REJECTED_BY_SEFIN"|"REJECTED_LOCALLY"|"SUBMIT_FAILED",
#    "findings":[...], "pedidoXml":"<signed pedido>", "eventoXml":"<evento>"}

curl -s -X POST   localhost:8080/nfse/{chaveAcesso}/cancel/validate ...  # dry run, sends nothing
curl -s           localhost:8080/nfse/{chaveAcesso}/eventos/101101/1     # read an event back
```

The same path also accepts **POST** — a cancellation carries a mandatory body and
bodies on DELETE are legal but routinely stripped by proxies. Note that cancelling
removes nothing: the note stays retrievable, with a cancellation event attached.

`cMotivo`: 1 = Erro na Emissão, 2 = Serviço não Prestado, 9 = Outros. `xMotivo`
needs at least 15 characters — the official XSD says so, and we catch it offline.

**Time** — every fiscal timestamp (`dhEmi`, `dCompet`, `dhEvento`) is **horário
de Brasília**, independent of the host's zone, so a UTC container cannot book a
note into the wrong competência.

**DANFSe (PDF)** — rendered locally per NT-008 (the official generation API was
suspended on 2026-08-03; emitter software must produce its own). The layout is
a faithful replica of the portal's DANFSe v2.0 template — same geometry, fonts
(embedded; Liberation Sans fallback vendored for hosts without Arial) and QR:

```bash
curl -s localhost:8080/nfse/{chaveAcesso}/danfse -o danfse.pdf     # fetches the note from SEFIN
curl -s -X POST localhost:8080/nfse/danfse -H 'Content-Type: application/xml' \
  --data-binary @nfse.xml -o danfse.pdf                            # offline, from the XML you already hold
```

**Low-level dry-run (XML)** — validate an already-built DPS document:

```bash
curl -s -X POST 'localhost:8080/internal/dry-run?expectedMunicipality=3304557&expectedOpSimpNac=3' \
  -H 'Content-Type: application/xml' --data-binary @my-dps.xml
```

The query parameters are the caller's own expectations (IBGE municipality code;
`opSimpNac` 1=Não Optante, 2=MEI, 3=ME/EPP) — omit either to skip that check.
The service itself is company-neutral.

Design and rule catalog: [docs/plans/2026-08-28-validation-dry-run-design.md](docs/plans/2026-08-28-validation-dry-run-design.md).

**Docker** — a three-stage build (Maven → `jlink` → busybox) producing a
**~76 MB** image, down from 371 MB on a stock JRE base. The image is
self-contained: schemas, truststore, DANFSe fonts and the IBGE table all ship
inside the jar. Only the certificate is mounted at run time.

```bash
docker build -t nfse .
docker run -m 192m -p 8080:8080 \
  -v /secure/ecnpj.p12:/secure/ecnpj.p12:ro \
  -v nfse-data:/var/lib/nfse \
  -e NFSE_PROFILE=restrita \
  -e NFSE_CERT_PATH=/secure/ecnpj.p12 -e NFSE_CERT_PASSWORD=... \
  nfse
```

The final stage is **busybox** (1.7 MB) rather than Alpine: `ldd` shows the
jlink runtime links against nothing outside its own directory except musl libc,
so that one file plus a static `su-exec` is all it needs. The trade-off is no
`apk` for ad-hoc debugging — busybox still gives you `sh`, `ps` and `wget`.
JAXB is not a runtime dependency (the DPS/NFS-e documents are built with DOM
directly) and lives in the `jaxb` profile instead, which removes ~1.5 MB and
~200 classes from startup.

The runtime is a custom `jlink` image carrying only the modules this service
uses. Two of them no static analysis can find, and both fail *silently* if
dropped:

- **`jdk.crypto.ec`** — mTLS to SEFIN negotiates ECDHE; without it the
  handshake fails.
- **`jdk.localedata`** (with `--include-locales=en,pt`) — the DANFSe formats
  money as `Locale.of("pt","BR")`. With only the root locale, `R$ 10.000,00`
  would silently render as `R$ 10,000.00` on every note.

The JVM runs as the unprivileged `nfse` user, and **the mounted certificate can
stay root-owned and `chmod 600`** — which is what a properly protected e-CNPJ key
file looks like. The entrypoint starts as root only long enough to copy it to a
container-private path owned by `nfse` (mode 400), then `exec`s the JVM as that
user; your file on the host is never touched. Passing `--user` skips all of it
and runs the JVM directly as whatever uid you named, in which case that uid must
be able to read the certificate itself.

Building needs BuildKit (Docker 23+, the default) for the Maven cache mount.

Profiles: `local` / `restrita` / `producao`, selected with `NFSE_PROFILE`. Each
one fixes its SEFIN host and `tpAmb` together in `config/Settings` so the two
cannot drift; only the certificate (`NFSE_CERT_PATH`, `NFSE_CERT_PASSWORD`) and
optional overrides (`NFSE_PORT`, `NFSE_SEFIN_BASE_URL`, `NFSE_ADN_BASE_URL`,
`NFSE_CONNECTIVITY_CHECK`, the timeouts) come from the environment.

Generate the JAXB DPS/NFS-e model classes from the official XSDs when ready:
`mvn -Pjaxb generate-sources`.

## Repository layout

| Path | Contents |
|------|----------|
| [`docs/`](docs) | Integration documentation (start here). |
| [`src/`](src) | The service. No framework — see `NfseApplication.main`. |
| [`schemas/`](schemas) | Official NFS-e XSDs, vendored. **v1.01 = 2026-02-09.** |
| [`scripts/`](scripts) | Dev helpers (dummy-cert generation). |
| [`reference/`](reference) | Extracted text of the official taxpayer manuals. |

## Operating it

`GET /health` reports the one thing that silently stops every emission — the
certificate. It answers **503 `DOWN`** when the e-CNPJ cannot be loaded or has
expired, and **200 `UP`** otherwise, carrying `daysToExpiry` and a warning under
30 days. It stays UP while the certificate is merely expiring, because killing a
working service a month early helps nobody; alert on the warning instead.

```json
{"status":"UP","certificate":{"notAfter":"2027-01-20T12:10:11Z","daysToExpiry":144},"warnings":[]}
```

DANFSe rendering is bounded: it is CPU-bound and allocation-heavy, so unbounded
concurrency turns a load spike into memory pressure. Renders beyond
`NFSE_MAX_CONCURRENT_RENDERS` (default: one per core) queue for
`NFSE_RENDER_QUEUE_TIMEOUT_SECONDS`, then get **529 Service Overloaded** — a
retryable condition, deliberately distinct from the 503 that means the service
cannot issue notes at all.

| Variable | Default | Purpose |
|---|---|---|
| `NFSE_PROFILE` | `local` | `local` / `restrita` / `producao` — picks host **and** `tpAmb` together. |
| `NFSE_CERT_PATH` / `NFSE_CERT_PASSWORD` | — | The e-CNPJ A1. Never baked into the image. |
| `NFSE_DATA_DIR` | `/var/lib/nfse` | Numbering counters + emission records. **Mount it.** |
| `NFSE_MAX_CONCURRENT_RENDERS` | one per core | DANFSe concurrency limit. |
| `NFSE_CONNECTIVITY_CHECK` | on outside `local` | mTLS probe at startup (~300–540 ms). |

## Sources

- Portal: <https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica>
- APIs (restrita/produção): <https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica/apis-prod-restrita-e-producao>
- Schemas: `nfse-esquemas_xsd-v1-01-20260209.zip` (current production layout).

## ⚠️ Never commit secrets

The ICP-Brasil **e-CNPJ certificate** (`.pfx`/`.p12`) and its password must never
enter the repo. See [`.gitignore`](.gitignore).

## Licence

Copyright 2026 Victor Ferreira Teixeira da Silva.
Licensed under the [Apache License 2.0](LICENSE); see [NOTICE](NOTICE) for the
attribution that must travel with redistributions, and for the vendored
GlobalSign root and official NFS-e schemas, which it does not cover.
