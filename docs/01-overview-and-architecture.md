# 1. Overview & Architecture

## 1.1 What the Sistema Nacional NFS-e is

Historically, NFS-e (service invoices) were issued by each of Brazil's ~5,570
municipalities, each with its own system, layout and webservice. The **Sistema
Nacional NFS-e** (a.k.a. *padrão nacional*, *Ambiente Nacional*) unifies this
under a single national standard, layout (DPS/NFS-e), set of REST APIs and a
single digital-certificate-based access model.

A municipality joins via a **convênio** (adhesion agreement). Once it has joined,
taxpayers in that municipality can issue their NFS-e directly against the
**national** infrastructure instead of the city's legacy system.

## 1.2 Actors

The layout models three actors on a service document:

| Actor | DPS field | Who it is for us |
|-------|-----------|------------------|
| **Prestador** (emitente) | `infDPS/prest` | **us** — we provide the service |
| **Tomador** | `infDPS/toma` | our client |
| **Intermediário** | `infDPS/interm` | optional broker/marketplace |

Access to read a document via the APIs is restricted to actors that appear on it
(fiscal secrecy — *sigilo fiscal*), validated against the **CNPJ raiz** of the
connecting certificate.

## 1.3 The four APIs

The national system exposes four logical APIs. We will primarily use the first
two.

| API | Host group | Purpose | We use it to |
|-----|-----------|---------|--------------|
| **SEFIN Nacional** (Emissor Público Nacional) | `sefin.*` | **Emit** NFS-e from a DPS, query, register events, read municipal parameters | **Issue invoices** (core) |
| **ADN** — Ambiente de Dados Nacional (Contribuintes / Distribuição) | `adn.*` | Distribute/pull documents where we are an actor; query events | Reconcile / pull our issued docs and events |
| **CNC** — Cadastro Nacional de Contribuintes | `adn.*/cnc` | National taxpayer registry data | Mostly municipal-facing; rarely needed by us |
| **DANFSE** | `adn.*/danfse` | Render the printable PDF (DANFSE) of an NFS-e | Produce the human-readable invoice |

> **[CONFIRMED]** SEFIN endpoint groups (from *Manual de Contribuintes – Emissor
> Público*): *Parâmetros Municipais*, *NFS-e*, *DPS*, *Eventos*.
> **[CONFIRMED]** ADN Contribuintes endpoints (from *Guia das APIs do ADN*):
> `GET /DFe/{NSU}`, `GET /NFSe/{ChaveAcesso}/Eventos`.

## 1.4 The two environments

Build and certify everything in **Produção Restrita** (sandbox/homologação)
first; only then switch base URLs to **Produção**.

| | Produção Restrita (sandbox) | Produção |
|--|------------------------------|----------|
| **SEFIN** | `https://sefin.producaorestrita.nfse.gov.br` | `https://sefin.nfse.gov.br` |
| **ADN** | `https://adn.producaorestrita.nfse.gov.br` | `https://adn.nfse.gov.br` |
| Swagger/ReDoc (SEFIN) | `…/API/SefinNacional/docs/index` | `…/SefinNacional/docs/index` |
| Swagger (ADN Contribuintes) | `…/contribuintes/docs/index.html` | `…/contribuintes/docs/index.html` |
| `tpAmb` value in the DPS | **`2`** | **`1`** |

> **[CONFIRMED]** `tpAmb` enum is `{1,2}` in the XSD (`TSTipoAmbiente`). By SPED
> convention `1` = produção, `2` = homologação/restrita. The DPS carries the
> environment, **and** it must match the host you post to.

> **[CONFIRMED]** The live OpenAPI/Swagger spec at
> `…/SefinNacional/swagger/docs/v1` returns **HTTP 403** without a client
> certificate — i.e. even the API documentation is gated behind mTLS. Plan to
> read the full Swagger only after the certificate is provisioned.

## 1.5 The end-to-end emission flow (high level)

```
            we build               we sign           transport (mTLS + JSON)
  ┌────────────────────┐   ┌──────────────────┐   ┌────────────────────────────┐
  │  DPS  (our draft)  │ → │ XMLDSig over     │ → │ POST  /nfse  to SEFIN       │
  │  XML, padrão nac.  │   │ <infDPS Id=...>  │   │ (synchronous)               │
  └────────────────────┘   └──────────────────┘   └─────────────┬──────────────┘
                                                                 │
                              ┌──────────────────────────────────┴───────────────┐
                              │ SEFIN runs business rules (RN) on the DPS          │
                              ├─ reject  → error message (cStat + motive)          │
                              └─ accept  → NFS-e XML (signed by SEFIN) + chave     │
                                                                 │
                ┌────────────────────────────────────────────────┴───────────────┐
                │ We persist {chave, dps.xml, nfse.xml}.                            │
                │ Optional: GET DANFSE (PDF) · pull via ADN /DFe/{NSU} ·            │
                │ register events (cancel) via POST /nfse/{chave}/eventos.          │
                └──────────────────────────────────────────────────────────────────┘
```

The critical property: **`POST /nfse` is synchronous** — one call in, the
authorized NFS-e (or a rejection) out. No polling for the happy path.

See [05-events.md](05-events.md) for the substitution case (a DPS that carries
the access key of an existing NFS-e cancels-and-replaces it).
