package br.com.nfse.emission;


import java.util.List;

/**
 * The JSON input for {@code POST /nfse/send} and {@code POST /nfse/validate}:
 * a plain description of the sale plus the emitter's own fiscal situation. The
 * service builds, validates and signs the DPS XML from this — callers never
 * touch XML. All values are strings exactly as the layout wants them rendered
 * (codes, and decimals with two places, dot separator).
 *
 * <p>Service export (the primary workflow here): set {@code values.tribISSQN=3},
 * {@code values.cPaisResult}, a foreign {@code tomador} (NIF or cNaoNif +
 * enderecoExterior) and the {@code comercioExterior} block — mirroring the DPS
 * accepted live by SEFIN restrita on 2026-08-28 (dps-export-accepted.xml).
 */
public record EmitNfseRequest(
        Emitter emitter,
        Dps dps,
        Service service,
        Values values,
        IbsCbs ibsCbs,
        Tomador tomador,
        Tomador intermediario,
        ComercioExterior comercioExterior,
        Substituicao substituicao
) {

    public EmitNfseRequest(Emitter emitter, Dps dps, Service service, Values values, IbsCbs ibsCbs,
                           Tomador tomador, Tomador intermediario, ComercioExterior comercioExterior) {
        this(emitter, dps, service, values, ibsCbs, tomador, intermediario, comercioExterior, null);
    }

    /**
     * Who is emitting. Exactly one of cnpj/cpf. opSimpNac: 1=Não Optante, 2=MEI,
     * 3=ME/EPP. {@code fone} and {@code email} are optional in the layout but are
     * printed on the DANFSe as the emitter's contact, so a note that carried them
     * before should keep carrying them.
     */
    public record Emitter(
            String cnpj,
            String cpf,
            String municipality,
            String opSimpNac,
            String regApTribSN,
            String regEspTrib,
            String fone,
            String email
    ) {
        public Emitter(String cnpj, String cpf, String municipality, String opSimpNac,
                       String regApTribSN, String regEspTrib) {
            this(cnpj, cpf, municipality, opSimpNac, regApTribSN, regEspTrib, null, null);
        }
    }

    /**
     * Cancelamento por substituição — set this to replace an existing NFS-e. SEFIN
     * validates the new DPS, cancels the note named by {@code chSubstda} with a
     * Cancelamento por Substituição event, and issues this one in its place, all in
     * the single {@code POST /nfse} call (docs/05 §5.3 path B).
     *
     * <p>cMotivo: 01 Desenquadramento do Simples, 02 Enquadramento no Simples,
     * 03 Inclusão retroativa de imunidade/isenção, 04 Exclusão retroativa,
     * 05 Rejeição pelo tomador/intermediário, 99 Outros. xMotivo needs at least
     * 15 characters when present.
     */
    public record Substituicao(
            String chSubstda,
            String cMotivo,
            String xMotivo
    ) {}

    /** Document numbering + dates. dhEmi/dCompet default to now/today when omitted. */
    public record Dps(
            String serie,
            String number,
            String dhEmi,
            String dCompet
    ) {}

    /**
     * What was sold. locPrestacao defaults to the emitter municipality. nbs is
     * the 9-digit NBS code (Anexo B) — SEFIN requires it whenever the IBS/CBS
     * group is declared (rule E0322). cTribMun is the municipality's own
     * complement to the national code (e.g. Rio: 010401 + "003" = elaboração de
     * programa sob encomenda) — without it Rio rejects with E0312.
     */
    public record Service(
            String cTribNac,
            String cTribMun,
            String description,
            String nbs,
            String locPrestacao,
            InformacoesComplementares infoCompl
    ) {
        public Service(String cTribNac, String cTribMun, String description, String nbs, String locPrestacao) {
            this(cTribNac, cTribMun, description, nbs, locPrestacao, null);
        }
    }

    /**
     * Free-form additions to the note — the fields nearly every real invoice needs:
     * a contract or instalment note ({@code xInfComp}, up to 2000 chars, printed in
     * INFORMAÇÕES COMPLEMENTARES on the DANFSe), the customer's purchase order
     * ({@code xPed}) and its item numbers, and a reference to another document
     * ({@code docRef}).
     */
    public record InformacoesComplementares(
            String xInfComp,
            String docRef,
            String xPed,
            List<String> itensPedido
    ) {}

    /**
     * Money + ISSQN. tribISSQN: 1=tributável, 2=imunidade, 3=exportação,
     * 4=não incidência; cPaisResult (ISO2) is required with 3. pTotTribSN
     * absent → declares indTotTrib=0 instead.
     */
    public record Values(
            String vServ,
            String tribISSQN,
            String cPaisResult,
            String tpRetISSQN,
            String pTotTribSN,
            Descontos descontos,
            DeducaoReducao deducaoReducao,
            TributacaoFederal tributacaoFederal
    ) {
        public Values(String vServ, String tribISSQN, String cPaisResult,
                      String tpRetISSQN, String pTotTribSN) {
            this(vServ, tribISSQN, cPaisResult, tpRetISSQN, pTotTribSN, null, null, null);
        }
    }

    /**
     * Discounts on the service value. The <em>unconditional</em> one is always
     * granted and reduces the ISSQN base; the <em>conditional</em> one depends on
     * the taker meeting a condition (early payment, say) and does not.
     */
    public record Descontos(
            String incondicionado,
            String condicionado
    ) {}

    /**
     * Deduções/reduções of the calculation base (construction materials, subcontracted
     * services…). The layout models this as a <em>choice</em>: express the deduction
     * as a flat {@code percentual}, OR a flat {@code valor}, OR an itemised list of
     * {@code documentos} — never more than one, and never none.
     */
    public record DeducaoReducao(
            String percentual,
            String valor,
            List<DocumentoDeducao> documentos
    ) {
        public DeducaoReducao {
            int given = (percentual != null ? 1 : 0) + (valor != null ? 1 : 0)
                    + (documentos != null && !documentos.isEmpty() ? 1 : 0);
            if (given != 1) {
                throw new IllegalArgumentException(
                        "deducaoReducao must carry exactly one of percentual, valor or documentos"
                                + " — the layout declares them as a choice (given: " + given + ")");
            }
        }
    }

    /**
     * One document backing a deduction. Exactly one identifier must be given — an
     * NFS-e key, an NF-e key, a fiscal document number, or any other document
     * number. {@code tipo} is the TSIdeDedRed code; {@code dataEmissao} is ISO.
     */
    public record DocumentoDeducao(
            String chNFSe,
            String chNFe,
            String nDocFisc,
            String nDoc,
            String tipo,
            String descricaoOutrasDeducoes,
            String dataEmissao,
            String valorDedutivel,
            String valorDeducao
    ) {}

    /**
     * Federal taxes on the service: PIS/COFINS as computed, plus the amounts the
     * taker withholds (contribuição previdenciária, IRRF, CSLL). Withholding is
     * routine once the customer is a legal entity, and the DANFSe prints all of it
     * in TRIBUTAÇÃO FEDERAL.
     */
    public record TributacaoFederal(
            PisCofins pisCofins,
            String vRetCP,
            String vRetIRRF,
            String vRetCSLL
    ) {}

    /** tpRet: 0 = não retido, 1 = retido. cst is the TSTipoCST code. */
    public record PisCofins(
            String cst,
            String vBc,
            String aliqPis,
            String aliqCofins,
            String vPis,
            String vCofins,
            String tpRet
    ) {}

    /** Reforma Tributária group — mandatory for regime-normal emitters since 2026-08-03; all-inclusive Simples omits it. */
    public record IbsCbs(
            String finNFSe,
            String cIndOp,
            String indDest,
            String cst,
            String cClassTrib,
            Tomador dest
    ) {}

    /**
     * A person on the note — used for both {@code tomador} and
     * {@code intermediario} (the DPS types them identically as TCInfoPessoa).
     * National: cnpj or cpf + nome. Foreign: nif OR cNaoNif (0=não informado
     * na origem, 1=dispensado, 2=não exigido) + nome + enderecoExterior.
     */
    public record Tomador(
            String cnpj,
            String cpf,
            String nome,
            String nif,
            String cNaoNif,
            EnderecoExterior enderecoExterior,
            EnderecoNacional enderecoNacional
    ) {
        public Tomador(String cnpj, String cpf, String nome, String nif, String cNaoNif,
                       EnderecoExterior enderecoExterior) {
            this(cnpj, cpf, nome, nif, cNaoNif, enderecoExterior, null);
        }
    }

    /**
     * Address inside Brazil. Required by several indicadores de operação —
     * without it SEFIN rejects the DPS with E0234.
     */
    public record EnderecoNacional(
            String municipio,
            String cep,
            String logradouro,
            String numero,
            String complemento,
            String bairro
    ) {}

    public record EnderecoExterior(
            String pais,
            String cep,
            String cidade,
            String estadoProvinciaRegiao,
            String logradouro,
            String numero,
            String complemento,
            String bairro
    ) {}

    /**
     * comExt block, required for exportação. Defaults mirror what the Emissor
     * Web sends: mecAFComexP/mecAFComexT "01" (nenhum), movTempBens "1" (não),
     * mdic "0" (don't share with MDIC).
     */
    public record ComercioExterior(
            String mdPrestacao,
            String vincPrest,
            String tpMoeda,
            String vServMoeda,
            String mecAFComexP,
            String mecAFComexT,
            String movTempBens,
            String mdic
    ) {
        public String mecAFComexPOrDefault() { return mecAFComexP != null ? mecAFComexP : "01"; }
        public String mecAFComexTOrDefault() { return mecAFComexT != null ? mecAFComexT : "01"; }
        public String movTempBensOrDefault() { return movTempBens != null ? movTempBens : "1"; }
        public String mdicOrDefault() { return mdic != null ? mdic : "0"; }
    }
}
