package br.com.nfse.danfse;

import br.com.nfse.config.BrasiliaTime;
import br.com.nfse.config.SafeXml;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Renders the DANFSe PDF locally from the NFS-e XML — the official generation
 * API was suspended on 2026-08-03 (NT-008) and emitter software must produce
 * this document itself.
 *
 * <p>The layout is a faithful replica of the official "DANFSe v2.0" template
 * produced by the gov.br portal (PDFsharp): every coordinate, font size, line
 * width and gray fill below was read out of a reference PDF's content stream,
 * not eyeballed. Text is positioned by baseline in top-down page coordinates;
 * rules and fills are drawn in native bottom-up PDF coordinates exactly as the
 * reference draws them. The portal embeds Arial Bold (labels/titles) and
 * Microsoft Sans Serif (values); we embed the same fonts when the host has
 * them, falling back to the metric-compatible Liberation Sans (vendored, OFL)
 * so the page renders identically in every viewer. Homologation notes
 * (DPS tpAmb=2) additionally carry the NT-008-mandated "NFS-e SEM VALIDADE
 * JURÍDICA" banner.
 */
public class DanfseGenerator {

    private static final String NS = "http://www.sped.fazenda.gov.br/nfse";
    private static final float W = 595;
    private static final float H = 842;
    /** Text x of the four grid columns (labels and values). */
    private static final float[] TX = {11.9055f, 156.4724f, 301.0394f, 445.6063f};
    /** Cell-border x of the four grid columns, and the right border. */
    private static final float[] CX = {8.5039f, 153.0709f, 297.6378f, 442.2047f};
    private static final float CR = 586.7717f;
    private static final Color GRAY = new Color(242, 242, 242);
    /** Height of a section-title gray cell. */
    private static final float SECTION_H = 19.0742f;
    /** A "NÃO IDENTIFICADO" band is one line; a full person block is a whole section. */
    private static final float BAND_H = 8.4f;
    private static final float PERSON_H = 57.70f;
    /** Vertical step between the lines of a wrapped name. */
    private static final float NAME_LINE_H = 7.92f;

    private static final Map<String, String> ISSQN_SITUATION = Map.of(
            "1", "Operação Tributável", "2", "Imunidade",
            "3", "Exportação de Serviço", "4", "Não incidência");
    private static final Map<String, String> RET_ISSQN = Map.of(
            "1", "Não Retido", "2", "Retido pelo Tomador", "3", "Retido pelo Intermediário");
    private static final Map<String, String> OP_SIMPLES = Map.of(
            "1", "Não Optante",
            "2", "Optante - Microempreendedor Individual (MEI)",
            "3", "Optante - Microempresa ou Empresa de Pequeno Porte (ME/EPP)");
    private static final Map<String, String> REG_AP_SN = Map.of(
            "1", "Regime de apuração dos tributos federais e municipal pelo Simples Nacional",
            "2", "Regime de apuração dos tributos federais pelo SN e ISSQN conforme legislação municipal",
            "3", "Regime de apuração dos tributos federais e municipal conforme respectivas legislações");
    private static final Map<String, String> EMITENTE = Map.of(
            "1", "Prestador", "2", "Tomador", "3", "Intermediário");

    public byte[] generate(String nfseXml) {
        try {
            Document xml = parse(nfseXml);
            Element inf = el(xml.getDocumentElement(), "infNFSe");
            Element emit = el(inf, "emit");
            Element ender = el(emit, "enderNac");
            Element infDps = desc(inf, "infDPS");
            Element prest = el(infDps, "prest");
            Element toma = el(infDps, "toma");
            Element interm = el(infDps, "interm");
            Element cServ = desc(infDps, "cServ");
            Element tribMun = desc(infDps, "tribMun");
            Element totTrib = desc(infDps, "totTrib");

            // SEFIN returns the computed IBS/CBS block on infNFSe; the CST and the
            // operation indicator stay on the DPS group the emitter declared.
            Element rtc = el(inf, "IBSCBS");
            Element rtcDps = desc(infDps, "IBSCBS");
            Element rtcVal = el(rtc, "valores");
            Element rtcUf = el(rtcVal, "uf");
            Element rtcMun = el(rtcVal, "mun");
            Element rtcFed = el(rtcVal, "fed");
            Element rtcTot = el(rtc, "totCIBS");
            Element gIbsCbs = desc(rtcDps, "gIBSCBS");

            String chave = inf.getAttribute("Id").replaceFirst("^NFS", "");
            String tpAmb = t(infDps, "tpAmb");
            boolean homologacao = "2".equals(tpAmb);
            String consultaUrl = (homologacao
                    ? "https://www.producaorestrita.nfse.gov.br/ConsultaPublica?tpc=1&chave="
                    : "https://www.nfse.gov.br/ConsultaPublica?tpc=1&chave=") + chave;

            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage(new PDRectangle(W, H));
                doc.addPage(page);
                PDFont bold = loadFont(doc, BOLD_CANDIDATES,
                        "/danfse/fonts/LiberationSans-Bold.ttf", Standard14Fonts.FontName.HELVETICA_BOLD);
                PDFont regular = loadFont(doc, REGULAR_CANDIDATES,
                        "/danfse/fonts/LiberationSans-Regular.ttf", Standard14Fonts.FontName.HELVETICA);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    G g = new G(cs, bold, regular);

                    // ---- header band: three contiguous gray cells + hairline under them
                    g.fill(CX[0], 802.315f, 144.5669f, 34.0157f);
                    g.fill(CX[1], 802.315f, 289.1339f, 34.0157f);
                    g.fill(CX[3], 802.315f, 144.5669f, 34.0157f);
                    g.hline(CX[0], CR, 802.065f, 0.5f);
                    g.rectStroke(5, 5, 585, 832, 1);
                    drawLogo(doc, cs);
                    // The banner pushes both title lines up; both variants are measured
                    // from official PDFs (produção and produção restrita).
                    g.text("DANFSe v2.0", g.bold, 9, 269.63f, homologacao ? 15.60f : 20.77f);
                    g.text("Documento Auxiliar da NFS-e", g.bold, 9, 234.38f, homologacao ? 25.94f : 31.12f);
                    g.text("Município: " + t(inf, "xLocEmi") + " - " + t(ender, "UF"), g.regular, 8, TX[3], 18.73f);
                    g.text("Ambiente Gerador: " + t(inf, "ambGer"), g.regular, 6, TX[3], 25.94f);
                    g.text("Tipo de Ambiente: " + tpAmb, g.regular, 6, TX[3], 32.74f);
                    if (homologacao) {
                        cs.setNonStrokingColor(Color.RED);
                        g.text("NFS-e SEM VALIDADE JURÍDICA", g.bold, 9, 226.88f, 36.29f);
                        cs.setNonStrokingColor(Color.BLACK);
                    }

                    // ---- chave + QR block
                    g.label7(0, 4, "CHAVE DE ACESSO DA NFS-e", 51.00f);
                    g.value(0, 3, chave, 58.94f);
                    drawQr(doc, cs, consultaUrl);
                    g.text("A autenticidade desta NFS-e pode ser verificada", g.regular, 6, TX[3], 97.40f);
                    g.text("pela leitura deste código QR ou pela consulta da", g.regular, 6, TX[3], 104.20f);
                    g.text("chave de acesso no portal nacional da NFS-e", g.regular, 6, TX[3], 111.00f);

                    g.label7(0, 1, "NÚMERO DA NFS-e", 71.23f);
                    g.label7(1, 1, "COMPETÊNCIA DA NFS-e", 71.23f);
                    g.label7(2, 1, "DATA E HORA DA EMISSÃO DA NFS-e", 71.23f);
                    g.value(0, 1, t(inf, "nNFSe"), 79.16f);
                    g.value(1, 1, date(t(infDps, "dCompet")), 79.16f);
                    g.value(2, 1, dateTime(t(inf, "dhProc")), 79.16f);
                    g.label7(0, 1, "NÚMERO DA DPS", 91.46f);
                    g.label7(1, 1, "SÉRIE DA DPS", 91.46f);
                    g.label7(2, 1, "DATA E HORA DA EMISSÃO DA DPS", 91.46f);
                    g.value(0, 1, t(infDps, "nDPS"), 99.39f);
                    g.value(1, 1, t(infDps, "serie"), 99.39f);
                    g.value(2, 1, dateTime(t(infDps, "dhEmi")), 99.39f);
                    g.fill(CX[0], 716.6664f, 144.5669f, 20.2241f); // gray EMITENTE cell
                    g.label7(0, 1, "EMITENTE DA NFS-e", 111.69f);
                    g.label7(1, 1, "SITUAÇÃO DA NFS-e", 111.69f);
                    g.label7(2, 1, "FINALIDADE", 111.69f);
                    g.value(0, 1, or(EMITENTE.get(t(infDps, "tpEmit"))), 119.62f);
                    g.value(1, 1, "100".equals(t(inf, "cStat")) ? "NFS-e Gerada" : or(t(inf, "cStat")), 119.62f);
                    g.value(2, 1, desc(infDps, "IBSCBS") != null ? "NFS-e regular" : "-", 119.62f);

                    // ---- PRESTADOR / FORNECEDOR
                    g.section("PRESTADOR / FORNECEDOR", 716.42f, 697.0922f, 132.40f);
                    g.label(1, 1, "CNPJ / CPF / NIF", 131.46f);
                    g.label(2, 1, "Indicador Municipal (Inscrição)", 131.46f);
                    g.label(3, 1, "Telefone", 131.46f);
                    g.value(1, 1, or(cnpj(t(emit, "CNPJ") + t(emit, "CPF"))), 139.20f);
                    g.value(2, 1, or(t(emit, "IM")), 139.20f);
                    g.value(3, 1, phone(t(prest, "fone")), 139.20f);
                    g.label(0, 2, "Nome / Nome Empresarial", 150.50f);
                    g.label(2, 1, "Município / Sigla UF", 150.50f);
                    g.label(3, 1, "Código IBGE / CEP", 150.50f);
                    g.value(0, 2, t(emit, "xNome"), 158.30f);
                    g.value(2, 1, t(inf, "xLocEmi") + " / " + t(ender, "UF"), 158.30f);
                    g.value(3, 1, ibge(t(ender, "cMun")) + " / " + cep(t(ender, "CEP")), 158.30f);
                    g.label(0, 2, "Endereço", 169.60f);
                    g.label(2, 2, "E-mail", 169.60f);
                    g.value(0, 2, join(t(ender, "xLgr"), t(ender, "nro"), t(ender, "xBairro")), 177.30f);
                    g.value(2, 2, or(t(prest, "email").toLowerCase()), 177.30f);
                    g.label(0, 1, "Simples Nacional na Data de Competência", 188.70f);
                    g.label(1, 3, "Regime de Apuração Tributária pelo SN", 188.70f);
                    g.value(0, 1, truncate(or(OP_SIMPLES.get(t(desc(prest, "regTrib"), "opSimpNac"))),
                            SIMPLES_MAX_CHARS), 196.40f);
                    g.value(1, 3, or(REG_AP_SN.get(t(desc(prest, "regTrib"), "regApTribSN"))), 196.40f);

                    // ---- TOMADOR / ADQUIRENTE
                    g.section("TOMADOR / ADQUIRENTE", 639.62f, 620.2963f, 209.20f);
                    Element tEndExt = desc(toma, "endExt");
                    Element tEnd = el(toma, "end");
                    g.label(1, 1, "CNPJ / CPF / NIF", 208.30f);
                    g.label(2, 1, "Indicador Municipal (Inscrição)", 208.30f);
                    g.label(3, 1, "Telefone", 208.30f);
                    g.value(1, 1, fiscalId(toma), 216.00f);
                    g.value(2, 1, or(t(toma, "IM")), 216.00f);
                    g.value(3, 1, phone(t(toma, "fone")), 216.00f);
                    g.label(0, 2, "Nome / Nome Empresarial", 227.30f);
                    g.label(2, 1, "Município / Sigla UF", 227.30f);
                    g.label(3, 1, "Código IBGE / CEP", 227.30f);
                    Element tEndNac = desc(toma, "endNac");
                    g.value(2, 1, tEndExt != null ? t(tEndExt, "xCidade")
                            : municipio(t(tEndNac, "cMun")), 235.10f);
                    g.value(3, 1, tEndExt != null ? t(tEndExt, "cEndPost") + " (ext)"
                            : (tEndNac != null ? ibge(t(tEndNac, "cMun")) + " / " + cep(t(tEndNac, "CEP")) : "-"),
                            235.10f);
                    g.shift += g.wrappedValue(0, 2, or(t(toma, "xNome")), 235.10f);
                    g.label(0, 2, "Endereço", 246.40f);
                    g.label(2, 2, "E-mail", 246.40f);
                    g.value(0, 2, or(join(t(tEnd, "xLgr"), t(tEnd, "nro"), t(tEnd, "xBairro"))), 254.10f);
                    g.value(2, 2, toma == null ? "-" : or(t(toma, "email").toLowerCase()), 254.10f);

                    // ---- destinatário and intermediário: a one-line band when the note
                    // carries none, otherwise a full person block that pushes the rest down
                    Element dest = desc(desc(infDps, "IBSCBS"), "dest");
                    g.hline(CX[0], CR, 581.90f, 0.5f);
                    if (dest == null) {
                        g.text("DESTINATÁRIO DA OPERAÇÃO NÃO IDENTIFICADO NA NFS-e", g.regular, 7, 194.24f, 266.80f);
                    } else {
                        g.person("DESTINATÁRIO DA OPERAÇÃO", dest, 260.10f, false);
                        g.shift += PERSON_H - BAND_H;
                    }
                    g.hline(CX[0], CR, 573.47f, 0.5f);
                    if (interm == null) {
                        g.text("INTERMEDIÁRIO DA OPERAÇÃO NÃO IDENTIFICADO NA NFS-e", g.regular, 7, 192.30f, 275.20f);
                    } else {
                        g.person("INTERMEDIÁRIO DA OPERAÇÃO", interm, 268.50f, true);
                        g.shift += PERSON_H - BAND_H;
                    }

                    // ---- SERVIÇO PRESTADO
                    g.section("SERVIÇO PRESTADO", 565.05f, 545.7263f, 283.80f);
                    g.label(1, 1, "Código de Tributação Nacional/Municipal", 282.80f);
                    g.label(2, 1, "Código da NBS", 282.80f);
                    g.label(3, 1, "Local da Prestação / Sigla UF / País", 282.80f);
                    g.value(1, 1, tribNac(t(cServ, "cTribNac"))
                            + (t(cServ, "cTribMun").isEmpty() ? "" : " / " + t(cServ, "cTribMun")), 290.60f);
                    g.value(2, 1, or(nbs(t(cServ, "cNBS"))), 290.60f);
                    g.value(3, 1, t(inf, "xLocPrestacao") + " / " + t(ender, "UF") + " / -", 290.60f);
                    g.value(0, 4, truncate(firstNonBlank(t(inf, "xTribMun"), t(inf, "xTribNac")), TRIB_NAC_MAX_CHARS), 302.70f);
                    g.label(0, 4, "Descrição do Serviço", 314.10f);
                    // xDescServ accepts 2000 characters; clipping it to one line would
                    // silently drop most of what the note says was sold. It wraps and
                    // pushes the rest of the page down, exactly as a long name does.
                    // NOTE: the multi-line behaviour is INFERRED from the verified name
                    // wrap — no official PDF we hold carries a long description, and the
                    // portal's own PDF is captcha-gated, so this one is unverified.
                    g.shift += g.wrappedValue(0, 4, t(cServ, "xDescServ"), 321.80f);

                    // ---- TRIBUTAÇÃO MUNICIPAL (ISSQN)
                    g.section("TRIBUTAÇÃO MUNICIPAL (ISSQN)", 514.23f, 494.8963f, 334.60f);
                    g.label(1, 1, "Tipo de Tributação do ISSQN", 333.60f);
                    g.label(2, 2, "Município / Sigla UF / País de Incidência do ISSQN", 333.60f);
                    g.value(1, 1, or(ISSQN_SITUATION.get(t(tribMun, "tribISSQN"))), 341.40f);
                    g.value(2, 2, t(inf, "xLocIncid").isEmpty() ? "Nenhum"
                            : t(inf, "xLocIncid") + " / " + t(ender, "UF") + " / -", 341.40f);
                    // The ISSQN apuração comes from SEFIN's own computation in
                    // infNFSe/valores. It is absent on exports and on all-inclusive
                    // Simples notes (their ISS rides in the DAS), which is why the
                    // reference PDFs show "-" in every one of these cells.
                    Element nfseVal = el(inf, "valores");
                    g.label(0, 1, "BC ISSQN", 352.70f);
                    g.label(1, 1, "Alíquota Aplicada", 352.70f);
                    g.label(2, 1, "Retenção do ISSQN", 352.70f);
                    g.label(3, 1, "ISSQN Apurado", 352.70f);
                    g.value(0, 1, reais(t(nfseVal, "vBC")), 360.40f);
                    g.value(1, 1, percent(firstNonBlank(t(nfseVal, "pAliqAplic"), t(tribMun, "pAliq"))), 360.40f);
                    g.value(2, 1, or(RET_ISSQN.get(t(tribMun, "tpRetISSQN"))), 360.40f);
                    g.value(3, 1, reais(t(nfseVal, "vISSQN")), 360.40f);

                    // ---- TRIBUTAÇÃO FEDERAL (EXCETO CBS)
                    g.section("TRIBUTAÇÃO FEDERAL (EXCETO CBS)", 475.58f, 456.2578f, 373.20f);
                    // Federal amounts come from the DPS the note embeds (SEFIN does not
                    // recompute them), so they are absent on every note that declares none.
                    Element tribFed = desc(infDps, "tribFed");
                    Element pisCofins = desc(tribFed, "piscofins");
                    g.label(1, 1, "IRRF", 372.30f);
                    g.label(2, 1, "Contribuição Previdenciária - Retida", 372.30f);
                    g.label(3, 1, "Contribuições Sociais - Retidas", 372.30f);
                    g.value(1, 1, reais(t(tribFed, "vRetIRRF")), 380.00f);
                    g.value(2, 1, reais(t(tribFed, "vRetCP")), 380.00f);
                    g.value(3, 1, reais(t(tribFed, "vRetCSLL")), 380.00f);
                    g.label(0, 1, "PIS - Débito Apuração Própria", 391.40f);
                    g.label(1, 1, "COFINS - Débito Apuração Própria", 391.40f);
                    g.label(2, 2, "Descrição Contrib. Sociais - Retidas", 391.40f);
                    g.value(0, 1, reais(t(pisCofins, "vPis")), 399.10f);
                    g.value(1, 1, reais(t(pisCofins, "vCofins")), 399.10f);
                    g.value(2, 2, "-", 399.10f);

                    // ---- TRIBUTAÇÃO IBS/CBS
                    g.section("TRIBUTAÇÃO IBS/CBS", 436.93f, 417.6093f, 411.90f);
                    g.label(1, 1, "CST / cClassTrib", 410.90f);
                    g.label(2, 2, "Indicador de Operação / Código IBGE Incidência / Município Incidência / Sigla UF", 410.90f);
                    g.value(1, 1, rtc == null ? "- / -"
                            : or(t(gIbsCbs, "CST")) + " / " + or(t(gIbsCbs, "cClassTrib")), 418.70f);
                    g.value(2, 2, rtc == null ? "- / - / - / -"
                            : or(t(rtcDps, "cIndOp")) + " / " + or(t(rtc, "cLocalidadeIncid"))
                              + " / " + or(t(rtc, "xLocalidadeIncid")) + " / " + uf(t(rtc, "cLocalidadeIncid")),
                            418.70f);
                    g.label(0, 1, "Exclusões e Reduções da Base de Cálculo", 430.00f);
                    g.label(1, 1, "Base de Cálculo Após Exclusões e Reduções", 430.00f);
                    g.label(2, 1, "Red. Alíquota IBS / Red. Alíquota CBS", 430.00f);
                    g.label(3, 1, "Alíquota - IBS UF / IBS Mun", 430.00f);
                    g.value(0, 1, "R$ 0,00", 437.80f);
                    g.value(1, 1, rtc == null ? "-" : reais(t(rtcVal, "vBC")), 437.80f);
                    g.value(2, 1, "- / - / -", 437.80f);
                    g.value(3, 1, rtc == null ? "- / -"
                            : percent(t(rtcUf, "pIBSUF")) + " / " + percent(t(rtcMun, "pIBSMun")), 437.80f);
                    g.label(0, 1, "Alíq. Efetiva Municipal - IBS", 449.10f);
                    g.label(1, 1, "Valor Apurado Municipal - IBS", 449.10f);
                    g.label(2, 1, "Alíq. Efetiva Estadual - IBS", 449.10f);
                    g.label(3, 1, "Valor Apurado Estadual - IBS", 449.10f);
                    g.value(0, 1, rtc == null ? "-" : percent(t(rtcMun, "pAliqEfetMun")), 456.80f);
                    g.value(1, 1, rtc == null ? "-" : reais(t(desc(rtcTot, "gIBSMunTot"), "vIBSMun")), 456.80f);
                    g.value(2, 1, rtc == null ? "-" : percent(t(rtcUf, "pAliqEfetUF")), 456.80f);
                    g.value(3, 1, rtc == null ? "-" : reais(t(desc(rtcTot, "gIBSUFTot"), "vIBSUF")), 456.80f);
                    g.label(0, 1, "Valor Total Apurado - IBS", 468.20f);
                    g.label(1, 1, "Alíquota - CBS", 468.20f);
                    g.label(2, 1, "Alíquota Efetiva - CBS", 468.20f);
                    g.label(3, 1, "Valor Total Apurado - CBS", 468.20f);
                    g.value(0, 1, rtc == null ? "-" : reais(t(desc(rtcTot, "gIBS"), "vIBSTot")), 475.90f);
                    g.value(1, 1, rtc == null ? "-" : percent(t(rtcFed, "pCBS")), 475.90f);
                    g.value(2, 1, rtc == null ? "-" : percent(t(rtcFed, "pAliqEfetCBS")), 475.90f);
                    g.value(3, 1, rtc == null ? "-" : reais(t(desc(rtcTot, "gCBS"), "vCBS")), 475.90f);

                    // ---- VALOR TOTAL DA NFS-e
                    String vServ = t(desc(infDps, "vServPrest"), "vServ");
                    String vLiq = t(nfseVal, "vLiq");
                    g.section("VALOR TOTAL DA NFS-e", 360.13f, 340.8148f, 488.70f);
                    g.label(1, 1, "VALOR DA OPERAÇÃO / SERVIÇO", 487.70f);
                    g.label(2, 1, "Desconto Incondicionado", 487.70f);
                    g.label(3, 1, "Desconto Condicionado", 487.70f);
                    Element descontos = desc(infDps, "vDescCondIncond");
                    g.value(1, 1, reais(vServ), 495.50f);
                    g.value(2, 1, reais(t(descontos, "vDescIncond")), 495.50f);
                    g.value(3, 1, reais(t(descontos, "vDescCond")), 495.50f);
                    g.fill(CX[3], 321.7406f, 144.5669f, SECTION_H); // gray VALOR LÍQUIDO + IBS/CBS cell
                    g.label(0, 1, "Total das Retenções (ISSQN / Federais)", 506.80f);
                    g.label(1, 1, "VALOR LÍQUIDO DA NFS-e", 506.80f);
                    g.label(2, 1, "Total do IBS/CBS", 506.80f);
                    g.label(3, 1, "VALOR LÍQUIDO DA NFS-e + IBS/CBS", 506.80f);
                    g.value(0, 1, reais(t(nfseVal, "vTotalRet")), 514.50f);
                    g.value(1, 1, reais(vLiq), 514.50f);
                    g.value(2, 1, rtc == null ? "R$ 0,00" : reais(totalIbsCbs(rtcTot)), 514.50f);
                    g.value(3, 1, rtc == null ? "R$ 0,00" : reais(vLiq), 514.50f);

                    // ---- INFORMAÇÕES COMPLEMENTARES
                    g.hline(CX[0], CR, 321.49f, 0.5f);
                    g.text("INFORMAÇÕES COMPLEMENTARES", g.bold, 7, TX[0], 527.30f);
                    String infoCompl = t(desc(infDps, "infoCompl"), "xInfComp");
                    if (!infoCompl.isEmpty()) {
                        g.value(0, 4, infoCompl, 537.90f);
                    }
                    g.value(0, 4, "Totais aproximados dos Tributos cfe. Lei n° 12.741/2012: "
                            + "Federais: " + orMoney(t(totTrib, "vTotTribFed"))
                            + "; Estaduais: " + orMoney(t(totTrib, "vTotTribEst"))
                            + "; Municipais: " + orMoney(t(totTrib, "vTotTribMun")) + ";", 547.40f);

                    // ---- footer: IDENTIFICAÇÃO E ASSINATURA box, anchored to the
                    // page bottom — it never moves with the content above it
                    g.shift = 0;
                    for (float x : new float[]{9.0039f, 153.5709f, 298.1378f, 587.2717f}) {
                        g.vline(x, 25.622f, 45.6962f, 1);
                    }
                    g.hline(8.5039f, 587.7717f, 26.122f, 1);
                    g.hline(8.5039f, 587.7717f, 46.1962f, 1);
                    g.text("DATA CIENTIFICAÇÃO:", g.bold, 6, TX[0] + 1, 801.90f);
                    g.text("IDENTIFICAÇÃO E ASSINATURA", g.bold, 6, TX[1] + 1, 801.90f);
                    g.text("N° NFS-e / CHAVE NFS-e", g.bold, 6, TX[2] + 1, 801.90f);
                    g.text(t(inf, "nNFSe") + " / " + chave, g.regular, 7, TX[2] + 1, 809.70f);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.save(out);
                return out.toByteArray();
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to render DANFSe: " + e.getMessage(), e);
        }
    }

    /**
     * Draws with the reference template's conventions: text is placed by
     * baseline in top-down coordinates; rules and fills use the exact
     * bottom-up values read from the reference content stream.
     */
    private static final class G {
        private final PDPageContentStream cs;
        private final PDFont bold;
        private final PDFont regular;
        /**
         * How far everything below the destinatário/intermediário rows has moved
         * down, in points, because one of them is identified and needs a full
         * block instead of a one-line band. Zero for the common note, which is
         * what keeps the reference geometry exact.
         */
        private float shift;

        private G(PDPageContentStream cs, PDFont bold, PDFont regular) {
            this.cs = cs;
            this.bold = bold;
            this.regular = regular;
        }

        /**
         * A full person block (the TOMADOR layout, reused): section title cell
         * plus the three label/value rows, positioned by its top edge in
         * top-down coordinates.
         */
        void person(String title, Element p, float topDown, boolean withMunicipalRegistration) throws Exception {
            Element endExt = desc(p, "endExt");
            Element end = el(p, "end");
            Element endNac = desc(p, "endNac");
            // fill() applies the running shift itself, so pass the unshifted origin
            fill(CX[0], H - topDown - 0.25f - SECTION_H, 144.5669f, SECTION_H);
            text(title, bold, 7, TX[0], topDown + 6.82f);
            label(1, 1, "CNPJ / CPF / NIF", topDown + 5.92f);
            if (withMunicipalRegistration) {
                label(2, 1, "Indicador Municipal (Inscrição)", topDown + 5.92f);
            }
            label(3, 1, "Telefone", topDown + 5.92f);
            value(1, 1, fiscalId(p), topDown + 13.62f);
            if (withMunicipalRegistration) {
                value(2, 1, or(t(p, "IM")), topDown + 13.62f);
            }
            value(3, 1, phone(t(p, "fone")), topDown + 13.62f);
            label(0, 2, "Nome / Nome Empresarial", topDown + 25.00f);
            label(2, 1, "Município / Sigla UF", topDown + 25.00f);
            label(3, 1, "Código IBGE / CEP", topDown + 25.00f);
            value(2, 1, endExt != null ? t(endExt, "xCidade") : municipio(t(endNac, "cMun")), topDown + 32.72f);
            value(3, 1, endExt != null ? t(endExt, "cEndPost") + " (ext)"
                    : (endNac != null ? ibge(t(endNac, "cMun")) + " / " + cep(t(endNac, "CEP")) : "-"),
                    topDown + 32.72f);
            shift += wrappedValue(0, 2, or(t(p, "xNome")), topDown + 32.72f);
            label(0, 2, "Endereço", topDown + 44.10f);
            label(2, 2, "E-mail", topDown + 44.10f);
            value(0, 2, or(join(t(end, "xLgr"), t(end, "nro"), t(end, "xBairro"))), topDown + 51.80f);
            value(2, 2, or(t(p, "email").toLowerCase()), topDown + 51.80f);
        }

        /** Section = hairline across the page + gray title cell in column 1. */
        void section(String title, float lineYBottomUp, float cellYBottomUp, float titleBaseline) throws Exception {
            hline(CX[0], CR, lineYBottomUp, 0.5f);
            fill(CX[0], cellYBottomUp, 144.5669f, SECTION_H);
            text(title, bold, 7, TX[0], titleBaseline);
        }

        void label(int col, int span, String s, float baseline) throws Exception {
            text(clip(s, bold, 6, width(col, span)), bold, 6, TX[col], baseline);
        }

        /** The rows above PRESTADOR use 7pt labels. */
        void label7(int col, int span, String s, float baseline) throws Exception {
            text(clip(s, bold, 7, width(col, span)), bold, 7, TX[col], baseline);
        }

        void value(int col, int span, String s, float baseline) throws Exception {
            text(clip(or(s), regular, 7, width(col, span)), regular, 7, TX[col], baseline);
        }

        /**
         * A value cell that word-wraps instead of clipping (the portal does this
         * for names). Draws each line {@link #NAME_LINE_H}pt apart and returns
         * how far the content below it has to move down.
         */
        float wrappedValue(int col, int span, String s, float baseline) throws Exception {
            List<String> lines = wrap(encodable(sanitize(or(s)), regular), regular, 7, width(col, span));
            for (int i = 0; i < lines.size(); i++) {
                text(lines.get(i), regular, 7, TX[col], baseline + i * NAME_LINE_H);
            }
            return (lines.size() - 1) * NAME_LINE_H;
        }

        void center(String s, PDFont font, float size, float baseline) throws Exception {
            text(s, font, size, (W - font.getStringWidth(s) / 1000 * size) / 2, baseline);
        }

        void text(String s, PDFont font, float size, float x, float baseline) throws Exception {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, H - baseline - shift);
            cs.showText(encodable(sanitize(s), font));
            cs.endText();
        }

        void hline(float x1, float x2, float yBottomUp, float lw) throws Exception {
            cs.setLineWidth(lw);
            cs.moveTo(x1, yBottomUp - shift);
            cs.lineTo(x2, yBottomUp - shift);
            cs.stroke();
        }

        void vline(float x, float y1BottomUp, float y2BottomUp, float lw) throws Exception {
            cs.setLineWidth(lw);
            cs.moveTo(x, y1BottomUp);
            cs.lineTo(x, y2BottomUp);
            cs.stroke();
        }

        void rectStroke(float x, float yBottomUp, float w, float h, float lw) throws Exception {
            cs.setLineWidth(lw);
            cs.addRect(x, yBottomUp, w, h);
            cs.stroke();
        }

        void fill(float x, float yBottomUp, float w, float h) throws Exception {
            cs.setNonStrokingColor(GRAY);
            cs.addRect(x, yBottomUp - shift, w, h);
            cs.fill();
            cs.setNonStrokingColor(Color.BLACK);
        }

        private float width(int col, int span) {
            return (col + span < CX.length ? CX[col + span] : CR) - TX[col] - 3.4016f;
        }

        /**
         * The portal truncates at the character that no longer fits and appends
         * "..." directly — no word-boundary snapping, and no space inserted (any
         * space you see before the dots is the original text's own).
         */
        private String clip(String s, PDFont font, float size, float maxWidth) throws Exception {
            String v = encodable(sanitize(s), font);
            if (font.getStringWidth(v) / 1000 * size <= maxWidth) {
                return v;
            }
            while (!v.isEmpty() && font.getStringWidth(v + "...") / 1000 * size > maxWidth) {
                v = v.substring(0, v.length() - 1);
            }
            return v + "...";
        }
    }

    // ---------- fonts ----------

    private static final List<String> BOLD_CANDIDATES = List.of(
            "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
            "C:/Windows/Fonts/arialbd.ttf",
            "/usr/share/fonts/truetype/msttcorefonts/Arial_Bold.ttf");
    private static final List<String> REGULAR_CANDIDATES = List.of(
            "/System/Library/Fonts/Supplemental/Microsoft Sans Serif.ttf",
            "C:/Windows/Fonts/micross.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "C:/Windows/Fonts/arial.ttf",
            "/usr/share/fonts/truetype/msttcorefonts/Arial.ttf");

    /**
     * The official DANFSe uses Arial Bold + Microsoft Sans Serif. Embed them from
     * the host when present; otherwise embed the vendored metric-compatible
     * Liberation Sans; a non-embedded Standard-14 font is the very last resort.
     */
    private static PDFont loadFont(PDDocument doc, List<String> systemCandidates,
                                   String vendored, Standard14Fonts.FontName lastResort) {
        for (String path : systemCandidates) {
            File f = new File(path);
            if (f.isFile()) {
                try {
                    return PDType0Font.load(doc, f);
                } catch (Exception ignored) {
                    // try the next candidate
                }
            }
        }
        try (var in = DanfseGenerator.class.getResourceAsStream(vendored)) {
            if (in != null) {
                return PDType0Font.load(doc, in, true);
            }
        } catch (Exception ignored) {
            // fall through to the Standard-14 font
        }
        return new PDType1Font(lastResort);
    }

    // ---------- images ----------

    private void drawLogo(PDDocument doc, PDPageContentStream cs) throws Exception {
        try (var in = getClass().getResourceAsStream("/danfse/nfse-logo.png")) {
            if (in == null) {
                return;
            }
            PDImageXObject logo = PDImageXObject.createFromByteArray(doc, in.readAllBytes(), "nfse-logo");
            cs.drawImage(logo, 11.9055f, 808.7606f, 115.6536f, 22.9165f);
        }
    }

    /**
     * 45x45pt at the reference position. EC level M, a 2-module quiet zone and
     * a 10px/module raster image match the portal's QR bit-for-bit (verified
     * against the reference PDF's embedded 450x450 image).
     */
    private void drawQr(PDDocument doc, PDPageContentStream cs, String url) throws Exception {
        QRCode code = Encoder.encode(url, ErrorCorrectionLevel.M, null);
        var m = code.getMatrix();
        int scale = 10, quiet = 2;
        int px = (m.getWidth() + 2 * quiet) * scale;
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        Graphics2D gfx = img.createGraphics();
        gfx.setColor(Color.WHITE);
        gfx.fillRect(0, 0, px, px);
        gfx.setColor(Color.BLACK);
        for (int i = 0; i < m.getWidth(); i++) {
            for (int j = 0; j < m.getHeight(); j++) {
                if (m.get(i, j) == 1) {
                    gfx.fillRect((i + quiet) * scale, (j + quiet) * scale, scale, scale);
                }
            }
        }
        gfx.dispose();
        cs.drawImage(LosslessFactory.createFromImage(doc, img), 491.9882f, 752.2358f, 45, 45);
    }

    // ---------- formatting ----------

    /**
     * Two cells are truncated by character count rather than by width — the
     * portal cuts them at a fixed budget (ellipsis included) regardless of how
     * much room is left. Both numbers are read off official PDFs.
     */
    private static final int SIMPLES_MAX_CHARS = 40;
    private static final int TRIB_NAC_MAX_CHARS = 170;

    private static String truncate(String s, int maxChars) {
        return s == null || s.length() <= maxChars ? s : s.substring(0, maxChars - 3) + "...";
    }

    /**
     * cMun -> "Município / UF". The NFS-e XML carries only the IBGE code, but
     * the DANFSe prints the name and UF, so the official table is vendored
     * (src/main/resources/danfse/municipios-ibge.csv, generated from the IBGE
     * localidades API).
     */
    private static final Map<String, String> MUNICIPIOS = loadMunicipios();

    private static Map<String, String> loadMunicipios() {
        Map<String, String> map = new java.util.HashMap<>(6000);
        try (var in = DanfseGenerator.class.getResourceAsStream("/danfse/municipios-ibge.csv")) {
            if (in == null) {
                return map;
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] parts = line.split(";");
                if (parts.length == 3) {
                    map.put(parts[0], parts[1] + " / " + parts[2]);
                }
            }
        } catch (Exception e) {
            // a missing table only costs the municipality name, never the document
        }
        return map;
    }

    /** "Município / UF" for an IBGE code, or "-" when it is unknown. */
    private static String municipio(String cMun) {
        return or(MUNICIPIOS.get(cMun == null ? "" : cMun.trim()));
    }

    /** The UF alone, for the cells that print it separately. */
    private static String uf(String cMun) {
        String v = MUNICIPIOS.get(cMun == null ? "" : cMun.trim());
        return v == null ? "-" : v.substring(v.lastIndexOf('/') + 2);
    }

    /** Percentages print as "0,10 %" on the official layout. */
    private static String percent(String v) {
        return v == null || v.isBlank() ? "-" : money(v) + " %";
    }

    /** Greedy word wrap; a single word longer than the cell is left to overflow its line. */
    private static List<String> wrap(String text, PDFont font, float size, float maxWidth) throws Exception {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (font.getStringWidth(candidate) / 1000 * size > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        lines.add(current.toString());
        return lines;
    }

    private static String or(String v) {
        return v == null || v.isBlank() ? "-" : v;
    }

    /**
     * What the CNPJ / CPF / NIF cell shows: the formatted national number, the
     * foreign NIF, or — when the taker has neither — the {@code cNaoNIF} code
     * saying why (official DANFSe prints the bare code there).
     */
    private static String fiscalId(Element person) {
        if (person == null) {
            return "-";
        }
        String national = cnpj(t(person, "CNPJ") + t(person, "CPF"));
        return or(firstNonBlank(national, firstNonBlank(t(person, "NIF"), t(person, "cNaoNIF"))));
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                sb.append(sb.isEmpty() ? "" : ", ").append(p);
            }
        }
        return sb.toString();
    }

    private static String date(String iso) {
        return iso.length() >= 10
                ? iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4)
                : iso;
    }

    /**
     * Timestamps print in horário de Brasília. SEFIN already answers with -03:00,
     * so this is a no-op on its documents, but a note whose timestamp carries any
     * other offset is converted rather than read at face value.
     */
    private static String dateTime(String iso) {
        String local = BrasiliaTime.toBrasilia(iso);
        return local.length() >= 19 ? date(local) + " " + local.substring(11, 19) : date(local);
    }

    private static String cnpj(String d) {
        if (d.length() == 14) {
            return d.replaceFirst("(\\d{2})(\\d{3})(\\d{3})(\\d{4})(\\d{2})", "$1.$2.$3/$4-$5");
        }
        if (d.length() == 11) {
            return d.replaceFirst("(\\d{3})(\\d{3})(\\d{3})(\\d{2})", "$1.$2.$3-$4");
        }
        return d;
    }

    private static String phone(String d) {
        if (d.length() == 11) {
            return "(" + d.substring(0, 2) + ") " + d.substring(2, 7) + "-" + d.substring(7);
        }
        if (d.length() == 10) {
            return "(" + d.substring(0, 2) + ") " + d.substring(2, 6) + "-" + d.substring(6);
        }
        return or(d);
    }

    private static String ibge(String d) {
        return d.length() == 7 ? d.substring(0, 2) + "." + d.substring(2) : d;
    }

    private static String cep(String d) {
        return d.length() == 8 ? d.substring(0, 2) + "." + d.substring(2, 5) + "-" + d.substring(5) : d;
    }

    private static String tribNac(String d) {
        return d.length() == 6 ? d.substring(0, 2) + "." + d.substring(2, 4) + "." + d.substring(4) : d;
    }

    private static String nbs(String d) {
        return d.length() == 9
                ? d.charAt(0) + "." + d.substring(1, 5) + "." + d.substring(5, 7) + "." + d.substring(7)
                : d;
    }

    private static String pct(String v) {
        return v == null || v.isBlank() ? "" : money(v) + "%";
    }

    /** "Total do IBS/CBS" = the apurado IBS plus the apurado CBS. */
    private static String totalIbsCbs(Element totCIBS) {
        String ibs = t(desc(totCIBS, "gIBS"), "vIBSTot");
        String cbs = t(desc(totCIBS, "gCBS"), "vCBS");
        if (ibs.isBlank() && cbs.isBlank()) {
            return "";
        }
        return new BigDecimal(ibs.isBlank() ? "0" : ibs)
                .add(new BigDecimal(cbs.isBlank() ? "0" : cbs)).toPlainString();
    }

    private static String reais(String v) {
        return v == null || v.isBlank() ? "-" : "R$ " + money(v);
    }

    private static String orMoney(String v) {
        return v == null || v.isBlank() ? "-" : "R$ " + money(v);
    }

    private static String money(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        DecimalFormat fmt = new DecimalFormat("#,##0.00",
                DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        return fmt.format(new BigDecimal(value));
    }

    private static String sanitize(String s) {
        return (s == null ? "" : s).replaceAll("[\\r\\n\\t]+", " ");
    }

    /** Only the Standard-14 last-resort fonts are limited to WinAnsi. */
    private static String encodable(String s, PDFont font) {
        if (!(font instanceof PDType1Font)) {
            return s;
        }
        String normalized = s.replace('–', '-').replace('—', '-')
                .replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('º', '°');
        StringBuilder out = new StringBuilder(normalized.length());
        for (char ch : normalized.toCharArray()) {
            out.append(ch <= 0xFF ? ch : '?');
        }
        return out.toString();
    }

    // ---------- XML helpers ----------

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = SafeXml.documentBuilderFactory();
        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Element el(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && NS.equals(e.getNamespaceURI()) && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static Element desc(Element scope, String localName) {
        if (scope == null) {
            return null;
        }
        var list = scope.getElementsByTagNameNS(NS, localName);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static String t(Element scope, String localName) {
        return Optional.ofNullable(el(scope, localName))
                .map(Element::getTextContent).map(String::trim).orElse("");
    }
}
