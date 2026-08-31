package br.com.nfse.signing;

import br.com.nfse.certificate.CertificateLoader;
import br.com.nfse.config.Environment;
import br.com.nfse.config.NfseProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the certificate-loading + XML-signing pipeline works end to end with a
 * self-signed dummy cert — i.e. most development needs no real e-CNPJ and no
 * network (docs/08 §8.6.1).
 */
class EnvelopedXmlSignerTest {

    @TempDir
    static Path tempDir;
    static Path p12;

    @BeforeAll
    static void generateDummyCert() throws Exception {
        p12 = tempDir.resolve("dummy.p12");
        String keytool = System.getProperty("java.home") + "/bin/keytool";
        Process p = new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "nfse-dummy",
                "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=NFSE DUMMY TEST, O=NFSE, C=BR",
                "-validity", "365", "-storetype", "PKCS12",
                "-keystore", p12.toString(),
                "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true)
                .start();
        int exit = p.waitFor();
        assumeTrue(exit == 0, "keytool unavailable; skipping signing test");
    }

    private static CertificateLoader loaderFor(SignatureAlgorithm alg) {
        NfseProperties props = new NfseProperties(
                Environment.LOCAL, 2,
                "http://localhost", "http://localhost", false,
                new NfseProperties.Certificate(p12.toString(), "changeit", "PKCS12"),
                new NfseProperties.Signature(alg),
                new NfseProperties.Http(null, null));
        return new CertificateLoader(props);
    }

    private NfseProperties propsWith(SignatureAlgorithm alg) {
        return new NfseProperties(
                Environment.LOCAL, 2,
                "http://localhost", "http://localhost", false,
                new NfseProperties.Certificate(p12.toString(), "changeit", "PKCS12"),
                new NfseProperties.Signature(alg),
                new NfseProperties.Http(null, null));
    }

    @Test
    void loadsCertificateAndReportsFutureExpiry() {
        assertTrue(loaderFor(SignatureAlgorithm.RSA_SHA1).info().daysToExpiry() > 0);
    }

    @Test
    void signsInfDpsElementByIdWithSha256() throws Exception {
        CertificateLoader loader = loaderFor(SignatureAlgorithm.RSA_SHA256);
        EnvelopedXmlSigner signer = new EnvelopedXmlSigner(propsWith(SignatureAlgorithm.RSA_SHA256), loader);

        String id = "DPS330455700000000000000000000010000000000000001";
        String xml = "<DPS versao=\"1.01\" xmlns=\"http://www.sped.fazenda.gov.br/nfse\">"
                + "<infDPS Id=\"" + id + "\"><serie>1</serie></infDPS></DPS>";

        Document doc = parse(xml);
        signer.signEnveloped(doc, id);

        NodeList sig = doc.getElementsByTagNameNS(javax.xml.crypto.dsig.XMLSignature.XMLNS, "Signature");
        assertEquals(1, sig.getLength(), "exactly one Signature element expected");

        NodeList refs = doc.getElementsByTagNameNS(javax.xml.crypto.dsig.XMLSignature.XMLNS, "Reference");
        assertEquals("#" + id, refs.item(0).getAttributes().getNamedItem("URI").getNodeValue());
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
