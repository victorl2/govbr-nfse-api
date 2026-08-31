package br.com.nfse.config;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * A {@link DocumentBuilderFactory} that is safe to point at untrusted XML.
 *
 * <p>JAXP's defaults resolve external entities, so a document can name a local
 * file (its content is then parsed into the tree and can be rendered or echoed
 * back), or a URL (turning the service into an SSRF proxy), and nested entity
 * definitions can be expanded until the heap is exhausted. Both matter here:
 * {@code POST /nfse/danfse} and {@code POST /internal/dry-run} parse XML the
 * caller supplies.
 *
 * <p>Disallowing DOCTYPE declarations outright closes all of it in one rule —
 * and costs nothing, because none of the NFS-e layouts uses a DTD. The document
 * is rejected with a clear message rather than silently sanitised.
 */
public final class SafeXml {

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";

    private SafeXml() {
    }

    /** A namespace-aware factory with DTDs and external entities switched off. */
    public static DocumentBuilderFactory documentBuilderFactory() {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        try {
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature(DISALLOW_DOCTYPE, true);
            dbf.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            dbf.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (ParserConfigurationException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "XML parser cannot be secured on this platform: " + e.getMessage(), e);
        }
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        dbf.setNamespaceAware(true);
        return dbf;
    }
}
