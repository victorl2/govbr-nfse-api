package br.com.nfse.dryrun;

import br.com.nfse.config.SafeXml;
import br.com.nfse.dps.DpsSchemaValidator;
import br.com.nfse.signing.EnvelopedXmlSigner;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline validation dry-run: XSD → lint → sign → self-verify, collecting every
 * finding into one report. Never touches the network; nothing is sent to gov.br.
 *
 * <p>All stages run even when an earlier one found problems (each catches a
 * different class of mistake), except signing, which needs a parseable document
 * with an {@code infDPS@Id}. A clean report means the DPS is as publishable as
 * we can prove offline; server-side rules (E-rule catalog, municipal parameters,
 * RTC calculator) are only exercised for real in produção restrita.
 */
public class DpsDryRunService {

    private static final String NS = "http://www.sped.fazenda.gov.br/nfse";

    private final DpsSchemaValidator schemaValidator;
    private final DpsLinter linter;
    private final EnvelopedXmlSigner signer;
    private final SignatureVerifier verifier;

    public DpsDryRunService(DpsSchemaValidator schemaValidator, DpsLinter linter,
                            EnvelopedXmlSigner signer, SignatureVerifier verifier) {
        this.schemaValidator = schemaValidator;
        this.linter = linter;
        this.signer = signer;
        this.verifier = verifier;
    }

    public DryRunReport dryRun(String dpsXml, LintExpectations expectations) {
        return run(dpsXml, expectations).report();
    }

    /** Like {@link #dryRun}, but also hands back the signed DPS for callers that will submit it. */
    public DryRunOutcome run(String dpsXml, LintExpectations expectations) {
        Document doc;
        try {
            doc = SafeXml.documentBuilderFactory().newDocumentBuilder().parse(
                    new ByteArrayInputStream(dpsXml.getBytes(StandardCharsets.UTF_8)));
        } catch (SAXParseException e) {
            return new DryRunOutcome(DryRunReport.of(List.of(new ValidationFinding(
                    Stage.XSD, Severity.ERROR, "XSD000",
                    "not well-formed XML: " + e.getMessage(),
                    e.getLineNumber() > 0 ? e.getLineNumber() : null))), null);
        } catch (Exception e) {
            return new DryRunOutcome(DryRunReport.of(List.of(
                    ValidationFinding.error(Stage.XSD, "XSD000", "unreadable XML: " + e.getMessage()))), null);
        }

        List<ValidationFinding> findings = new ArrayList<>(schemaValidator.validateCollecting(dpsXml));
        findings.addAll(linter.lint(doc, expectations));
        String signedXml = signAndVerify(doc, findings);
        return new DryRunOutcome(DryRunReport.of(findings), signedXml);
    }

    /** Signs and self-verifies in place; returns the signed XML, or null when signing was impossible. */
    private String signAndVerify(Document doc, List<ValidationFinding> findings) {
        String id = infDpsId(doc);
        if (id.isEmpty()) {
            findings.add(ValidationFinding.error(Stage.SIGN, "SIG000",
                    "infDPS has no Id attribute — the signature Reference has nothing to point at"));
            return null;
        }
        boolean xsdWasClean = findings.stream()
                .noneMatch(f -> f.stage() == Stage.XSD && f.severity() == Severity.ERROR);
        try {
            signer.signEnveloped(doc, id);
        } catch (Exception e) {
            findings.add(ValidationFinding.error(Stage.SIGN, "SIG005",
                    "signing failed: " + e.getMessage()));
            return null;
        }
        findings.addAll(verifier.verify(doc));
        String signedXml = serialize(doc);
        // Prove the inserted Signature element kept the document schema-valid, but only
        // when the input itself was clean — otherwise this just repeats the XSD stage.
        if (xsdWasClean) {
            for (ValidationFinding f : schemaValidator.validateCollecting(signedXml)) {
                findings.add(new ValidationFinding(Stage.VERIFY, f.severity(), "SIG004",
                        "signed document is no longer schema-valid: " + f.message(), null));
            }
        }
        return signedXml;
    }

    private static String infDpsId(Document doc) {
        for (Node n = doc.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && NS.equals(el.getNamespaceURI()) && "infDPS".equals(el.getLocalName())) {
                return el.getAttribute("Id");
            }
        }
        return "";
    }

    private static String serialize(Document doc) {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize signed DPS: " + e.getMessage(), e);
        }
    }
}
