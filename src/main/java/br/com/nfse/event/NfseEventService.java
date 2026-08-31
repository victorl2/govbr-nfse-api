package br.com.nfse.event;

import br.com.nfse.config.SafeXml;
import br.com.nfse.dryrun.Severity;
import br.com.nfse.dryrun.SignatureVerifier;
import br.com.nfse.dryrun.Stage;
import br.com.nfse.dryrun.ValidationFinding;
import br.com.nfse.sefin.SefinClient;
import br.com.nfse.sefin.SefinEventResult;
import br.com.nfse.sefin.SefinMessage;
import br.com.nfse.signing.EnvelopedXmlSigner;
import org.w3c.dom.Document;

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
 * The event pipeline: build the Pedido de Registro de Evento, validate it against
 * the official XSD, sign it, self-verify, and submit it to
 * {@code POST /nfse/{chave}/eventos}. Same shape as the emission pipeline, and the
 * same rule — a pedido that cannot pass the schema never reaches SEFIN.
 *
 * <p>Cancelling is the one operation with a deadline: the municipal window closes
 * some days after emission, after which the note can only be replaced (a DPS with
 * a {@code subst} block) or cancelled through fiscal analysis. A rejection past
 * the window comes back as a SEFIN-stage finding, not an exception.
 */
public class NfseEventService {

    private final EventBuilder builder;
    private final PedRegEventoSchemaValidator schemaValidator;
    private final EnvelopedXmlSigner signer;
    private final SignatureVerifier verifier;
    private final SefinClient sefinClient;

    public NfseEventService(EventBuilder builder, PedRegEventoSchemaValidator schemaValidator,
                            EnvelopedXmlSigner signer, SignatureVerifier verifier, SefinClient sefinClient) {
        this.builder = builder;
        this.schemaValidator = schemaValidator;
        this.signer = signer;
        this.verifier = verifier;
        this.sefinClient = sefinClient;
    }

    /** Builds, validates and signs the cancellation without sending it. */
    public EventResponse validateCancellation(String chaveAcesso, CancelEventRequest request) {
        String pedidoXml = builder.buildCancellation(chaveAcesso, request);
        List<ValidationFinding> findings = new ArrayList<>(schemaValidator.validateCollecting(pedidoXml));
        String signed = signAndVerify(pedidoXml, builder.pedidoId(chaveAcesso, EventType.CANCELAMENTO), findings);
        boolean valid = findings.stream().noneMatch(f -> f.severity() == Severity.ERROR);
        return new EventResponse(valid ? EventStatus.VALIDATED : EventStatus.REJECTED_LOCALLY,
                valid, List.copyOf(findings), signed != null ? signed : pedidoXml, null);
    }

    public EventResponse cancel(String chaveAcesso, CancelEventRequest request) {
        String pedidoXml = builder.buildCancellation(chaveAcesso, request);
        String pedidoId = builder.pedidoId(chaveAcesso, EventType.CANCELAMENTO);
        List<ValidationFinding> findings = new ArrayList<>(schemaValidator.validateCollecting(pedidoXml));
        String signed = signAndVerify(pedidoXml, pedidoId, findings);
        String bestXml = signed != null ? signed : pedidoXml;

        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            return new EventResponse(
                    EventStatus.REJECTED_LOCALLY, false, List.copyOf(findings), bestXml, null);
        }

        SefinEventResult sefin;
        try {
            sefin = sefinClient.registerEvent(chaveAcesso, signed);
        } catch (Exception e) {
            findings.add(ValidationFinding.error(Stage.SEFIN, "NET001",
                    "event submission to SEFIN failed: " + e.getMessage()
                            + " — read the note's events back before retrying, the event may have registered"));
            return new EventResponse(
                    EventStatus.SUBMIT_FAILED, true, List.copyOf(findings), bestXml, null);
        }

        for (SefinMessage m : sefin.messages()) {
            findings.add(sefin.accepted()
                    ? ValidationFinding.warn(Stage.SEFIN, m.code(), messageText(m))
                    : ValidationFinding.error(Stage.SEFIN, m.code(), messageText(m)));
        }
        return new EventResponse(
                sefin.accepted() ? EventStatus.REGISTERED : EventStatus.REJECTED_BY_SEFIN,
                true, List.copyOf(findings), bestXml, sefin.eventoXml());
    }

    /** Signs infPedReg in place and self-verifies; returns the signed XML, or null when impossible. */
    private String signAndVerify(String pedidoXml, String pedidoId, List<ValidationFinding> findings) {
        Document doc;
        try {
            doc = SafeXml.documentBuilderFactory().newDocumentBuilder().parse(
                    new ByteArrayInputStream(pedidoXml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            findings.add(ValidationFinding.error(Stage.XSD, "XSD000", "unreadable pedido: " + e.getMessage()));
            return null;
        }
        try {
            signer.signEnveloped(doc, pedidoId);
        } catch (Exception e) {
            findings.add(ValidationFinding.error(Stage.SIGN, "SIG005", "signing failed: " + e.getMessage()));
            return null;
        }
        findings.addAll(verifier.verify(doc));
        return serialize(doc);
    }

    private static String messageText(SefinMessage m) {
        return m.description() + (m.complement() == null ? "" : " (" + m.complement() + ")");
    }

    private static String serialize(Document doc) {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize signed pedido: " + e.getMessage(), e);
        }
    }
}
