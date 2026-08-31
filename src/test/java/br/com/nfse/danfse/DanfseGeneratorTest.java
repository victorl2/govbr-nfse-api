package br.com.nfse.danfse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DanfseGeneratorTest {

    private final DanfseGenerator generator = new DanfseGenerator();

    private static String sampleNfse() throws Exception {
        try (InputStream in = DanfseGeneratorTest.class.getResourceAsStream("/dps/nfse-export-sample.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void rendersAPdfWithTheNoteEssentials() throws Exception {
        byte[] pdf = generator.generate(sampleNfse());
        assertTrue(pdf.length > 1000 && pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F',
                "output must be a PDF");
        String text = pdfText(pdf);
        assertTrue(text.contains("Documento Auxiliar da NFS-e"), text);
        assertTrue(text.contains("33045572212345678000195000000000000126081270635662"), "chave de acesso");
        assertTrue(text.contains("EMPRESA DE EXEMPLO LTDA"), "emitter name");
        assertTrue(text.contains("ACME SOFTWARE CLIENT LLC"), "tomador");
        assertTrue(text.contains("10.000,00"), "value formatted pt-BR");
        assertTrue(text.contains("Exporta"), "ISSQN situation (exportação)");
    }

    /** The official layout is "DANFSe v2.0" — a boxed grid; these anchors pin its structure. */
    @Test
    void followsTheOfficialV2Layout() throws Exception {
        String text = pdfText(generator.generate(sampleNfse()));
        assertTrue(text.contains("DANFSe v2.0"), text);
        assertTrue(text.contains("CHAVE DE ACESSO DA NFS-e"), "chave section");
        assertTrue(text.contains("PRESTADOR / FORNECEDOR"), "prestador section");
        assertTrue(text.contains("TOMADOR / ADQUIRENTE"), "tomador section");
        assertTrue(text.contains("SERVIÇO PRESTADO"), "serviço section");
        assertTrue(text.contains("TRIBUTAÇÃO MUNICIPAL (ISSQN)"), "tributação municipal");
        assertTrue(text.contains("TRIBUTAÇÃO IBS/CBS"), "tributação IBS/CBS");
        assertTrue(text.contains("VALOR TOTAL DA NFS-e"), "valor total");
        assertTrue(text.contains("VALOR LÍQUIDO DA NFS-e"), "valor líquido");
        assertTrue(text.contains("INFORMAÇÕES COMPLEMENTARES"), "informações complementares");
        assertTrue(text.contains("Ambiente Gerador: 2"), "header environment fields");
        assertTrue(text.contains("12.345.678/0001-95"), "formatted CNPJ");
        assertTrue(text.contains("01.04.01 / 003"), "formatted cTribNac / cTribMun");
        assertTrue(text.contains("1.1502.20.00"), "formatted NBS");
        assertTrue(text.contains("IDENTIFICAÇÃO E ASSINATURA"), "footer");
    }

    /**
     * The official DANFSe embeds its fonts (Arial Bold + Microsoft Sans Serif subsets), which is
     * what makes it render identically in every viewer. Ours must do the same — Standard-14
     * fallback fonts are substituted by the viewer and change the page's appearance.
     */
    @Test
    void embedsItsFontsAndUsesTheOfficialPageGeometry() throws Exception {
        byte[] pdf = generator.generate(sampleNfse());
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            var page = doc.getPage(0);
            assertTrue(Math.abs(page.getMediaBox().getWidth() - 595) < 0.01
                            && Math.abs(page.getMediaBox().getHeight() - 842) < 0.01,
                    "page must be exactly 595x842pt like the official PDF, was "
                            + page.getMediaBox().getWidth() + "x" + page.getMediaBox().getHeight());
            var resources = page.getResources();
            int fonts = 0;
            for (var name : resources.getFontNames()) {
                var font = resources.getFont(name);
                fonts++;
                assertTrue(font.isEmbedded(), "font " + font.getName() + " must be embedded");
            }
            assertTrue(fonts >= 2, "expected at least a bold and a regular font, found " + fonts);
        }
    }

    private static String intermNfse() throws Exception {
        try (InputStream in = DanfseGeneratorTest.class.getResourceAsStream("/dps/nfse-interm-sample.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The "NÃO IDENTIFICADO" bands are only correct when the note really carries
     * no intermediário: when {@code interm} is present the DANFSe must show it.
     * Section title and geometry below are transcribed from the official PDF of
     * a live restrita note that carries one (chave …326086825159457).
     */
    @Test
    void identifiedIntermediarioIsShownInsteadOfTheNotIdentifiedBand() throws Exception {
        String text = pdfText(generator.generate(intermNfse()));
        assertTrue(text.contains("INTERMEDIÁRIO DA OPERAÇÃO"), "intermediário section: " + text);
        assertTrue(text.contains("AGENCIA INTERMEDIA LTDA"), "intermediário name: " + text);
        assertTrue(text.contains("11.222.333/0001-81"), "intermediário CNPJ formatted: " + text);
        assertFalse(text.contains("INTERMEDIÁRIO DA OPERAÇÃO NÃO IDENTIFICADO"),
                "must not claim there is no intermediário: " + text);
        // the destinatário band is unaffected and keeps its place
        assertTrue(text.contains("DESTINATÁRIO DA OPERAÇÃO NÃO IDENTIFICADO NA NFS-e"), text);
    }

    /**
     * SEFIN fills {@code infNFSe/emit} with the CNPJ registry's phone and e-mail,
     * but the official DANFSe shows the ones the emitter declared in the DPS
     * ({@code prest}) — a note whose DPS omits them prints "-" even though the
     * registry values are right there in the XML (proven on nota …159457).
     */
    @Test
    void prestadorContactComesFromTheDpsNotFromTheRegistry() throws Exception {
        String text = pdfText(generator.generate(sampleNfse()));
        assertTrue(text.contains("(21) 98888-7777"), "phone declared in the DPS: " + text);
        assertTrue(text.contains("contato@exemplo.com.br"), "e-mail declared in the DPS: " + text);
        assertFalse(text.contains("3333-4444"), "registry phone must not be used: " + text);
        assertFalse(text.contains("registro@exemplo.com.br"), "registry e-mail must not be used: " + text);
    }

    /**
     * A foreign tomador without a NIF shows its {@code cNaoNIF} code in the
     * CNPJ/CPF/NIF cell (official nota …159457 prints "1" there, not "-").
     * Checked at the cell's own coordinates so a match elsewhere can't pass it.
     */
    @Test
    void tomadorWithoutNifShowsTheCNaoNifCode() throws Exception {
        assertEquals("1", textAt(sampleNfse(), 156.47f, 216.00f), "tomador CNPJ / CPF / NIF cell");
    }

    /**
     * The portal clips two cells by character budget rather than by width, and
     * counts the ellipsis inside the budget: the Simples line stops at 40
     * characters and the national-code description at 170 (both read off
     * official PDFs).
     */
    @Test
    void theTwoCharacterBudgetedCellsClipExactlyLikeThePortal() throws Exception {
        String simples = textAt(sampleNfse(), 11.91f, 196.40f);
        assertEquals(40, simples.length(), "Simples cell budget: " + simples);
        assertEquals("Optante - Microempresa ou Empresa de ...", simples);

        String tribNac = textAt(sampleNfse(), 11.91f, 302.70f);
        assertTrue(tribNac.length() <= 170, "xTribNac budget: " + tribNac.length());
    }

    private static String destIntermNfse() throws Exception {
        try (InputStream in = DanfseGeneratorTest.class.getResourceAsStream("/dps/nfse-dest-interm-sample.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * An identified destinatário renders its own block, and — unlike every other
     * person section — it carries no "Indicador Municipal (Inscrição)" column
     * (verified against the official PDF of nota …426081835915591).
     */
    @Test
    void identifiedDestinatarioRendersWithoutTheMunicipalRegistrationColumn() throws Exception {
        String text = pdfText(generator.generate(destIntermNfse()));
        assertTrue(text.contains("DESTINATÁRIO DA OPERAÇÃO"), "destinatário section: " + text);
        assertTrue(text.contains("CLIENTE DESTINATARIO EXEMPLO LTDA"), "destinatário name: " + text);
        assertTrue(text.contains("11.444.777/0001-61"), "destinatário CNPJ: " + text);
        assertFalse(text.contains("DESTINATÁRIO DA OPERAÇÃO NÃO IDENTIFICADO"), text);
        // its first row has CNPJ and Telefone only — no Indicador Municipal cell
        assertEquals("", textAt(destIntermNfse(), 301.04f, 273.90f), "destinatário must have no IM label");
    }

    /** finNFSe=0 in the IBS/CBS group prints "NFS-e regular" in FINALIDADE. */
    @Test
    void finalidadeComesFromTheIbsCbsGroup() throws Exception {
        assertEquals("NFS-e regular", textAt(destIntermNfse(), 301.04f, 119.62f));
        assertEquals("-", textAt(sampleNfse(), 301.04f, 119.62f), "no IBS/CBS group means no finalidade");
    }

    /**
     * A name too long for its cell wraps onto a second line 7.92pt below rather
     * than being clipped, and pushes the rest of that block (and everything
     * after it) down — measured on the official PDF.
     */
    @Test
    void longNamesWrapOntoASecondLineAndPushTheBlockDown() throws Exception {
        String xml = destIntermNfse();
        assertEquals("COOPERATIVA DE DESENVOLVIMENTO E EDUCACAO TECNOLOGICA DO VALE",
                textAt(xml, 11.91f, 235.10f), "first line of the wrapped name");
        assertEquals("EXEMPLO LTDA", textAt(xml, 11.91f, 243.02f), "second line of the wrapped name");
        // the address row below it moved down by one line (246.40 -> 254.33)
        assertEquals("", textAt(xml, 11.91f, 246.40f), "nothing is left at the unshifted address row");
        assertEquals("Endereço", textAt(xml, 11.91f, 254.33f), "address label moved down one line");
    }

    /**
     * Every note we have emitted so far is an export or an all-inclusive Simples
     * note, where SEFIN returns no ISSQN apuração at all — hence "-" in those cells,
     * which is what the three reference PDFs show. A note whose ISSQN is actually due
     * carries the apuração in {@code infNFSe/valores}, and the DANFSe must print it.
     */
    @Test
    void aNoteWithIssqnDueRendersTheApuracaoInsteadOfDashes() throws Exception {
        String text = pdfText(generator.generate(issqnDevidoNfse()));
        assertTrue(text.contains("Operação Tributável"), "ISSQN situation: " + text);
        assertEquals("R$ 10.000,00", textAt(issqnDevidoNfse(), 11.91f, 360.40f), "BC ISSQN");
        assertEquals("2,00 %", textAt(issqnDevidoNfse(), 156.47f, 360.40f), "alíquota aplicada");
        assertEquals("R$ 200,00", textAt(issqnDevidoNfse(), 445.61f, 360.40f), "ISSQN apurado");
        assertEquals("R$ 200,00", textAt(issqnDevidoNfse(), 11.91f, 514.50f), "total das retenções");
        assertEquals("R$ 9.800,00", textAt(issqnDevidoNfse(), 156.47f, 514.50f), "valor líquido");
    }

    /** The reference notes carry no apuração, so those cells must stay exactly "-". */
    @Test
    void aNoteWithoutIssqnApuracaoStillPrintsDashes() throws Exception {
        assertEquals("-", textAt(sampleNfse(), 11.91f, 360.40f), "BC ISSQN");
        assertEquals("-", textAt(sampleNfse(), 445.61f, 360.40f), "ISSQN apurado");
        assertEquals("-", textAt(sampleNfse(), 11.91f, 514.50f), "total das retenções");
    }

    /**
     * Discounts and federal taxes were hardcoded "-" because no note we had ever
     * carried them. They come from the DPS the note embeds, not from SEFIN's
     * computed block.
     */
    @Test
    void discountsAndFederalTaxesAreRenderedWhenTheNoteCarriesThem() throws Exception {
        String xml = completeNfse();
        assertEquals("R$ 500,00", textAt(xml, 301.04f, 495.50f), "desconto incondicionado");
        assertEquals("R$ 250,00", textAt(xml, 445.61f, 495.50f), "desconto condicionado");
        assertEquals("R$ 150,00", textAt(xml, 156.47f, 380.00f), "IRRF");
        assertEquals("R$ 1.100,00", textAt(xml, 301.04f, 380.00f), "contribuição previdenciária retida");
        assertEquals("R$ 100,00", textAt(xml, 445.61f, 380.00f), "contribuições sociais retidas");
        assertEquals("R$ 65,00", textAt(xml, 11.91f, 399.10f), "PIS");
        assertEquals("R$ 300,00", textAt(xml, 156.47f, 399.10f), "COFINS");
    }

    /** A note without them keeps the dashes the reference PDFs show. */
    @Test
    void aNoteWithoutDiscountsOrFederalTaxesStillPrintsDashes() throws Exception {
        String xml = sampleNfse();
        assertEquals("-", textAt(xml, 301.04f, 495.50f), "desconto incondicionado");
        assertEquals("-", textAt(xml, 156.47f, 380.00f), "IRRF");
        assertEquals("-", textAt(xml, 11.91f, 399.10f), "PIS");
    }

    /**
     * xDescServ accepts 2000 characters. Rendering it as one clipped line silently
     * drops most of the service description, which is a compliance problem rather
     * than a cosmetic one — so it wraps, and pushes what follows down, using the
     * same line mechanics measured for wrapped names.
     */
    @Test
    void aLongServiceDescriptionWrapsInsteadOfBeingSilentlyTruncated() throws Exception {
        String longText = "Desenvolvimento de software sob encomenda abrangendo levantamento de "
                + "requisitos, arquitetura da solucao, implementacao dos modulos de integracao "
                + "fiscal, testes automatizados, documentacao tecnica e acompanhamento da "
                + "implantacao em ambiente produtivo do cliente conforme contrato";
        String xml = sampleNfse().replaceAll("<xDescServ>[^<]*</xDescServ>",
                "<xDescServ>" + longText + "</xDescServ>");

        String text = pdfText(generator.generate(xml));
        assertTrue(text.contains("acompanhamento da"), "the tail of the description must survive: " + text);
        assertTrue(text.contains("conforme contrato"),
                "the description's last words must be printed, not clipped: " + text);

        // it occupies more than one line, and the section below moves down with it
        assertTrue(baselineOf(xml, "TRIBUTAÇÃO MUNICIPAL (ISSQN)") > 334.60f,
                "the section below must be pushed down by the extra lines");
        assertEquals(801.93f, baselineOf(xml, "IDENTIFICAÇÃO E ASSINATURA"), 0.15f,
                "the signature footer stays anchored to the page bottom");
    }

    /** A description that fits on one line must not move anything. */
    @Test
    void aShortServiceDescriptionLeavesTheLayoutExactlyWhereItWas() throws Exception {
        assertEquals(334.60f, baselineOf(sampleNfse(), "TRIBUTAÇÃO MUNICIPAL (ISSQN)"), 0.15f);
    }

    private static String completeNfse() throws Exception {
        try (InputStream in = DanfseGeneratorTest.class.getResourceAsStream("/dps/nfse-completo-sample.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String issqnDevidoNfse() throws Exception {
        try (InputStream in = DanfseGeneratorTest.class.getResourceAsStream("/dps/nfse-issqn-devido-sample.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The DANFSe prints horário de Brasília. SEFIN answers with an offset-carrying
     * timestamp, and the same instant expressed as UTC must still render as the
     * Brazilian civil time — never three hours later.
     */
    @Test
    void timestampsArePrintedInHorarioDeBrasiliaWhateverOffsetTheXmlCarries() throws Exception {
        String brasilia = sampleNfse();
        String utc = brasilia
                .replace("<dhProc>2026-08-28T14:37:06-03:00</dhProc>", "<dhProc>2026-08-28T17:37:06+00:00</dhProc>")
                .replace("<dhEmi>2026-08-28T14:37:06-03:00</dhEmi>", "<dhEmi>2026-08-28T17:37:06+00:00</dhEmi>");

        assertEquals("28/08/2026 14:37:06", textAt(brasilia, 301.04f, 79.16f), "reference rendering");
        assertEquals("28/08/2026 14:37:06", textAt(utc, 301.04f, 79.16f),
                "the same instant sent as UTC must still print as Brasília time");
        assertEquals("28/08/2026 14:37:06", textAt(utc, 301.04f, 99.39f), "dhEmi likewise");
    }

    /** The text drawn at one cell of the grid, by its column x and baseline y. */
    private String textAt(String nfseXml, float x, float y) throws Exception {
        try (PDDocument doc = Loader.loadPDF(generator.generate(nfseXml))) {
            StringBuilder found = new StringBuilder();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, java.util.List<TextPosition> positions) {
                    TextPosition first = positions.get(0);
                    if (Math.abs(first.getXDirAdj() - x) < 1.5f && Math.abs(first.getYDirAdj() - y) < 1.5f) {
                        found.append(text.trim());
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
            return found.toString();
        }
    }

    /**
     * An identified intermediário pushes the sections below it down by exactly
     * one block (49.30pt, measured against the official PDF), while the
     * signature footer stays anchored to the bottom of the page.
     */
    @Test
    void anIdentifiedPersonShiftsTheContentBelowButNeverTheFooter() throws Exception {
        assertEquals(283.80f, baselineOf(sampleNfse(), "SERVIÇO PRESTADO"), 0.15f,
                "without an intermediário the serviço section sits at its reference position");
        assertEquals(333.10f, baselineOf(intermNfse(), "SERVIÇO PRESTADO"), 0.15f,
                "an identified intermediário pushes it down by one block");
        for (String xml : new String[]{sampleNfse(), intermNfse()}) {
            assertEquals(801.93f, baselineOf(xml, "IDENTIFICAÇÃO E ASSINATURA"), 0.15f,
                    "the signature footer is anchored to the page bottom");
        }
    }

    /** Top-down baseline (as PDFBox reports it) of the line containing {@code needle}. */
    private float baselineOf(String nfseXml, String needle) throws Exception {
        try (PDDocument doc = Loader.loadPDF(generator.generate(nfseXml))) {
            float[] found = {Float.NaN};
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, java.util.List<TextPosition> positions) {
                    if (text.contains(needle) && Float.isNaN(found[0])) {
                        found[0] = positions.get(0).getYDirAdj();
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);
            assertFalse(Float.isNaN(found[0]), "text not found in the PDF: " + needle);
            return found[0];
        }
    }

    /** NT-008: notes generated in produção restrita (ambGer=2) MUST carry this banner. */
    @Test
    void homologationNotesCarryTheNoLegalValueBanner() throws Exception {
        String text = pdfText(generator.generate(sampleNfse()));
        assertTrue(text.contains("NFS-e SEM VALIDADE JUR"), text);
    }

    /** The banner keys on the DPS tpAmb (the reference production PDF has ambGer=2 but tpAmb=1 and no banner). */
    @Test
    void productionNotesDoNotCarryTheBanner() throws Exception {
        String production = sampleNfse()
                .replace("<ambGer>2</ambGer>", "<ambGer>1</ambGer>")
                .replace("<tpAmb>2</tpAmb>", "<tpAmb>1</tpAmb>");
        String text = pdfText(generator.generate(production));
        assertFalse(text.contains("SEM VALIDADE JUR"), text);
    }
}
