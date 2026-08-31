package br.com.nfse.dryrun;

import br.com.nfse.config.NfseProperties;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Local business-rule lint of a parsed DPS: the rules SEFIN enforces server-side
 * that we can predict offline, plus the caller's own expectations (municipality,
 * Simples Nacional situation) supplied per run via {@link LintExpectations} —
 * unset expectations disable those checks, keeping the service company-neutral.
 * Structural problems are the XSD stage's job — when an element a rule needs is
 * missing, the rule stays silent rather than duplicating the schema error.
 */
public class DpsLinter {

    private static final String NS = "http://www.sped.fazenda.gov.br/nfse";
    /** IBS/CBS groups and their validation rules are mandatory in production since this date (RTC page, NT-004). */
    private static final LocalDate IBSCBS_MANDATORY_FROM = LocalDate.of(2026, 8, 3);

    private final NfseProperties props;

    public DpsLinter(NfseProperties props) {
        this.props = props;
    }

    public List<ValidationFinding> lint(Document dps, LintExpectations expectations) {
        Element infDps = child(dps.getDocumentElement(), "infDPS");
        if (infDps == null) {
            return List.of();
        }
        LintExpectations lint = expectations == null ? LintExpectations.none() : expectations;
        List<ValidationFinding> findings = new ArrayList<>();
        checkEnvironment(infDps, findings);
        checkIbsCbsPresence(infDps, lint, findings);
        checkExport(infDps, findings);
        checkSimplesNacional(infDps, lint, findings);
        checkSubstitution(infDps, lint, findings);
        checkMunicipality(infDps, lint, findings);
        checkIdComposition(infDps, findings);
        return List.copyOf(findings);
    }

    /** Exportação (tribISSQN=3) needs the country of result and the comExt block — proven live 2026-08-28. */
    private void checkExport(Element infDps, List<ValidationFinding> findings) {
        Element tribMun = descend(infDps, "valores", "trib", "tribMun");
        if (tribMun == null || !"3".equals(childText(tribMun, "tribISSQN").orElse(""))) {
            return;
        }
        if (child(tribMun, "cPaisResult") == null) {
            findings.add(ValidationFinding.error(Stage.LINT, "EXP001",
                    "exportação de serviço (tribISSQN=3) requires cPaisResult — the country where"
                            + " the service's result is verified"));
        }
        if (descend(infDps, "serv", "comExt") == null) {
            findings.add(ValidationFinding.error(Stage.LINT, "EXP002",
                    "exportação de serviço requires the comExt group (mdPrestacao, vincPrest,"
                            + " tpMoeda, vServMoeda...) — the Emissor Web always sends it"));
        }
    }

    /**
     * SEFIN rule E0063, observed live in restrita on 2026-08-28: when the emitter is
     * a Simples Nacional optante, a substituting DPS may not change the competência,
     * the tomador's identification or the service value of the note it replaces.
     * Verifying that needs the original note, so this is a warning naming the
     * constraint rather than a check we can settle offline.
     */
    private void checkSubstitution(Element infDps, LintExpectations lint, List<ValidationFinding> findings) {
        if (child(infDps, "subst") == null || !lint.simplesOptante()) {
            return;
        }
        findings.add(ValidationFinding.warn(Stage.LINT, "SUB001",
                "this DPS substitutes an existing NFS-e and the emitter is a Simples Nacional optante:"
                        + " dCompet, the tomador's identification (CPF/CNPJ/NIF) and vServ must be identical"
                        + " to the note being replaced — SEFIN rejects any change with E0063"));
    }

    private void checkEnvironment(Element infDps, List<ValidationFinding> findings) {
        childText(infDps, "tpAmb")
                .filter(tpAmb -> !tpAmb.equals(String.valueOf(props.tpAmb())))
                .ifPresent(tpAmb -> findings.add(ValidationFinding.error(Stage.LINT, "ENV001",
                        "tpAmb=" + tpAmb + " disagrees with the configured environment ("
                                + props.environment() + " expects tpAmb=" + props.tpAmb()
                                + ") — this DPS would target the wrong SEFIN environment")));
    }

    private void checkIbsCbsPresence(Element infDps, LintExpectations lint, List<ValidationFinding> findings) {
        // An all-inclusive Simples optante does not send the IBS/CBS group (taxes live
        // inside the DAS) — proven live: restrita generated an NFS-e without it on
        // 2026-08-28. The NT-004 mandate applies to the regime-normal path.
        Optional<LocalDate> emitted = childText(infDps, "dhEmi")
                .filter(v -> v.length() >= 10)
                .map(v -> LocalDate.parse(v.substring(0, 10)));
        if (!lint.simplesOptante()
                && lint.opSimpNacConfigured()
                && emitted.isPresent()
                && !emitted.get().isBefore(IBSCBS_MANDATORY_FROM)
                && child(infDps, "IBSCBS") == null) {
            findings.add(ValidationFinding.error(Stage.LINT, "RTC001",
                    "IBSCBS group is missing: the IBS/CBS groups are mandatory in production since "
                            + IBSCBS_MANDATORY_FROM + " (Reforma Tributária, NT-004) — SEFIN will reject this DPS"));
        }
        // SEFIN rule E0322, observed live in restrita on 2026-08-28.
        if (child(infDps, "IBSCBS") != null
                && descend(infDps, "serv", "cServ", "cNBS") == null) {
            findings.add(ValidationFinding.error(Stage.LINT, "RTC002",
                    "IBS/CBS information requires an NBS item: fill serv/cServ/cNBS (Anexo B)"
                            + " — SEFIN rejects with E0322 otherwise"));
        }
    }

    private void checkSimplesNacional(Element infDps, LintExpectations lint, List<ValidationFinding> findings) {
        if (!lint.opSimpNacConfigured()) {
            return;
        }
        Element regTrib = descend(infDps, "prest", "regTrib");
        if (regTrib != null) {
            childText(regTrib, "opSimpNac")
                    .filter(v -> !lint.expectedOpSimpNac().equals(v))
                    .ifPresent(v -> findings.add(ValidationFinding.error(Stage.LINT, "SN001",
                            "opSimpNac=" + v + " but this emitter is configured as opSimpNac="
                                    + lint.expectedOpSimpNac() + " (1=Não Optante, 2=MEI, 3=ME/EPP)")));
        }
        Element totTrib = descend(infDps, "valores", "trib", "totTrib");
        if (lint.simplesOptante() && totTrib != null && child(totTrib, "pTotTribSN") == null) {
            findings.add(ValidationFinding.warn(Stage.LINT, "SN002",
                    "totTrib does not carry pTotTribSN — for a Simples Nacional optante the total-tax"
                            + " transparency is expected through the Simples percentage (docs/04 §4.7)"));
        }
    }

    private void checkMunicipality(Element infDps, LintExpectations lint, List<ValidationFinding> findings) {
        if (!lint.municipalityConfigured()) {
            return;
        }
        childText(infDps, "cLocEmi")
                .filter(v -> !lint.expectedMunicipality().equals(v))
                .ifPresent(v -> findings.add(ValidationFinding.warn(Stage.LINT, "LOC001",
                        "cLocEmi=" + v + " differs from the configured emitter municipality ("
                                + lint.expectedMunicipality() + ")")));
    }

    /** Id = "DPS" + cLocEmi(7) + inscription type(1: 1=CPF, 2=CNPJ) + inscription(14) + serie(5) + nDPS(15). */
    private void checkIdComposition(Element infDps, List<ValidationFinding> findings) {
        String id = infDps.getAttribute("Id");
        Element prest = child(infDps, "prest");
        Optional<String> cLocEmi = childText(infDps, "cLocEmi");
        Optional<String> serie = childText(infDps, "serie");
        Optional<String> nDps = childText(infDps, "nDPS");
        if (id.isEmpty() || prest == null || cLocEmi.isEmpty() || serie.isEmpty() || nDps.isEmpty()) {
            return;
        }
        Optional<String> inscription = childText(prest, "CNPJ").map(cnpj -> "2" + cnpj)
                .or(() -> childText(prest, "CPF").map(cpf -> "1" + pad(cpf, 14)));
        if (inscription.isEmpty()) {
            return;
        }
        String expected = "DPS" + cLocEmi.get() + inscription.get() + pad(serie.get(), 5) + pad(nDps.get(), 15);
        if (!expected.equals(id)) {
            findings.add(ValidationFinding.warn(Stage.LINT, "ID001",
                    "infDPS@Id does not match the documented key composition — expected " + expected
                            + " from cLocEmi/inscription/serie/nDPS, found " + id));
        }
    }

    private static String pad(String value, int width) {
        return "0".repeat(Math.max(0, width - value.length())) + value;
    }

    private static Element child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && NS.equals(el.getNamespaceURI()) && localName.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    private static Element descend(Element parent, String... path) {
        Element current = parent;
        for (String localName : path) {
            if (current == null) {
                return null;
            }
            current = child(current, localName);
        }
        return current;
    }

    private static Optional<String> childText(Element parent, String localName) {
        Element el = child(parent, localName);
        if (el == null) {
            return Optional.empty();
        }
        String text = el.getTextContent();
        return (text == null || text.isBlank()) ? Optional.empty() : Optional.of(text.trim());
    }
}
