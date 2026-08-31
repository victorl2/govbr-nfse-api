package br.com.nfse.dps;

import br.com.nfse.dryrun.Severity;
import br.com.nfse.dryrun.Stage;
import br.com.nfse.dryrun.ValidationFinding;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates an XML string against one of the official schemas vendored under
 * {@code src/main/resources/schemas/1.01} (docs/04 §4.9). Validating locally
 * before signing/sending catches most structural errors offline and for free.
 *
 * <p>The schema set uses relative {@code xs:include}s; a classpath
 * {@link LSResourceResolver} resolves them so it also works from inside the jar.
 *
 * <p>Two official quirks are shimmed at load time, leaving the vendored XSD files
 * byte-identical to the official download:
 * <ul>
 *   <li>{@code TSSerieDPS} is declared as {@code ^0{0,4}\d{1,5}$}, but {@code ^}
 *       and {@code $} are literal characters in XML Schema regex, so a
 *       spec-compliant validator (the JDK's Xerces) rejects every real-world serie
 *       value. SEFIN's own validator treats them as anchors, so the anchors are
 *       stripped.</li>
 *   <li>{@code xmldsig-core-schema.xsd}'s DOCTYPE points at w3.org; fetching it
 *       makes schema loading depend on a throttled external host. The entities it
 *       uses live in its internal subset, so the external reference is removed.</li>
 * </ul>
 */
public abstract class XsdValidator {

    private static final String SCHEMA_DIR = "schemas/1.01/";
    private static final String QUIRKY_SCHEMA = "tiposSimples_v1.01.xsd";
    private static final String BROKEN_SERIE_PATTERN = "^0{0,4}\\d{1,5}$";
    private static final String FIXED_SERIE_PATTERN = "0{0,4}\\d{1,5}";
    private static final String XMLDSIG_SCHEMA = "xmldsig-core-schema.xsd";
    private static final String EXTERNAL_DTD_REF =
            "PUBLIC \"-//W3C//DTD XMLSchema 200102//EN\" \"http://www.w3.org/2001/XMLSchema.dtd\"";

    private final Schema schema;
    private final String documentName;

    /**
     * @param rootSchema  file name of the root XSD inside {@code schemas/1.01/}
     * @param documentName what the validated document is called, for error messages
     */
    protected XsdValidator(String rootSchema, String documentName) {
        this.documentName = documentName;
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setResourceResolver(new ClasspathResolver(SCHEMA_DIR));
            URL root = Thread.currentThread().getContextClassLoader().getResource(SCHEMA_DIR + rootSchema);
            if (root == null) {
                throw new IllegalStateException("schema not found on classpath: " + SCHEMA_DIR + rootSchema);
            }
            this.schema = factory.newSchema(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + rootSchema + ": " + e.getMessage(), e);
        }
    }

    /** Collects every schema violation (with line numbers) instead of failing on the first. */
    public List<ValidationFinding> validateCollecting(String xml) {
        List<ValidationFinding> findings = new ArrayList<>();
        try {
            Validator validator = secured(schema.newValidator());
            validator.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) { findings.add(finding(Severity.WARN, e)); }
                @Override public void error(SAXParseException e) { findings.add(finding(Severity.ERROR, e)); }
                @Override public void fatalError(SAXParseException e) throws SAXException {
                    findings.add(finding(Severity.ERROR, e));
                    throw e;
                }
            });
            validator.validate(new StreamSource(new StringReader(xml)));
        } catch (SAXException e) {
            if (findings.isEmpty()) {
                findings.add(ValidationFinding.error(Stage.XSD, "XSD000", e.getMessage()));
            }
        } catch (IOException e) {
            findings.add(ValidationFinding.error(Stage.XSD, "XSD000", e.getMessage()));
        }
        return List.copyOf(findings);
    }

    /** @throws DpsValidationException if the XML does not conform to the schema. */
    public void validate(String xml) {
        try {
            secured(schema.newValidator()).validate(new StreamSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new DpsValidationException(documentName + " failed XSD validation: " + e.getMessage(), e);
        }
    }

    /**
     * The instance document being validated is untrusted too, so the validator gets
     * the same treatment as the parser: no external DTD or schema may be fetched
     * while reading it.
     */
    private static Validator secured(Validator validator) {
        try {
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (org.xml.sax.SAXNotRecognizedException | org.xml.sax.SAXNotSupportedException e) {
            throw new IllegalStateException("XML validator cannot be secured: " + e.getMessage(), e);
        }
        return validator;
    }

    private static ValidationFinding finding(Severity severity, SAXParseException e) {
        Integer line = e.getLineNumber() > 0 ? e.getLineNumber() : null;
        return new ValidationFinding(Stage.XSD, severity, "XSD001", e.getMessage(), line);
    }

    private static final class ClasspathResolver implements LSResourceResolver {
        private final String basePath;

        private ClasspathResolver(String basePath) {
            this.basePath = basePath;
        }

        @Override
        public LSInput resolveResource(String type, String namespaceURI, String publicId,
                                       String systemId, String baseURI) {
            if (systemId == null) {
                return null;
            }
            InputStream in = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(basePath + systemId);
            if (in == null) {
                return null;
            }
            if (QUIRKY_SCHEMA.equals(systemId)) {
                in = patched(in, systemId, BROKEN_SERIE_PATTERN, FIXED_SERIE_PATTERN);
            } else if (XMLDSIG_SCHEMA.equals(systemId)) {
                in = patched(in, systemId, EXTERNAL_DTD_REF, "");
            }
            return new ClasspathLSInput(publicId, systemId, in);
        }

        private static InputStream patched(InputStream in, String systemId, String find, String replace) {
            try (in) {
                String xsd = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace(find, replace);
                return new ByteArrayInputStream(xsd.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read " + systemId + ": " + e.getMessage(), e);
            }
        }
    }
}
