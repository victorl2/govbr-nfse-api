package br.com.nfse.signing;

import br.com.nfse.certificate.CertificateLoader;
import br.com.nfse.certificate.KeyMaterial;
import br.com.nfse.config.NfseProperties;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import java.util.List;

/**
 * Enveloped XMLDSig over the {@code infDPS} element, per docs/06 §6.2:
 * C14N canonicalization, enveloped + C14N transforms, KeyInfo carrying the
 * X509Certificate, Reference URI = "#" + infDPS@Id. The signature algorithm
 * (SHA-1 / SHA-256) comes from config.
 *
 * <p>The signing mechanics are identical regardless of who issued the cert, so
 * this is fully exercisable with a self-signed dummy in local dev.
 */
public class EnvelopedXmlSigner implements XmlSigner {

    private final NfseProperties props;
    private final CertificateLoader certificateLoader;

    public EnvelopedXmlSigner(NfseProperties props, CertificateLoader certificateLoader) {
        this.props = props;
        this.certificateLoader = certificateLoader;
    }

    @Override
    public void signEnveloped(Document document, String referenceId) {
        try {
            KeyMaterial km = certificateLoader.load();
            SignatureAlgorithm alg = props.signature().algorithmOrDefault();
            XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");

            // Make the Reference URI ("#id") resolvable by marking the Id attribute.
            markIdAttribute(document, referenceId);

            List<Transform> transforms = List.of(
                    fac.newTransform(Transform.ENVELOPED, (javax.xml.crypto.dsig.spec.TransformParameterSpec) null),
                    fac.newTransform(CanonicalizationMethod.INCLUSIVE, (javax.xml.crypto.dsig.spec.C14NMethodParameterSpec) null));

            Reference ref = fac.newReference(
                    "#" + referenceId,
                    fac.newDigestMethod(alg.digestMethodUri(), null),
                    transforms, null, null);

            SignedInfo signedInfo = fac.newSignedInfo(
                    fac.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (javax.xml.crypto.dsig.spec.C14NMethodParameterSpec) null),
                    fac.newSignatureMethod(alg.signatureMethodUri(), null),
                    List.of(ref));

            KeyInfoFactory kif = fac.getKeyInfoFactory();
            X509Data x509Data = kif.newX509Data(List.of(km.certificate()));
            KeyInfo keyInfo = kif.newKeyInfo(List.of(x509Data));

            // Signature is appended as the last child of the document root (sibling of infDPS).
            DOMSignContext context = new DOMSignContext(km.privateKey(), document.getDocumentElement());
            XMLSignature signature = fac.newXMLSignature(signedInfo, keyInfo);
            signature.sign(context);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign XML (referenceId=" + referenceId + "): " + e.getMessage(), e);
        }
    }

    private void markIdAttribute(Document document, String id) {
        Element el = findByIdAttribute(document.getDocumentElement(), id);
        if (el == null) {
            throw new IllegalArgumentException("No element with Id='" + id + "' found to sign");
        }
        el.setIdAttribute("Id", true);
    }

    private Element findByIdAttribute(Element element, String id) {
        if (id.equals(element.getAttribute("Id"))) {
            return element;
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element child) {
                Element found = findByIdAttribute(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
