package br.com.nfse.dryrun;

import br.com.nfse.testsupport.DryRunTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DpsLinterTest {

    private final DpsLinter linter = new DpsLinter(DryRunTestSupport.localProps(null));

    private List<ValidationFinding> lint(String xml) {
        return linter.lint(DryRunTestSupport.parse(xml), DryRunTestSupport.rioSimples());
    }

    private static boolean has(List<ValidationFinding> findings, String code, Severity severity) {
        return findings.stream().anyMatch(f -> code.equals(f.code()) && f.severity() == severity);
    }

    @Test
    void cleanFixtureHasNoFindings() {
        assertEquals(List.of(), lint(DryRunTestSupport.validDps()));
    }

    /**
     * SEFIN rejected a substitution with E0063 live on 2026-08-28: for a Simples
     * Nacional optante the replacement may not change dCompet, the tomador's
     * identification or vServ. We cannot verify that offline (it needs the original
     * note), so the rule is a warning that states what must match.
     */
    @Test
    void aSimplesSubstitutionWarnsAboutTheFieldsThatMayNotChange() {
        String xml = DryRunTestSupport.validDps().replace("<prest>",
                "<subst><chSubstda>33045572212345678000195000000000000126081270635662</chSubstda>"
                        + "<cMotivo>99</cMotivo></subst><prest>");
        assertTrue(has(lint(xml), "SUB001", Severity.WARN), "" + lint(xml));
    }

    /** A regime-normal emitter is not bound by E0063, so the warning must stay quiet. */
    @Test
    void substitutionWarningOnlyAppliesToSimplesOptantes() {
        String xml = DryRunTestSupport.validDps()
                .replace("<opSimpNac>3</opSimpNac>", "<opSimpNac>1</opSimpNac>")
                .replace("<prest>", "<subst><chSubstda>33045572212345678000195000000000000126081270635662</chSubstda>"
                        + "<cMotivo>99</cMotivo></subst><prest>");
        assertTrue(linter.lint(DryRunTestSupport.parse(xml), new LintExpectations("3304557", "1"))
                .stream().noneMatch(f -> "SUB001".equals(f.code())));
    }

    /** A DPS with no subst block says nothing about substitution. */
    @Test
    void anOrdinaryNoteRaisesNoSubstitutionWarning() {
        assertTrue(lint(DryRunTestSupport.validDps()).stream()
                .noneMatch(f -> "SUB001".equals(f.code())));
    }

    /** For regime-normal emitters the IBS/CBS group is mandatory since 2026-08-03 (NT-004). */
    @Test
    void missingIbscbsOnOrAfterCutoverIsErrorForRegimeNormal() {
        String xml = DryRunTestSupport.validDps()
                .replace("<opSimpNac>3</opSimpNac>", "<opSimpNac>1</opSimpNac>")
                .replaceAll("(?s)<IBSCBS>.*</IBSCBS>", "");
        List<ValidationFinding> findings = linter.lint(
                DryRunTestSupport.parse(xml), new LintExpectations("3304557", "1"));
        assertTrue(findings.stream().anyMatch(f -> "RTC001".equals(f.code())), "" + findings);
    }

    /**
     * An all-inclusive Simples Nacional optante does NOT send the IBS/CBS group —
     * proven live: SEFIN restrita generated an NFS-e without it on 2026-08-28
     * (dps-export-accepted.xml). RTC001 must stay quiet for optantes.
     */
    @Test
    void ibscbsIsOptionalForSimplesOptantes() {
        String xml = DryRunTestSupport.validDps()
                .replaceAll("(?s)<IBSCBS>.*</IBSCBS>", "");
        assertTrue(lint(xml).stream().noneMatch(f -> "RTC001".equals(f.code())));
    }

    @Test
    void ibscbsNotRequiredBeforeCutoverEvenForRegimeNormal() {
        String xml = DryRunTestSupport.validDps()
                .replace("<opSimpNac>3</opSimpNac>", "<opSimpNac>1</opSimpNac>")
                .replace("2026-08-28T12:00:00-03:00", "2026-07-01T12:00:00-03:00")
                .replaceAll("(?s)<IBSCBS>.*</IBSCBS>", "");
        List<ValidationFinding> findings = linter.lint(
                DryRunTestSupport.parse(xml), new LintExpectations("3304557", "1"));
        assertTrue(findings.stream().noneMatch(f -> "RTC001".equals(f.code())), "" + findings);
    }

    /** Exportação de serviço (tribISSQN=3) needs the country of result and the comExt block. */
    @Test
    void exportWithoutCountryOfResultAndComExtIsFlagged() {
        String xml = DryRunTestSupport.validDps()
                .replace("<tribISSQN>1</tribISSQN>", "<tribISSQN>3</tribISSQN>");
        List<ValidationFinding> findings = lint(xml);
        assertTrue(has(findings, "EXP001", Severity.ERROR), "EXP001 in: " + findings);
        assertTrue(has(findings, "EXP002", Severity.ERROR), "EXP002 in: " + findings);
    }

    /** SEFIN rule E0322 (observed live): IBS/CBS info present requires an NBS item. */
    @Test
    void ibscbsWithoutNbsCodeIsError() {
        String xml = DryRunTestSupport.validDps().replace("<cNBS>115022000</cNBS>", "");
        assertTrue(has(lint(xml), "RTC002", Severity.ERROR));
    }

    /** opSimpNac: 1 = Não Optante, 2 = MEI, 3 = ME/EPP. We are Simples ME/EPP, so anything but 3 is wrong. */
    @Test
    void nonOptanteSimplesValueIsError() {
        String xml = DryRunTestSupport.validDps()
                .replace("<opSimpNac>3</opSimpNac>", "<opSimpNac>1</opSimpNac>");
        assertTrue(has(lint(xml), "SN001", Severity.ERROR));
    }

    @Test
    void tpAmbDisagreeingWithConfiguredEnvironmentIsError() {
        String xml = DryRunTestSupport.validDps()
                .replace("<tpAmb>2</tpAmb>", "<tpAmb>1</tpAmb>");
        assertTrue(has(lint(xml), "ENV001", Severity.ERROR));
    }

    @Test
    void emitterMunicipalityOtherThanRioIsWarning() {
        String xml = DryRunTestSupport.validDps()
                .replace("<cLocEmi>3304557</cLocEmi>", "<cLocEmi>3550308</cLocEmi>");
        assertTrue(has(lint(xml), "LOC001", Severity.WARN));
    }

    @Test
    void totTribWithoutSimplesPercentageIsWarning() {
        String xml = DryRunTestSupport.validDps()
                .replace("<pTotTribSN>6.00</pTotTribSN>", "<indTotTrib>0</indTotTrib>");
        assertTrue(has(lint(xml), "SN002", Severity.WARN));
    }

    @Test
    void municipalityAndSimplesChecksAreDrivenByCallerExpectations() {
        // São Paulo emitter, regime normal: same linter, different expectations.
        String xml = DryRunTestSupport.validDps()
                .replace("<cLocEmi>3304557</cLocEmi>", "<cLocEmi>3550308</cLocEmi>")
                .replace("<opSimpNac>3</opSimpNac>", "<opSimpNac>1</opSimpNac>");

        List<ValidationFinding> findings = linter.lint(
                DryRunTestSupport.parse(xml), new LintExpectations("3550308", "1"));
        assertTrue(findings.stream().noneMatch(f -> "LOC001".equals(f.code())), "LOC001 in: " + findings);
        assertTrue(findings.stream().noneMatch(f -> "SN001".equals(f.code())), "SN001 in: " + findings);
    }

    @Test
    void companyChecksAreSkippedWithoutExpectations() {
        String xml = DryRunTestSupport.validDps()
                .replace("<cLocEmi>3304557</cLocEmi>", "<cLocEmi>3550308</cLocEmi>")
                .replace("<opSimpNac>3</opSimpNac>", "<opSimpNac>1</opSimpNac>")
                .replace("<pTotTribSN>6.00</pTotTribSN>", "<indTotTrib>0</indTotTrib>");

        List<ValidationFinding> findings = linter.lint(
                DryRunTestSupport.parse(xml), LintExpectations.none());
        assertTrue(findings.stream().noneMatch(
                f -> "LOC001".equals(f.code()) || "SN001".equals(f.code()) || "SN002".equals(f.code())),
                "expected no company-specific findings, got: " + findings);
    }

    @Test
    void simplesPercentageWarningOnlyAppliesToOptantes() {
        // Expected Não Optante: indTotTrib=0 is the normal path, no SN002.
        String xml = DryRunTestSupport.validDps()
                .replace("<opSimpNac>3</opSimpNac>", "<opSimpNac>1</opSimpNac>")
                .replace("<pTotTribSN>6.00</pTotTribSN>", "<indTotTrib>0</indTotTrib>");
        assertTrue(linter.lint(DryRunTestSupport.parse(xml), new LintExpectations("3304557", "1"))
                .stream().noneMatch(f -> "SN002".equals(f.code())));
    }

    @Test
    void idNotMatchingKeyCompositionIsWarning() {
        String xml = DryRunTestSupport.validDps()
                .replace("Id=\"DPS330455721234567800019500001000000000000001\"",
                         "Id=\"DPS000000000000000000000000000000000000000000\"");
        assertTrue(has(lint(xml), "ID001", Severity.WARN));
    }
}
