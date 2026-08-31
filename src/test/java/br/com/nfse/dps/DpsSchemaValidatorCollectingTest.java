package br.com.nfse.dps;

import br.com.nfse.dryrun.Severity;
import br.com.nfse.dryrun.Stage;
import br.com.nfse.dryrun.ValidationFinding;
import br.com.nfse.testsupport.DryRunTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DpsSchemaValidatorCollectingTest {

    private final DpsSchemaValidator validator = new DpsSchemaValidator();

    @Test
    void validDpsYieldsNoFindings() {
        assertEquals(List.of(), validator.validateCollecting(DryRunTestSupport.validDps()));
    }

    /**
     * The official TSSerieDPS pattern ({@code ^0{0,4}\d{1,5}$}) uses ^/$ which are
     * LITERAL characters in XML Schema regex — a spec-compliant validator therefore
     * rejects every real-world serie. The validator must shim that one pattern so
     * real values pass, as SEFIN's own (.NET) validator accepts them.
     */
    @Test
    void realWorldSerieValuesSurviveTheOfficialPatternQuirk() {
        String unpadded = DryRunTestSupport.validDps().replace("<serie>00001</serie>", "<serie>1</serie>");
        assertEquals(List.of(), validator.validateCollecting(unpadded));
    }

    @Test
    void collectsEveryErrorNotJustTheFirst() {
        String broken = DryRunTestSupport.validDps()
                .replace("<tpAmb>2</tpAmb>", "<tpAmb>9</tpAmb>")
                .replace("<vServ>1000.00</vServ>", "");
        List<ValidationFinding> findings = validator.validateCollecting(broken);

        assertTrue(findings.size() >= 2, "expected both errors reported, got: " + findings);
        for (ValidationFinding f : findings) {
            assertEquals(Stage.XSD, f.stage());
            assertEquals(Severity.ERROR, f.severity());
            assertNotNull(f.line(), "XSD findings should carry a line number");
        }
    }

    @Test
    void malformedXmlIsReportedAsFinding() {
        List<ValidationFinding> findings = validator.validateCollecting("<DPS><infDPS>");
        assertFalse(findings.isEmpty());
        assertEquals(Severity.ERROR, findings.get(0).severity());
    }
}
