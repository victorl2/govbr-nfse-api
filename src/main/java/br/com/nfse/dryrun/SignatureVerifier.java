package br.com.nfse.dryrun;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.crypto.AlgorithmMethod;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import java.security.Key;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Cryptographic self-verification of the enveloped XMLDSig on a signed DPS,
 * using the certificate embedded in the signature's own KeyInfo — the same way
 * SEFIN will verify it (docs/06 §6.3). Trust-chain checks are out of scope here;
 * this proves digests and signature value are consistent with the document.
 */
public class SignatureVerifier {

    public List<ValidationFinding> verify(Document signedDps) {
        NodeList signatures = signedDps.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (signatures.getLength() == 0) {
            return List.of(ValidationFinding.error(Stage.VERIFY, "SIG001",
                    "document carries no XML signature"));
        }
        try {
            markIdAttributes(signedDps.getDocumentElement());
            DOMValidateContext context = new DOMValidateContext(new EmbeddedCertKeySelector(), signatures.item(0));
            XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            if (!signature.validate(context)) {
                return List.of(ValidationFinding.error(Stage.VERIFY, "SIG002",
                        "signature failed cryptographic validation — the document was altered after"
                                + " signing, or the wrong element was referenced"));
            }
            return List.of();
        } catch (Exception e) {
            return List.of(ValidationFinding.error(Stage.VERIFY, "SIG003",
                    "signature verification error: " + e.getMessage()));
        }
    }

    /** Makes every {@code Id} attribute an XML ID so the Reference URI ("#id") resolves. */
    private static void markIdAttributes(Element element) {
        if (!element.getAttribute("Id").isEmpty()) {
            element.setIdAttribute("Id", true);
        }
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) {
                markIdAttributes(child);
            }
        }
    }

    /** Selects the public key of the X509 certificate embedded in the signature's KeyInfo. */
    private static final class EmbeddedCertKeySelector extends KeySelector {
        @Override
        public KeySelectorResult select(KeyInfo keyInfo, Purpose purpose, AlgorithmMethod method,
                                        XMLCryptoContext context) throws KeySelectorException {
            if (keyInfo != null) {
                for (Object content : keyInfo.getContent()) {
                    if (content instanceof X509Data x509Data) {
                        for (Object item : x509Data.getContent()) {
                            if (item instanceof X509Certificate cert) {
                                Key key = cert.getPublicKey();
                                return () -> key;
                            }
                        }
                    }
                }
            }
            throw new KeySelectorException("KeyInfo carries no X509 certificate to verify with");
        }
    }
}
