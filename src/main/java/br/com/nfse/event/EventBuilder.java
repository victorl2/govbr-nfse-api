package br.com.nfse.event;

import br.com.nfse.config.BrasiliaTime;
import br.com.nfse.config.NfseProperties;
import br.com.nfse.config.SafeXml;
import br.com.nfse.config.EmitterSoftware;
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
 * Builds the <em>Pedido de Registro de Evento</em> XML (layout v1.01) that
 * {@code POST /nfse/{chave}/eventos} expects. Like the DPS, {@code tpAmb} comes
 * from the active environment and never from the caller, and {@code dhEvento} is
 * horário de Brasília.
 *
 * <p>The {@code infPedReg@Id} follows TSIdPedRegEvt: {@code "PRE"} + the 50-digit
 * access key + the 6-digit event type. The 3-digit sequence that completes the
 * generated <em>Evento</em>'s own id (TSIdEvento) is assigned by SEFIN, not by us.
 */
public class EventBuilder {

    private static final String NS = "http://www.sped.fazenda.gov.br/nfse";

    private final NfseProperties props;
    private final Clock clock;

    public EventBuilder(NfseProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /** {@code "PRE" + chave(50) + tipoEvento(6)} — TSIdPedRegEvt. */
    public String pedidoId(String chaveAcesso, EventType type) {
        return "PRE" + chaveAcesso + type.code();
    }

    public String buildCancellation(String chaveAcesso, CancelEventRequest request) {
        return build(chaveAcesso, EventType.CANCELAMENTO, request.cnpjAutor(), request.cpfAutor(),
                (doc, payload) -> {
                    text(doc, payload, "xDesc", EventType.CANCELAMENTO.description());
                    text(doc, payload, "cMotivo", request.cMotivo());
                    text(doc, payload, "xMotivo", request.xMotivo());
                });
    }

    private String build(String chaveAcesso, EventType type, String cnpjAutor, String cpfAutor,
                         PayloadWriter payloadWriter) {
        try {
            Document doc = SafeXml.documentBuilderFactory().newDocumentBuilder().newDocument();
            Element pedido = doc.createElementNS(NS, "pedRegEvento");
            pedido.setAttribute("versao", "1.01");
            doc.appendChild(pedido);

            Element infPedReg = child(doc, pedido, "infPedReg");
            infPedReg.setAttribute("Id", pedidoId(chaveAcesso, type));

            text(doc, infPedReg, "tpAmb", String.valueOf(props.tpAmb()));
            text(doc, infPedReg, "verAplic", EmitterSoftware.VER_APLIC);
            text(doc, infPedReg, "dhEvento", BrasiliaTime.now(clock));
            if (cnpjAutor != null) {
                text(doc, infPedReg, "CNPJAutor", cnpjAutor);
            } else {
                text(doc, infPedReg, "CPFAutor", cpfAutor);
            }
            text(doc, infPedReg, "chNFSe", chaveAcesso);

            payloadWriter.write(doc, child(doc, infPedReg, type.element()));
            return serialize(doc);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build pedido de registro de evento: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface PayloadWriter {
        void write(Document doc, Element payload);
    }

    private static Element child(Document doc, Element parent, String name) {
        Element el = doc.createElementNS(NS, name);
        parent.appendChild(el);
        return el;
    }

    private static void text(Document doc, Element parent, String name, String value) {
        child(doc, parent, name).setTextContent(value);
    }

    private static String serialize(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter out = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toString();
    }
}
