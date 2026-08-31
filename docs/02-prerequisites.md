# 2. Prerequisites — what we need before any call works

These are gating requirements. None of the API work matters until they are
satisfied, roughly in this order.

## 0. The gate: is your município on the national system?

A taxpayer can only emit through **SEFIN Nacional** if its **município has
adhered (convênio) to the Sistema Nacional NFS-e**. If it has not, NFS-e for that
city are still issued by the city's own legacy system (its own webservice/layout)
and this entire integration does **not** apply.

**Action:** confirm adhesion on the gov.br NFS-e portal (list of municípios
conveniados) and via `GET /parametros_municipais/{codigoMunicipio}/convenio`
(see [03-api-reference.md](03-api-reference.md)).

> **✅ RESOLVED for us — município = Rio de Janeiro (IBGE `3304557`).** Rio
> **mandatorily migrated to the Sistema Nacional NFS-e on 2026-01-01**: emission
> is now **exclusively** through the **Emissor Nacional / SEFIN Nacional**, and
> the legacy **Nota Carioca** system was discontinued for emission (it remains
> only for consultation/cancel/substitution of notes for periods up to Dec 2025).
> The production gate (município convênio active in produção) is therefore
> **already satisfied**. NFS-e issued via the national emitter are automatically
> integrated back into Nota Carioca to generate the ISS guias.
> Sources: Secretaria Municipal de Fazenda do Rio / Portal do ISS (Dec 2025).
>
> **Rio-specific follow-up:** ensure our **inscrição municipal (CCM)** is active
> and matches the `IM` we send in the DPS `prest` block. Do **not** attempt to
> reissue pre-2026 Nota Carioca notes through the national API.
>
> **We are Simples Nacional:** ISS is recolhido via the **DAS** (PGDAS-D), so
> there is **no separate municipal ISS guia** for us — the "Rio auto-generates ISS
> guia from the national NFS-e" path applies to regime-normal taxpayers. A
> separate collection only arises if ISS is **retido na fonte** by the tomador.
> See the Simples Nacional taxation notes in
> [04 §4.7](04-dps-and-nfse-layout.md#47-valores-tcinfovalores--amounts--taxation).

Even when emission stays municipal, issued documents are still **shared into the
ADN**, so the ADN distribution API can remain relevant for reconciliation.

## 1. ICP-Brasil digital certificate (e-CNPJ)

Everything is authenticated by an **ICP-Brasil certificate tied to our CNPJ**.
The same certificate is used for **two distinct purposes**:

1. **mTLS** — the TLS client certificate on every HTTPS call (the server rejects
   connections without it; see the 403 note in
   [01](01-overview-and-architecture.md#14-the-two-environments)).
2. **XML digital signature** — signing the DPS (and event) XML documents.

| Type | Form | Use for a server | Verdict |
|------|------|------------------|---------|
| **e-CNPJ A1** | software `.pfx`/`.p12` file + password, valid 1 year | Ideal — loadable into a Java `KeyStore`, no hardware | **Use this** |
| e-CNPJ A3 | hardware token / smartcard, valid up to 3 years | Requires PKCS#11 + physical device; painful to automate | Avoid for a server |

**CNPJ raiz rule [CONFIRMED]:** the ADN distribution API accepts a certificate
whose CNPJ shares the same **raiz** (first 8 digits) as the CNPJ being queried.
So a matriz certificate can act for its filiais. Plan certificate ownership
around the CNPJ raiz, not the exact 14-digit CNPJ.

## 2. Credenciamento in the environment

Confirm whether our CNPJ must be **pre-registered (credenciado)** in Produção
Restrita before `POST /nfse` is accepted, or whether a valid certificate is
sufficient. **[TO CONFIRM]** — to be verified against the *Manual de
Contribuintes* registration section and the restrita Swagger once we hold a
certificate. Treat as a required onboarding step until proven otherwise.

## 3. Municipal parameters available for our city

Emission validations depend on data the município pre-loads into the national
system (alíquotas, regimes especiais, benefícios, retenções). Before issuing we
should fetch and cache:

- `GET /parametros_municipais/{codMun}/convenio` — adhesion + convênio params.
- `GET /parametros_municipais/{codMun}/{codigoServico}` — alíquota, regimes
  especiais, deduções/reduções per service subitem.
- `GET /parametros_municipais/{codMun}/{CNPJ}` — our retentions + municipal
  benefits.

If these return empty/!adhered for our city, stop — see gate 0.

## 4. Company / fiscal data we must have on hand

To populate the DPS (`prest` block) — see
[04-dps-and-nfse-layout.md](04-dps-and-nfse-layout.md):

- **CNPJ** (14 digits) and **Inscrição Municipal** (IM), if our city issues one.
- **Regime tributário**: `opSimpNac` (Simples Nacional opt-in 1/2/3),
  `regApTribSN` if applicable, `regEspTrib` (special regime 0–6,9).
- The **código de tributação nacional** (`cTribNac`, 6 digits) for each service
  we sell — from the national service list (**Anexo B / NBS**), *not* the old LC
  116 municipal codes. Map our service catalog to these once.
- Our address per `TCEndereco` (município IBGE + CEP for national addresses).

## Prerequisite checklist

- [ ] Município confirmed on the Sistema Nacional NFS-e (gate 0).
- [ ] e-CNPJ **A1** certificate obtained (`.pfx` + password), stored securely.
- [ ] Certificate's CNPJ raiz matches the emitting CNPJ(s).
- [ ] Credenciamento in Produção Restrita confirmed/performed. *(TO CONFIRM)*
- [ ] Municipal parameters for our city return data in restrita.
- [ ] Each service in our catalog mapped to a `cTribNac` (Anexo B).
- [ ] Regime tributário fields determined for our company.
