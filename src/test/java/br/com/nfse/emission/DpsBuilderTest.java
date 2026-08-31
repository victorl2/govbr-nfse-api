package br.com.nfse.emission;

import br.com.nfse.dps.DpsSchemaValidator;
import br.com.nfse.testsupport.DryRunTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DpsBuilderTest {

    private final DpsBuilder builder = DryRunTestSupport.builder(null);

    @Test
    void buildsSchemaValidXmlFromTheSampleRequest() {
        String xml = builder.build(DryRunTestSupport.sampleRequest());
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml),
                "built DPS must pass the official XSD, got:\n" + xml);
    }

    @Test
    void composesTheIdExactlyLikeTheDocumentedRule() {
        // "DPS" + cLocEmi(7) + tpInsc "2"=CNPJ + inscription(14) + serie(5) + number(15)
        assertEquals("DPS330455721234567800019500001000000000000001",
                builder.dpsId(DryRunTestSupport.sampleRequest()));
    }

    @Test
    void tpAmbComesFromConfigurationNotFromTheCaller() {
        // localProps is tpAmb=2; nothing in the request can override it.
        String xml = builder.build(DryRunTestSupport.sampleRequest());
        assertTrue(xml.contains("<tpAmb>2</tpAmb>"), xml);
    }

    @Test
    void nbsCodeIsRendered() {
        String xml = builder.build(DryRunTestSupport.sampleRequest());
        assertTrue(xml.contains("<cNBS>115022000</cNBS>"), xml);
    }

    @Test
    void omittedIbsCbsBuildsWithoutTheGroup() {
        EmitNfseRequest req = DryRunTestSupport.sampleRequest();
        EmitNfseRequest without = new EmitNfseRequest(
                req.emitter(), req.dps(), req.service(), req.values(), null, null, null, null);
        String xml = builder.build(without);
        assertFalse(xml.contains("IBSCBS"), xml);
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml));
    }

    @Test
    void omittedSimplesPercentageDeclaresNoEstimateInstead() {
        EmitNfseRequest req = DryRunTestSupport.sampleRequest();
        EmitNfseRequest without = new EmitNfseRequest(
                req.emitter(), req.dps(), req.service(),
                new EmitNfseRequest.Values("1000.00", "1", null, "1", null), req.ibsCbs(), null, null, null);
        String xml = builder.build(without);
        assertTrue(xml.contains("<indTotTrib>0</indTotTrib>"), xml);
        assertFalse(xml.contains("pTotTribSN"), xml);
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml));
    }

    /** The export shape SEFIN restrita accepted on 2026-08-28 (dps-export-accepted.xml). */
    @Test
    void buildsTheAcceptedExportShape() {
        String xml = builder.build(DryRunTestSupport.exportRequest());
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml),
                "export DPS must pass the official XSD, got:\n" + xml);
        assertTrue(xml.contains("<cTribMun>003</cTribMun>"), "municipal complement");
        assertTrue(xml.contains("<cNaoNIF>1</cNaoNIF>"), "foreign tomador without NIF");
        assertTrue(xml.contains("<cPais>US</cPais>"), "foreign address country");
        assertTrue(xml.contains("<tribISSQN>3</tribISSQN>"), "ISSQN = exportação");
        assertTrue(xml.contains("<cPaisResult>US</cPaisResult>"), "country of result");
        // comExt with the defaults the Emissor Web sends
        assertTrue(xml.contains("<mdPrestacao>1</mdPrestacao>") && xml.contains("<tpMoeda>220</tpMoeda>")
                && xml.contains("<vServMoeda>2000.00</vServMoeda>") && xml.contains("<mecAFComexP>01</mecAFComexP>")
                && xml.contains("<mecAFComexT>01</mecAFComexT>") && xml.contains("<movTempBens>1</movTempBens>")
                && xml.contains("<mdic>0</mdic>"), "comExt block: " + xml);
        assertFalse(xml.contains("IBSCBS"), "all-inclusive Simples export carries no IBS/CBS group");
    }

    /** interm is a TCInfoPessoa exactly like toma; it must land between toma and serv. */
    @Test
    void buildsAnIdentifiedIntermediario() {
        EmitNfseRequest req = DryRunTestSupport.exportRequest();
        EmitNfseRequest withInterm = new EmitNfseRequest(
                req.emitter(), req.dps(), req.service(), req.values(), req.ibsCbs(), req.tomador(),
                new EmitNfseRequest.Tomador("11222333000181", null, "AGENCIA INTERMEDIA LTDA", null, null, null),
                req.comercioExterior());
        String xml = builder.build(withInterm);
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml),
                "DPS with interm must pass the official XSD, got:\n" + xml);
        assertTrue(xml.contains("<interm><CNPJ>11222333000181</CNPJ><xNome>AGENCIA INTERMEDIA LTDA</xNome></interm>"),
                xml);
        assertTrue(xml.indexOf("<interm>") > xml.indexOf("</toma>") && xml.indexOf("<interm>") < xml.indexOf("<serv>"),
                "interm must sit between toma and serv: " + xml);
    }

    /** dest (destinatário ≠ adquirente) lives inside IBSCBS, between indDest and valores. */
    @Test
    void buildsAnIdentifiedDestinatarioInsideIbsCbs() {
        EmitNfseRequest req = DryRunTestSupport.exportRequest();
        EmitNfseRequest withDest = new EmitNfseRequest(
                req.emitter(), req.dps(), req.service(), req.values(),
                new EmitNfseRequest.IbsCbs("0", "000001", "1", "000", "000001",
                        new EmitNfseRequest.Tomador("11222333000181", null, "DESTINO FINAL LTDA", null, null, null)),
                req.tomador(), null, req.comercioExterior());
        String xml = builder.build(withDest);
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml),
                "DPS with IBSCBS dest must pass the official XSD, got:\n" + xml);
        assertTrue(xml.contains("<indDest>1</indDest><dest><CNPJ>11222333000181</CNPJ><xNome>DESTINO FINAL LTDA</xNome></dest>"),
                xml);
    }

    /**
     * A national address is mandatory for several indicadores de operação —
     * SEFIN rejects with E0234 without it (seen live on the IBS/CBS flow).
     */
    @Test
    void buildsANationalAddressForTheTomador() {
        EmitNfseRequest req = DryRunTestSupport.sampleRequest();
        EmitNfseRequest withAddress = new EmitNfseRequest(
                req.emitter(), req.dps(), req.service(), req.values(), req.ibsCbs(),
                new EmitNfseRequest.Tomador("11222333000181", null, "CLIENTE NACIONAL LTDA", null, null, null,
                        new EmitNfseRequest.EnderecoNacional("3304557", "20031170", "Avenida Rio Branco", "156",
                                null, "Centro")),
                null, null);
        String xml = builder.build(withAddress);
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml),
                "DPS with a national tomador address must pass the official XSD, got:\n" + xml);
        assertTrue(xml.contains("<endNac><cMun>3304557</cMun><CEP>20031170</CEP></endNac>"), xml);
        assertTrue(xml.contains("<xLgr>Avenida Rio Branco</xLgr><nro>156</nro><xBairro>Centro</xBairro>"), xml);
    }

    /**
     * Cancelamento por substituição: a DPS that carries the key of an existing
     * NFS-e makes SEFIN cancel that note and issue this one in its place, in a
     * single operation. {@code subst} sits between cLocEmi and prest.
     */
    @Test
    void buildsTheSubstitutionBlockForAReplacementNote() {
        EmitNfseRequest req = DryRunTestSupport.exportRequest();
        EmitNfseRequest replacement = new EmitNfseRequest(
                req.emitter(), req.dps(), req.service(), req.values(), req.ibsCbs(), req.tomador(),
                req.intermediario(), req.comercioExterior(),
                new EmitNfseRequest.Substituicao(
                        "33045572212345678000195000000000000126081270635662", "99",
                        "Correcao da descricao do servico prestado"));

        String xml = builder.build(replacement);
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml),
                "DPS with subst must pass the official XSD, got:\n" + xml);
        assertTrue(xml.contains("<subst><chSubstda>33045572212345678000195000000000000126081270635662</chSubstda>"
                + "<cMotivo>99</cMotivo><xMotivo>Correcao da descricao do servico prestado</xMotivo></subst>"), xml);
        assertTrue(xml.indexOf("<subst>") > xml.indexOf("</cLocEmi>")
                        && xml.indexOf("<subst>") < xml.indexOf("<prest>"),
                "subst must sit between cLocEmi and prest: " + xml);
    }

    /**
     * Every timestamp on a Brazilian fiscal document is horário de Brasília. A UTC
     * host (every Linux container by default) must not change that: at 02:30 UTC on
     * the 1st it is still 23:30 of the previous day in Brasília, and stamping the
     * note with the UTC date would book it into the NEXT MONTH's competência.
     */
    @Test
    void timestampsAreHorarioDeBrasiliaWhateverZoneTheHostRunsIn() {
        Clock utcHost = Clock.fixed(Instant.parse("2026-09-01T02:30:00Z"), ZoneOffset.UTC);
        DpsBuilder onUtcHost = new DpsBuilder(DryRunTestSupport.localProps(null), utcHost);
        EmitNfseRequest req = DryRunTestSupport.sampleRequest();
        EmitNfseRequest defaulted = new EmitNfseRequest(
                req.emitter(), new EmitNfseRequest.Dps("00001", "1", null, null),
                req.service(), req.values(), req.ibsCbs(), null, null, null);

        String xml = onUtcHost.build(defaulted);
        assertTrue(xml.contains("<dhEmi>2026-08-31T23:30:00-03:00</dhEmi>"),
                "dhEmi must be Brasília local time with the -03:00 offset: " + xml);
        assertTrue(xml.contains("<dCompet>2026-08-31</dCompet>"),
                "competência must follow the Brasília date, not the host's: " + xml);
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml));
    }

    /** Discounts live in valores/vDescCondIncond, between vServPrest and trib. */
    @Test
    void buildsUnconditionalAndConditionalDiscounts() {
        EmitNfseRequest req = DryRunTestSupport.sampleRequest();
        EmitNfseRequest withDiscounts = withValues(req, new EmitNfseRequest.Values(
                "1000.00", "1", null, "1", "6.00",
                new EmitNfseRequest.Descontos("100.00", "50.00"), null, null));

        String xml = builder.build(withDiscounts);
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml),
                "DPS with discounts must pass the official XSD, got:\n" + xml);
        assertTrue(xml.contains("<vDescCondIncond><vDescIncond>100.00</vDescIncond>"
                + "<vDescCond>50.00</vDescCond></vDescCondIncond>"), xml);
        assertTrue(xml.indexOf("<vDescCondIncond>") > xml.indexOf("</vServPrest>")
                && xml.indexOf("<vDescCondIncond>") < xml.indexOf("<trib>"), "order: " + xml);
    }

    /** Federal retentions (IRRF/CSLL/CP) and PIS/COFINS sit in trib/tribFed, after tribMun. */
    @Test
    void buildsFederalRetentionsAndPisCofins() {
        EmitNfseRequest req = DryRunTestSupport.sampleRequest();
        EmitNfseRequest withFederal = withValues(req, new EmitNfseRequest.Values(
                "1000.00", "1", null, "1", "6.00", null, null,
                new EmitNfseRequest.TributacaoFederal(
                        new EmitNfseRequest.PisCofins("01", "1000.00", "0.65", "3.00",
                                "6.50", "30.00", "1"),
                        "110.00", "15.00", "10.00")));

        String xml = builder.build(withFederal);
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml),
                "DPS with tribFed must pass the official XSD, got:\n" + xml);
        assertTrue(xml.contains("<tribFed><piscofins><CST>01</CST><vBCPisCofins>1000.00</vBCPisCofins>"
                + "<pAliqPis>0.65</pAliqPis><pAliqCofins>3.00</pAliqCofins><vPis>6.50</vPis>"
                + "<vCofins>30.00</vCofins><tpRetPisCofins>1</tpRetPisCofins></piscofins>"
                + "<vRetCP>110.00</vRetCP><vRetIRRF>15.00</vRetIRRF><vRetCSLL>10.00</vRetCSLL></tribFed>"), xml);
        assertTrue(xml.indexOf("<tribFed>") > xml.indexOf("</tribMun>")
                && xml.indexOf("<tribFed>") < xml.indexOf("<totTrib>"), "order: " + xml);
    }

    /** infoCompl is the last child of serv, and its own children have a fixed order. */
    @Test
    void buildsComplementaryInformation() {
        EmitNfseRequest req = DryRunTestSupport.sampleRequest();
        EmitNfseRequest withInfo = new EmitNfseRequest(
                req.emitter(), req.dps(),
                new EmitNfseRequest.Service("010701", null, "Desenvolvimento de software sob encomenda",
                        "115022000", "3304557",
                        new EmitNfseRequest.InformacoesComplementares(
                                "Contrato 2026/014 - parcela 3 de 12", "NF 12345", "PO-99",
                                List.of("item-1", "item-2"))),
                req.values(), req.ibsCbs(), null, null, null);

        String xml = builder.build(withInfo);
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml),
                "DPS with infoCompl must pass the official XSD, got:\n" + xml);
        assertTrue(xml.contains("<infoCompl><docRef>NF 12345</docRef><xPed>PO-99</xPed>"
                + "<gItemPed><xItemPed>item-1</xItemPed><xItemPed>item-2</xItemPed></gItemPed>"
                + "<xInfComp>Contrato 2026/014 - parcela 3 de 12</xInfComp></infoCompl>"), xml);
    }

    /** vDedRed is a CHOICE: an itemised document list is one of its three forms. */
    @Test
    void buildsDeductionsFromTheirSupportingDocuments() {
        EmitNfseRequest req = DryRunTestSupport.sampleRequest();
        EmitNfseRequest withDeduction = withValues(req, new EmitNfseRequest.Values(
                "1000.00", "1", null, "1", "6.00", null,
                new EmitNfseRequest.DeducaoReducao(null, null, List.of(
                        new EmitNfseRequest.DocumentoDeducao(
                                null, null, "NF-4711", null, "1", null,
                                "2026-08-01", "100.00", "100.00"))),
                null));

        String xml = builder.build(withDeduction);
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml),
                "DPS with vDedRed must pass the official XSD, got:\n" + xml);
        assertTrue(xml.contains("<docDedRed><nDocFisc>NF-4711</nDocFisc><tpDedRed>1</tpDedRed>"
                + "<dtEmiDoc>2026-08-01</dtEmiDoc><vDedutivelRedutivel>100.00</vDedutivelRedutivel>"
                + "<vDeducaoReducao>100.00</vDeducaoReducao></docDedRed>"), xml);
    }

    /** The flat-percentage form is the other common one, and excludes the rest. */
    @Test
    void buildsAFlatPercentageDeduction() {
        EmitNfseRequest req = DryRunTestSupport.sampleRequest();
        String xml = builder.build(withValues(req, new EmitNfseRequest.Values(
                "1000.00", "1", null, "1", "6.00", null,
                new EmitNfseRequest.DeducaoReducao("10.00", null, null), null)));
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml), xml);
        assertTrue(xml.contains("<vDedRed><pDR>10.00</pDR></vDedRed>"), xml);
    }

    /** The layout's choice is not advisory — asking for two forms is a caller error. */
    @Test
    void aDeductionCarryingTwoOfTheThreeFormsIsRefused() {
        assertThrows(IllegalArgumentException.class, () ->
                new EmitNfseRequest.DeducaoReducao("10.00", "100.00", null));
        assertThrows(IllegalArgumentException.class, () ->
                new EmitNfseRequest.DeducaoReducao(null, null, null));
    }

    /** A national taker identified by CPF — the B2C shape, never exercised before. */
    @Test
    void buildsANationalTomadorIdentifiedByCpf() {
        EmitNfseRequest req = DryRunTestSupport.sampleRequest();
        EmitNfseRequest b2c = new EmitNfseRequest(
                req.emitter(), req.dps(), req.service(), req.values(), req.ibsCbs(),
                new EmitNfseRequest.Tomador(null, "12345678901", "MARIA DE EXEMPLO", null, null, null,
                        new EmitNfseRequest.EnderecoNacional("3304557", "20031170",
                                "Avenida Rio Branco", "156", "sala 1201", "Centro")),
                null, null);

        String xml = builder.build(b2c);
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(xml),
                "DPS with a CPF tomador must pass the official XSD, got:\n" + xml);
        assertTrue(xml.contains("<toma><CPF>12345678901</CPF><xNome>MARIA DE EXEMPLO</xNome>"), xml);
        assertTrue(xml.contains("<xCpl>sala 1201</xCpl>"), xml);
    }

    private static EmitNfseRequest withValues(EmitNfseRequest req, EmitNfseRequest.Values values) {
        return new EmitNfseRequest(req.emitter(), req.dps(), req.service(), values,
                req.ibsCbs(), req.tomador(), req.intermediario(), req.comercioExterior());
    }

    @Test
    void cpfEmitterGetsInscriptionType1AndZeroPadding() {
        EmitNfseRequest req = DryRunTestSupport.sampleRequest();
        EmitNfseRequest cpf = new EmitNfseRequest(
                new EmitNfseRequest.Emitter(null, "12345678901", "3304557", "3", "1", "0"),
                req.dps(), req.service(), req.values(), req.ibsCbs(), null, null, null);
        assertEquals("DPS330455710001234567890100001000000000000001", builder.dpsId(cpf));
        assertEquals(List.of(), new DpsSchemaValidator().validateCollecting(builder.build(cpf)));
    }
}
