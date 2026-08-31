package br.com.nfse.emission;

import br.com.nfse.config.BrasiliaTime;
import br.com.nfse.config.EmitterSoftware;
import br.com.nfse.config.NfseProperties;
import br.com.nfse.config.SafeXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.time.Clock;

/**
 * Builds the DPS XML (layout v1.01) from an {@link EmitNfseRequest}. The
 * document shape mirrors docs/04; {@code tpAmb} always comes from the active
 * environment config, never from the caller, so a note can't target the wrong
 * SEFIN environment. The {@code infDPS@Id} is composed per the documented rule:
 * "DPS" + cLocEmi(7) + inscription type(1: 1=CPF, 2=CNPJ) + inscription(14,
 * left-zero-padded) + serie(5) + number(15).
 */
public class DpsBuilder {

    private static final String NS = "http://www.sped.fazenda.gov.br/nfse";

    private final NfseProperties props;
    private final Clock clock;

    public DpsBuilder(NfseProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /** The unsigned DPS XML for this request. */
    public String build(EmitNfseRequest request) {
        try {
            Document doc = SafeXml.documentBuilderFactory().newDocumentBuilder().newDocument();
            Element dps = doc.createElementNS(NS, "DPS");
            dps.setAttribute("versao", "1.01");
            doc.appendChild(dps);

            Element infDps = child(doc, dps, "infDPS");
            infDps.setAttribute("Id", dpsId(request));

            text(doc, infDps, "tpAmb", String.valueOf(props.tpAmb()));
            text(doc, infDps, "dhEmi", request.dps().dhEmi() != null
                    ? request.dps().dhEmi()
                    : BrasiliaTime.now(clock));
            text(doc, infDps, "verAplic", EmitterSoftware.VER_APLIC);
            text(doc, infDps, "serie", request.dps().serie());
            text(doc, infDps, "nDPS", request.dps().number());
            text(doc, infDps, "dCompet", request.dps().dCompet() != null
                    ? request.dps().dCompet()
                    : BrasiliaTime.today(clock).toString());
            text(doc, infDps, "tpEmit", "1");
            text(doc, infDps, "cLocEmi", request.emitter().municipality());
            appendSubst(doc, infDps, request.substituicao());

            appendPrest(doc, infDps, request.emitter());
            if (request.tomador() != null) {
                appendPessoa(doc, infDps, "toma", request.tomador());
            }
            if (request.intermediario() != null) {
                appendPessoa(doc, infDps, "interm", request.intermediario());
            }
            appendServ(doc, infDps, request);
            appendValores(doc, infDps, request.values());
            if (request.ibsCbs() != null) {
                appendIbsCbs(doc, infDps, request.ibsCbs());
            }
            return serialize(doc);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build DPS: " + e.getMessage(), e);
        }
    }

    /** The composed infDPS@Id for this request. */
    public String dpsId(EmitNfseRequest request) {
        EmitNfseRequest.Emitter emitter = request.emitter();
        String inscription = emitter.cnpj() != null
                ? "2" + emitter.cnpj()
                : "1" + pad(emitter.cpf(), 14);
        return "DPS" + emitter.municipality() + inscription
                + pad(request.dps().serie(), 5) + pad(request.dps().number(), 15);
    }

    /** subst names the note this DPS replaces; it sits between cLocEmi and prest. */
    private void appendSubst(Document doc, Element infDps, EmitNfseRequest.Substituicao substituicao) {
        if (substituicao == null) {
            return;
        }
        Element subst = child(doc, infDps, "subst");
        text(doc, subst, "chSubstda", substituicao.chSubstda());
        text(doc, subst, "cMotivo", substituicao.cMotivo());
        if (substituicao.xMotivo() != null) {
            text(doc, subst, "xMotivo", substituicao.xMotivo());
        }
    }

    private void appendPrest(Document doc, Element infDps, EmitNfseRequest.Emitter emitter) {
        Element prest = child(doc, infDps, "prest");
        if (emitter.cnpj() != null) {
            text(doc, prest, "CNPJ", emitter.cnpj());
        } else {
            text(doc, prest, "CPF", emitter.cpf());
        }
        Element regTrib = child(doc, prest, "regTrib");
        text(doc, regTrib, "opSimpNac", emitter.opSimpNac());
        if (emitter.regApTribSN() != null) {
            text(doc, regTrib, "regApTribSN", emitter.regApTribSN());
        }
        text(doc, regTrib, "regEspTrib", emitter.regEspTrib() != null ? emitter.regEspTrib() : "0");
    }

    /**
     * toma and interm share the same TCInfoPessoa shape; the tomador variant
     * mirrors the DPS accepted by SEFIN restrita on 2026-08-28
     * (dps-export-accepted.xml).
     */
    private void appendPessoa(Document doc, Element infDps, String name, EmitNfseRequest.Tomador tomador) {
        Element toma = child(doc, infDps, name);
        if (tomador.cnpj() != null) {
            text(doc, toma, "CNPJ", tomador.cnpj());
        } else if (tomador.cpf() != null) {
            text(doc, toma, "CPF", tomador.cpf());
        } else if (tomador.nif() != null) {
            text(doc, toma, "NIF", tomador.nif());
        } else {
            text(doc, toma, "cNaoNIF", tomador.cNaoNif());
        }
        text(doc, toma, "xNome", tomador.nome());
        EmitNfseRequest.EnderecoNacional nac = tomador.enderecoNacional();
        if (nac != null) {
            Element end = child(doc, toma, "end");
            Element endNac = child(doc, end, "endNac");
            text(doc, endNac, "cMun", nac.municipio());
            text(doc, endNac, "CEP", nac.cep());
            text(doc, end, "xLgr", nac.logradouro());
            text(doc, end, "nro", nac.numero());
            if (nac.complemento() != null) {
                text(doc, end, "xCpl", nac.complemento());
            }
            text(doc, end, "xBairro", nac.bairro());
        }
        EmitNfseRequest.EnderecoExterior ext = tomador.enderecoExterior();
        if (ext != null) {
            Element end = child(doc, toma, "end");
            Element endExt = child(doc, end, "endExt");
            text(doc, endExt, "cPais", ext.pais());
            text(doc, endExt, "cEndPost", ext.cep());
            text(doc, endExt, "xCidade", ext.cidade());
            text(doc, endExt, "xEstProvReg", ext.estadoProvinciaRegiao());
            text(doc, end, "xLgr", ext.logradouro());
            text(doc, end, "nro", ext.numero());
            if (ext.complemento() != null) {
                text(doc, end, "xCpl", ext.complemento());
            }
            text(doc, end, "xBairro", ext.bairro());
        }
    }

    private void appendServ(Document doc, Element infDps, EmitNfseRequest request) {
        Element serv = child(doc, infDps, "serv");
        Element locPrest = child(doc, serv, "locPrest");
        text(doc, locPrest, "cLocPrestacao", request.service().locPrestacao() != null
                ? request.service().locPrestacao()
                : request.emitter().municipality());
        Element cServ = child(doc, serv, "cServ");
        text(doc, cServ, "cTribNac", request.service().cTribNac());
        if (request.service().cTribMun() != null) {
            text(doc, cServ, "cTribMun", request.service().cTribMun());
        }
        text(doc, cServ, "xDescServ", request.service().description());
        if (request.service().nbs() != null) {
            text(doc, cServ, "cNBS", request.service().nbs());
        }
        EmitNfseRequest.ComercioExterior ce = request.comercioExterior();
        if (ce != null) {
            Element comExt = child(doc, serv, "comExt");
            text(doc, comExt, "mdPrestacao", ce.mdPrestacao());
            text(doc, comExt, "vincPrest", ce.vincPrest());
            text(doc, comExt, "tpMoeda", ce.tpMoeda());
            text(doc, comExt, "vServMoeda", ce.vServMoeda());
            text(doc, comExt, "mecAFComexP", ce.mecAFComexPOrDefault());
            text(doc, comExt, "mecAFComexT", ce.mecAFComexTOrDefault());
            text(doc, comExt, "movTempBens", ce.movTempBensOrDefault());
            text(doc, comExt, "mdic", ce.mdicOrDefault());
        }
        appendInfoCompl(doc, serv, request.service().infoCompl());
    }

    private void appendValores(Document doc, Element infDps, EmitNfseRequest.Values values) {
        Element valores = child(doc, infDps, "valores");
        Element vServPrest = child(doc, valores, "vServPrest");
        text(doc, vServPrest, "vServ", values.vServ());
        appendDescontos(doc, valores, values.descontos());
        appendDedRed(doc, valores, values.deducaoReducao());
        Element trib = child(doc, valores, "trib");
        Element tribMun = child(doc, trib, "tribMun");
        text(doc, tribMun, "tribISSQN", values.tribISSQN());
        if (values.cPaisResult() != null) {
            text(doc, tribMun, "cPaisResult", values.cPaisResult());
        }
        text(doc, tribMun, "tpRetISSQN", values.tpRetISSQN());
        appendTribFed(doc, trib, values.tributacaoFederal());
        Element totTrib = child(doc, trib, "totTrib");
        if (values.pTotTribSN() != null) {
            text(doc, totTrib, "pTotTribSN", values.pTotTribSN());
        } else {
            text(doc, totTrib, "indTotTrib", "0");
        }
    }

    /** vDescCondIncond sits between vServPrest and vDedRed. */
    private void appendDescontos(Document doc, Element valores, EmitNfseRequest.Descontos descontos) {
        if (descontos == null
                || (descontos.incondicionado() == null && descontos.condicionado() == null)) {
            return;
        }
        Element el = child(doc, valores, "vDescCondIncond");
        if (descontos.incondicionado() != null) {
            text(doc, el, "vDescIncond", descontos.incondicionado());
        }
        if (descontos.condicionado() != null) {
            text(doc, el, "vDescCond", descontos.condicionado());
        }
    }

    /**
     * vDedRed is a CHOICE in the layout: a flat percentage, a flat value, OR an
     * itemised document list — exactly one, which {@code DeducaoReducao} enforces.
     */
    private void appendDedRed(Document doc, Element valores, EmitNfseRequest.DeducaoReducao dedRed) {
        if (dedRed == null) {
            return;
        }
        Element el = child(doc, valores, "vDedRed");
        if (dedRed.percentual() != null) {
            text(doc, el, "pDR", dedRed.percentual());
            return;
        }
        if (dedRed.valor() != null) {
            text(doc, el, "vDR", dedRed.valor());
            return;
        }
        Element documentos = child(doc, el, "documentos");
        for (EmitNfseRequest.DocumentoDeducao d : dedRed.documentos()) {
            Element doc1 = child(doc, documentos, "docDedRed");
            // exactly one identifier, per the XSD's choice
            if (d.chNFSe() != null) {
                text(doc, doc1, "chNFSe", d.chNFSe());
            } else if (d.chNFe() != null) {
                text(doc, doc1, "chNFe", d.chNFe());
            } else if (d.nDocFisc() != null) {
                text(doc, doc1, "nDocFisc", d.nDocFisc());
            } else {
                text(doc, doc1, "nDoc", d.nDoc());
            }
            text(doc, doc1, "tpDedRed", d.tipo());
            if (d.descricaoOutrasDeducoes() != null) {
                text(doc, doc1, "xDescOutDed", d.descricaoOutrasDeducoes());
            }
            text(doc, doc1, "dtEmiDoc", d.dataEmissao());
            text(doc, doc1, "vDedutivelRedutivel", d.valorDedutivel());
            text(doc, doc1, "vDeducaoReducao", d.valorDeducao());
        }
    }

    /** tribFed sits between tribMun and totTrib. */
    private void appendTribFed(Document doc, Element trib, EmitNfseRequest.TributacaoFederal fed) {
        if (fed == null) {
            return;
        }
        Element el = child(doc, trib, "tribFed");
        EmitNfseRequest.PisCofins pc = fed.pisCofins();
        if (pc != null) {
            Element piscofins = child(doc, el, "piscofins");
            text(doc, piscofins, "CST", pc.cst());
            optional(doc, piscofins, "vBCPisCofins", pc.vBc());
            optional(doc, piscofins, "pAliqPis", pc.aliqPis());
            optional(doc, piscofins, "pAliqCofins", pc.aliqCofins());
            optional(doc, piscofins, "vPis", pc.vPis());
            optional(doc, piscofins, "vCofins", pc.vCofins());
            optional(doc, piscofins, "tpRetPisCofins", pc.tpRet());
        }
        optional(doc, el, "vRetCP", fed.vRetCP());
        optional(doc, el, "vRetIRRF", fed.vRetIRRF());
        optional(doc, el, "vRetCSLL", fed.vRetCSLL());
    }

    /** infoCompl is the last child of serv; its own children follow the XSD order. */
    private void appendInfoCompl(Document doc, Element serv, EmitNfseRequest.InformacoesComplementares info) {
        if (info == null) {
            return;
        }
        Element el = child(doc, serv, "infoCompl");
        optional(doc, el, "docRef", info.docRef());
        optional(doc, el, "xPed", info.xPed());
        if (info.itensPedido() != null && !info.itensPedido().isEmpty()) {
            Element items = child(doc, el, "gItemPed");
            for (String item : info.itensPedido()) {
                text(doc, items, "xItemPed", item);
            }
        }
        optional(doc, el, "xInfComp", info.xInfComp());
    }

    private void appendIbsCbs(Document doc, Element infDps, EmitNfseRequest.IbsCbs ibsCbs) {
        Element group = child(doc, infDps, "IBSCBS");
        text(doc, group, "finNFSe", ibsCbs.finNFSe());
        text(doc, group, "cIndOp", ibsCbs.cIndOp());
        text(doc, group, "indDest", ibsCbs.indDest());
        if (ibsCbs.dest() != null) {
            appendPessoa(doc, group, "dest", ibsCbs.dest());
        }
        Element valores = child(doc, group, "valores");
        Element trib = child(doc, valores, "trib");
        Element gIbsCbs = child(doc, trib, "gIBSCBS");
        text(doc, gIbsCbs, "CST", ibsCbs.cst());
        text(doc, gIbsCbs, "cClassTrib", ibsCbs.cClassTrib());
    }

    private static Element child(Document doc, Element parent, String name) {
        Element el = doc.createElementNS(NS, name);
        parent.appendChild(el);
        return el;
    }

    private static void text(Document doc, Element parent, String name, String value) {
        child(doc, parent, name).setTextContent(value);
    }

    /** Writes the element only when the caller supplied a value. */
    private static void optional(Document doc, Element parent, String name, String value) {
        if (value != null) {
            text(doc, parent, name, value);
        }
    }

    private static String pad(String value, int width) {
        return "0".repeat(Math.max(0, width - value.length())) + value;
    }

    private static String serialize(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter out = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toString();
    }
}
