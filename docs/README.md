# NFS-e Nacional — Integration Documentation

Technical reference for building an in-house API (Java / Spring) that issues
**NFS-e (Nota Fiscal de Serviço eletrônica)** through the **Sistema Nacional
NFS-e** (padrão nacional / Ambiente Nacional), operated by the Receita Federal /
CGNFS-e.

This documentation was assembled from the official technical documentation at
<https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica> (current
production layout, **XSD v1.01 of 2026-02-09**) and the official taxpayer
manuals, which are mirrored under [`/reference`](../reference) and the schemas
under [`/schemas`](../schemas).

> Status: **research / design phase. No application code yet.** These documents
> define *what* we must build and *what is required to go live* before we write
> the Spring service.

## Reading order

| # | Document | What it answers |
|---|----------|-----------------|
| 1 | [01-overview-and-architecture.md](01-overview-and-architecture.md) | What the national system is, the actors, the four APIs, the two environments. |
| 2 | [02-prerequisites.md](02-prerequisites.md) | What *we* must have before any call works: certificate, municipal adhesion, credenciamento. |
| 3 | [03-api-reference.md](03-api-reference.md) | Every endpoint we will call (SEFIN + ADN), auth, message envelope, errors. |
| 4 | [04-dps-and-nfse-layout.md](04-dps-and-nfse-layout.md) | The DPS we build and the NFS-e we get back: full field tree, access keys, IBS/CBS. |
| 5 | [05-events.md](05-events.md) | Cancellation, substitution and other post-issuance events. |
| 6 | [06-signing-and-security.md](06-signing-and-security.md) | XML digital signature + mTLS — the part that causes most rejections. |
| 7 | [07-sandbox-to-go-live.md](07-sandbox-to-go-live.md) | The full path: produção restrita → produção, with a go-live checklist. |
| 8 | [08-java-spring-design.md](08-java-spring-design.md) | Proposed implementation architecture for our service. |

## Our scenario (assumptions to keep in mind)

- We are a **single Brazilian company** issuing NFS-e for **our own services** (we
  are always the **prestador / emitente**).
- Our **município is Rio de Janeiro (IBGE `3304557`)**, which **mandatorily
  migrated to the Sistema Nacional NFS-e on 2026-01-01** — emission is now
  exclusively via **SEFIN Nacional** and the legacy Nota Carioca was discontinued
  for emission. The national integration target is correct and **already live in
  production**. See
  [02-prerequisites.md](02-prerequisites.md#0-the-gate-is-your-município-on-the-national-system).
- Target stack: **Java + Spring Boot**.

## Confidence markers used in these docs

- **[CONFIRMED]** — taken verbatim from an official manual or the official XSD.
- **[STANDARD]** — follows the SPED / ICP-Brasil / NF-e convention the national
  system inherits; extremely likely but worth a one-time verification against the
  cert-gated Swagger or Anexo I once we have a certificate.
- **[TO CONFIRM]** — we could not read it without a certificate; flagged
  explicitly so it does not silently become an assumption.
