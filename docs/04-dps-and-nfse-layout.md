# 4. DPS & NFS-e Layout

Everything here is **[CONFIRMED]** — extracted directly from the official
**XSD v1.01** (`nfse-esquemas_xsd-v1-01-20260209.zip`), vendored under
[`/schemas/1.01`](../schemas/1.01). Namespace for all documents:
`http://www.sped.fazenda.gov.br/nfse`. The signature namespace is
`http://www.w3.org/2000/09/xmldsig#` (`ds:`).

Two documents matter:

- **DPS** (*Declaração de Prestação de Serviços*) — **we build and sign** this.
- **NFS-e** — **SEFIN generates and signs** this; it *embeds the full DPS we
  sent* plus the official identifiers and totals.

## 4.1 Document roots

```
DPS  (root element, attribute versao)
└─ infDPS  (attribute Id = "DPS" + 42 digits — REQUIRED, signature target)
└─ ds:Signature  (our XMLDSig; minOccurs=0 in schema but REQUIRED by business rule)

NFSe (root element, attribute versao)
└─ infNFSe (attribute Id = "NFS" + 50 digits)
   └─ ... official fields (see 4.4) ...
   └─ DPS              ← the entire DPS we submitted, embedded
└─ ds:Signature        ← SEFIN's signature
```

## 4.2 `infDPS` — top level (what we fill)

| Field | Type | Req | Meaning |
|-------|------|-----|---------|
| `tpAmb` | enum `1,2` | ✔ | 1=produção, 2=restrita. **Must match the host.** |
| `dhEmi` | dateTime UTC | ✔ | Emission timestamp (UTC, with offset). |
| `verAplic` | string | ✔ | Our application version tag. |
| `serie` | `0{0,4}\d{1,5}` | ✔ | DPS series. |
| `nDPS` | numeric ≤15 | ✔ | DPS number (our sequential, per série). |
| `dCompet` | date | ✔ | Competence date of the service. |
| `tpEmit` | enum `1,2,3` | ✔ | Emitter type (1=prestador is the normal case). |
| `cMotivoEmisTI` | enum | — | Reason for contingency emission, if applicable. |
| `chNFSeRej` | 50 digits | — | Rejected NFS-e key (contingency flows). |
| `cLocEmi` | IBGE 7 | ✔ | Município IBGE code of the emitter. |
| `subst` | TCSubstituicao | — | Substitution block (replace an existing NFS-e). |
| **`prest`** | TCInfoPrestador | ✔ | **Us** — see 4.5. |
| `toma` | TCInfoPessoa | — | The client (tomador). |
| `interm` | TCInfoPessoa | — | Intermediário, if any. |
| **`serv`** | TCServ | ✔ | The service — see 4.6. |
| **`valores`** | TCInfoValores | ✔ | Amounts & taxation — see 4.7. |
| `IBSCBS` | TCRTCInfoIBSCBS | — | **2026 tax reform** block — see 4.8. |

## 4.3 Identifiers & access keys

**[CONFIRMED]** from the XSD documentation.

**DPS id** — `infDPS@Id`, the literal `DPS` + **42 digits** (45 chars total):

```
"DPS" + cMun(7) + tpInscFederal(1) + InscFederal(14, CPF left-padded with 000)
      + Série(5) + NúmDPS(15)
```
This is **deterministic** from our own data → it is our natural idempotency key.
The `GET/HEAD /dps/{id}` endpoints take exactly this string.

**NFS-e access key (chave de acesso)** — `infNFSe@Id`, the literal `NFS` +
**50 digits** (53 chars total):

```
"NFS" + cMun(7) + AmbGer(1) + tpInscFederal(1) + InscFederal(14)
      + NoNFSe(13) + AnoMêsEmis(4, AAMM) + CódNum(9) + DV(1)
```
The 50-digit numeric form (`TSChaveNFSe`, `[0-9]{50}`) is what every
`/{chaveAcesso}` path parameter expects (strip the `NFS` literal).

## 4.4 `infNFSe` — top level (what SEFIN returns)

| Field | Meaning |
|-------|---------|
| `xLocEmi`, `xLocPrestacao` | Emission / provision location text. |
| `nNFSe` | NFS-e number assigned by SEFIN. |
| `cLocIncid`, `xLocIncid` | Município of ISSQN incidence. |
| `xTribNac`, `xTribMun`, `xNBS` | Resolved descriptions for the codes we sent. |
| `verAplic`, `ambGer`, `tpEmis`, `procEmi` | Generator app/environment/emission type. |
| `cStat` | **Status code** of the result (accepted state). |
| `dhProc` | Processing timestamp. |
| `nDFSe` | DFS-e number. |
| `emit` | Emitter identification as registered. |
| `valores` | Official computed totals. |
| `IBSCBS` | Reform totals (if present). |
| **`DPS`** | The full DPS we submitted, embedded verbatim. |

We persist this whole signed document — it is the legal invoice.

## 4.5 `prest` (TCInfoPrestador) — us

```
prest
├─ [choice] CNPJ(14) | CPF(11) | NIF | cNaoNIF{0,1,2}   ← CNPJ for us
├─ CAEPF(14)?            (rural/individual, n/a for us)
├─ IM?                   inscrição municipal (≤15)
├─ xNome?                razão social (≤300)
├─ end?  (TCEndereco)    endNac{cMun, CEP, ...} | endExt{cPais, ...}
├─ fone?  email?
└─ regTrib  (REQUIRED)
   ├─ opSimpNac     enum 1,2,3   (Simples Nacional situation)
   ├─ regApTribSN?  enum 1,2,3   (apuração regime, if Simples)
   └─ regEspTrib    enum 0,1,2,3,4,5,6,9  (special tax regime)
```

`regTrib` is **required** and drives a lot of the ISSQN math — set it from our
company's actual fiscal regime (see prerequisite 4 in
[02](02-prerequisites.md#4-company--fiscal-data-we-must-have-on-hand)).

## 4.6 `serv` (TCServ) — the service

```
serv
├─ locPrest        [choice] cLocPrestacao(IBGE 7) | cPaisPrestacao(ISO 2)
├─ cServ  (REQUIRED)
│  ├─ cTribNac      6 digits   ← national taxation code (Anexo B / NBS) REQUIRED
│  ├─ cTribMun?     3 digits   ← municipal code, if the city requires
│  ├─ xDescServ     ≤2000      ← service description REQUIRED
│  ├─ cNBS?         9 digits   ← NBS code
│  └─ cIntContrib?  ≤20        ← our internal code (free)
├─ comExt?         foreign-trade block (export of services)
├─ obra?           construction work block (CIB / endereço da obra)
├─ atvEvento?      event/activity block (shows, etc.)
└─ infoCompl?
   ├─ idDocTec? docRef? xPed?
   ├─ gItemPed? (xItemPed ×1..99)
   └─ xInfComp?   ≤2000  ← free additional info
```

The **only hard requirements** for a plain domestic service: `locPrest`,
`cTribNac`, `xDescServ`. The rest are situational.

## 4.7 `valores` (TCInfoValores) — amounts & taxation

```
valores
├─ vServPrest  (REQUIRED)
│  ├─ vReceb?   amount received
│  └─ vServ     service value  ← REQUIRED   (decimal, 2 places)
├─ vDescCondIncond?   vDescIncond / vDescCond   (discounts)
├─ vDedRed?           deductions/reductions (percentage pDR or value vDR,
│                     with a documentos list of up to 1000 backing docs)
└─ trib  (REQUIRED — TCInfoTributacao)
   ├─ tribMun  (REQUIRED)
   │  ├─ tribISSQN     enum 1,2,3,4   (ISSQN operation type) REQUIRED
   │  ├─ cPaisResult?  (result country, exports)
   │  ├─ tpImunidade?  enum 0..5
   │  ├─ exigSusp?     suspended liability (tpSusp + nProcesso 30 digits)
   │  ├─ BM?           benefício municipal (nBM 14 + redução)
   │  ├─ tpRetISSQN    enum 1,2,3   (ISSQN withholding type) REQUIRED
   │  └─ pAliq?        ISSQN aliquot (decimal)
   ├─ tribFed?
   │  ├─ piscofins?  (CST, base, pAliqPis, pAliqCofins, vPis, vCofins, ret)
   │  ├─ vRetCP?  vRetIRRF?  vRetCSLL?   (federal withholdings)
   └─ totTrib  (REQUIRED — total tax transparency, "Lei da Transparência")
      ├─ [choice] vTotTrib{Fed,Est,Mun}  |  pTotTrib{Fed,Est,Mun}
      │           |  indTotTrib=0  |  pTotTribSN
```

Notes:
- Decimals are strings with a fixed pattern, **2 decimal places** (`TSDec15V2`
  etc.) — render with exactly two decimals, dot separator, no thousands.
- `totTrib` is **required** — even if we declare "no estimate" via `indTotTrib=0`
  or report via `pTotTribSN` (Simples Nacional percentage). Decide our path.
- ISSQN withholding (`tpRetISSQN`) decides whether the tomador retains the tax.

### Our case: Simples Nacional [project fact]

We are **optante do Simples Nacional**, which fixes several of these fields:

- `prest/regTrib/opSimpNac` = **`3`** (Optante — ME/EPP). The XSD enum is
  **1 = Não Optante, 2 = Optante MEI, 3 = Optante ME/EPP** (`TSOpSimpNac`) — an
  earlier revision of this doc wrongly said `1` = optante. Set `regApTribSN` per
  our apuração regime when applicable.
- **No `IBSCBS` group** for the all-inclusive Simples path (taxes inside the
  DAS): proven live 2026-08-28 — SEFIN restrita generated our NFS-e without it
  (see `src/test/resources/dps/dps-export-accepted.xml`). The NT-004 mandate
  applies to regime-normal emitters (and to a Simples company that opts into the
  hybrid "IBS/CBS por fora" regime).
- **Rio requires `cServ/cTribMun`** (municipal complement to `cTribNac`;
  `010401`+`003` = programa sob encomenda) — omitting it gets **E0312**.
- **Exportação (our primary flow)**: `tribISSQN=3` + `cPaisResult`; foreign
  `toma` (NIF or `cNaoNIF` + `endExt`); `serv/comExt` with `mdPrestacao`,
  `vincPrest`, `tpMoeda` (BACEN numeric, USD=220), `vServMoeda`, and the fixed
  tail `mecAFComexP/T=01`, `movTempBens=1`, `mdic=0`. No ISSQN is due; total-tax
  transparency still via `pTotTribSN`.
- Total-tax transparency goes through **`totTrib/pTotTribSN`** (the Simples
  Nacional percentage branch), **not** the `vTotTrib`/`pTotTrib` monetary/percent
  breakdown used by regime-normal taxpayers.
- **ISS is recolhido inside the DAS** (PGDAS-D, on gross revenue) — there is **no
  separate municipal ISS guia**. So `pAliq`/ISS value handling follows the Simples
  path, and the Rio "auto ISS guia" integration does not apply to us.
- A separate ISS collection only arises if ISS is **retido na fonte** by the
  tomador (`tpRetISSQN` ≠ não-retido / substituição tributária). Handle that as a
  distinct branch when the tomador is the responsible party.

## 4.8 `IBSCBS` — the 2026 tax reform block

The XSD already carries the **IBS/CBS** (Reforma Tributária) structure
(`TCRTCInfoIBSCBS`). Highlights:

```
IBSCBS
├─ finNFSe        enum {0}
├─ indFinal?      enum 0,1   (final consumer)
├─ cIndOp         6 digits   (operation indicator — Anexo C / INDOP)
├─ tpOper?        enum 1..5
├─ gRefNFSe?      referenced NFS-e keys (1..99)
├─ indDest        enum 0,1
├─ dest?          destinatário (CNPJ/CPF/NIF + nome + endereço)
├─ imovel?        property (CIB / endereço)
└─ valores  (REQUIRED)
   ├─ gReeRepRes?  reembolsos/repasses/ressarcimentos (docs list)
   └─ trib  → gIBSCBS{ CST(3), cClassTrib(6), cCredPres?, gTribRegular?, gDif? }
```

For now this block is **optional** in the schema, but it is the future of service
taxation. **Model it in our domain from day one** (even if mostly zero/absent)
so we are not re-architecting when it becomes mandatory in our município. Codes
come from **Anexo C (INDOP / IBS-CBS)**.

## 4.9 Validation strategy

- **Validate every DPS against `schemas/1.01/DPS_v1.01.xsd` locally before
  signing and sending.** Cheap, and catches the majority of structural errors
  offline.
- Beyond XSD, SEFIN applies **business rules (RN)** documented in **Anexo I** —
  these are *not* expressible in XSD (cross-field math, parameter lookups). They
  surface as `cStat` rejections; map and surface them clearly.
- Schema files of interest: `DPS_v1.01.xsd`, `NFSe_v1.01.xsd`,
  `tiposComplexos_v1.01.xsd`, `tiposSimples_v1.01.xsd`,
  `tiposEventos_v1.01.xsd`, `xmldsig-core-schema.xsd`.
