# 5. Events (cancellation, substitution, manifestação)

After an NFS-e exists, changes to its lifecycle happen through **events**
(*eventos*) — themselves signed XML fiscal documents linked to the note's access
key. Source: *Manual de Contribuintes – Emissor Público* + `tiposEventos_v1.01.xsd`.

## 5.1 The generic event API

**[CONFIRMED]** One generic endpoint handles all event types:

```
POST /nfse/{chaveAcesso}/eventos
```

The request is a **Pedido de Registro de Evento** with:

- a **generic part** (author identification, event identification, linked NFS-e
  key, sequence number), and
- a **specific part**: the XML for the concrete event type.
- the whole message is **digitally signed** by the event author.

Flow **[CONFIRMED]**:
1. We send the pedido de registro de evento.
2. SEFIN validates → registers or rejects.
3. On accept, SEFIN generates the *Evento* DF-e, linked to the note's key.
4. SEFIN returns accept/reject to us.

The note must already exist at the generating SEFIN. Multiple events of the same
type may exist (hence `numSeqEvento`); when a type allows only one, the sequence
is `1`.

## 5.2 Event type catalog

**[CONFIRMED 2026-08-28]** — read from the `xs:documentation` of each element in
`tiposEventos_v1.01.xsd`, so these are the official names, not inferences. (An
earlier revision of this table guessed the meanings and got several wrong: the
2xx group is confirmação/rejeição, not ciência/desconhecimento, and `101103` is
a request for fiscal analysis, not a cancellation variant.)

| Element | Code | Meaning | Author |
|---------|------|---------|--------|
| `e101101` | 101101 | Cancelamento de NFS-e | emitter |
| `e105102` | 105102 | Cancelamento de NFS-e por Substituição | SEFIN (side effect of a `subst` DPS) |
| `e101103` | 101103 | Solicitação de Análise Fiscal para Cancelamento | emitter |
| `e105104` | 105104 | Cancelamento Deferido por Análise Fiscal | fisco |
| `e105105` | 105105 | Cancelamento Indeferido por Análise Fiscal | fisco |
| `e202201` | 202201 | Confirmação do Prestador | prestador |
| `e203202` | 203202 | Confirmação do Tomador | tomador |
| `e204203` | 204203 | Confirmação do Intermediário | intermediário |
| `e205204` | 205204 | Confirmação Tácita | SEFIN |
| `e202205` | 202205 | Rejeição do Prestador | prestador |
| `e203206` | 203206 | Rejeição do Tomador | tomador |
| `e204207` | 204207 | Rejeição do Intermediário | intermediário |
| `e205208` | 205208 | Anulação da Rejeição | — |
| `e305101` | 305101 | Cancelamento de NFS-e por Ofício | município / fisco |
| `e305102` | 305102 | Bloqueio de NFS-e por Ofício | município / fisco |
| `e305103` | 305103 | Desbloqueio de NFS-e por Ofício | município / fisco |

The element name is what goes inside `infPedReg`; the numeric code is what goes
in the pedido's `Id` and in the consultation path. `EventType` models the three
an emitter can raise; the rest are named there only so events read back can be
identified.

### Pedido structure

```xml
<pedRegEvento versao="1.01">
  <infPedReg Id="PRE{chave 50}{tipoEvento 6}">
    <tpAmb/> <verAplic/> <dhEvento/>
    <CNPJAutor/>|<CPFAutor/>          <!-- a choice -->
    <chNFSe/>
    <e101101><xDesc/><cMotivo/><xMotivo/></e101101>
  </infPedReg>
  <Signature/>
</pedRegEvento>
```

Two traps the XSD enforces and we now catch offline: `xDesc` is a **fixed
enumeration** per event type (`"Cancelamento de NFS-e"` exactly), and `xMotivo`
has a **15-character minimum** — a terse "erro" is rejected.

## 5.3 Cancellation — two distinct paths

**A. Direct cancellation (an event).** `POST /nfse/{chave}/eventos` with an
`e101101` payload. `cMotivo`: 1 = Erro na Emissão, 2 = Serviço não Prestado,
9 = Outros. Subject to a municipal time window after emission; past it, use path
B or `e101103` (fiscal analysis).

**Implemented and PROVEN LIVE in restrita on 2026-08-28** (nota 13,
chave `…526087675240780`, cancelled and read back):
`DELETE /nfse/{chave}/cancel` — JSON in, JSON out; the service builds,
XSD-validates, signs, self-verifies and submits. The same path accepts `POST`
too, because a cancellation carries a mandatory body and bodies on DELETE are
legal but routinely stripped by proxies. `POST /nfse/{chave}/cancel/validate` is
the offline dry run; `GET /nfse/{chave}/eventos/{tipo}/{seq}` reads events back.

SEFIN assigns the sequence: the registered Evento came back as
`EVT{chave}101101001` — confirming TSIdEvento = the pedido's id plus a 3-digit
`nPedRegEvento`.

**B. Cancellation by substitution (via `POST /nfse`).** **[CONFIRMED]** If a new
DPS carries the access key of an existing NFS-e (the `subst` block), SEFIN:
1. validates the new DPS,
2. generates a **Cancelamento por Substituição** event on the original (cancels
   it), and
3. issues the **substitute NFS-e**, returned to us.

Use B when correcting/replacing a note in one operation; use A to simply void.

**Implemented and PROVEN LIVE 2026-08-28**: nota 16 (`…726085135035240`)
replaced nota 14 (`…626085725734100`), and SEFIN raised the `e105102` event on
the original by itself, carrying `chSubstituta` = the replacement's key. Set
`substituicao` on `POST /nfse/send`
(`{chSubstda, cMotivo, xMotivo}`) and the DPS carries a `subst` block between
`cLocEmi` and `prest`. `cMotivo` here is the **substitution** catalog, not the
cancellation one: 01 Desenquadramento do Simples, 02 Enquadramento no Simples,
03 Inclusão retroativa de imunidade/isenção, 04 Exclusão retroativa,
05 Rejeição pelo tomador/intermediário, 99 Outros.

> **E0063 (learned live):** when the emitter is a **Simples Nacional optante**,
> the substituting DPS may NOT change `dCompet`, the tomador's identification or
> `vServ` — only things like the service description. A first attempt that raised
> `vServ` from 1500 to 1750 was rejected. The linter warns about this (`SUB001`);
> it cannot verify it offline, since that needs the original note.

## 5.4 Reading events back

**CORRECTED 2026-08-28 by probing the live restrita API** — the manual lists
three consultation paths, but only the fully-qualified one exists:

| Path | Live result |
|------|-------------|
| `GET /nfse/{chave}/eventos/{tipoEvento}/{numSeqEvento}` | **200** — works |
| `GET /nfse/{chave}/eventos` | **405** — "does not support http method 'GET'" |
| `GET /nfse/{chave}/eventos/{tipoEvento}` | **404** |
| ADN: `GET /NFSe/{chave}/Eventos` | distribution view (not probed) |

Its **response shape is also undocumented**: the Swagger promises the same
`eventoXmlGZipB64` the registration returns, but the live API answers

```json
{"dataHoraProcessamento": "...", "tipoAmbiente": 2, "versaoAplicativo": "...",
 "eventos": [{"chaveAcesso": "...", "tipoEvento": 101101,
              "numeroPedidoRegistroEvento": 1, "dataHoraRecebimento": "...",
              "arquivoXml": "<base64>"}]}
```

and `arquivoXml` is **double-encoded**: plain base64 wrapping the gzip+base64
text, so it needs one extra base64 decode. `SefinClient` handles both shapes.
Enumerating a note's events therefore means probing the type codes you care
about, not listing them.

For reconciliation we mainly care about: cancellations we issued, and
manifestações filed by tomadores against notes where we are an actor (pulled via
ADN NSU walk — see [03](03-api-reference.md#32-adn--contribuintes-distribuição)).

## 5.5 Schema references

- `pedRegEvento_v1.01.xsd` — the Pedido de Registro de Evento envelope.
- `evento_v1.01.xsd` — the generated Evento document.
- `tiposEventos_v1.01.xsd` — all `TEnnnnnn` event payload types.
