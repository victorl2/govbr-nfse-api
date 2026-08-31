package br.com.nfse.testsupport;

import br.com.nfse.adn.AdnClient;
import org.springframework.web.client.RestClient;
import br.com.nfse.certificate.CertificateLoader;
import br.com.nfse.config.BrasiliaTime;
import br.com.nfse.config.Environment;
import br.com.nfse.config.NfseProperties;
import br.com.nfse.signing.SignatureAlgorithm;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Shared helpers for the dry-run test suite: dummy cert, properties, fixture, parsing. */
public final class DryRunTestSupport {

    private DryRunTestSupport() {
    }

    /** Generates a self-signed dummy PKCS12 at {@code dir/dummy.p12}; skips the test if keytool is unavailable. */
    public static Path generateDummyCert(Path dir) throws Exception {
        return generateCert(dir, "dummy.p12", null, 365);
    }

    /**
     * Generates a dummy PKCS12 with a chosen lifetime, so expiry behaviour can be
     * tested against a real certificate rather than a stubbed date.
     *
     * @param startOffset keytool {@code -startdate} offset (e.g. {@code "-400d"}),
     *                    or null for "now"; combined with {@code validityDays} this
     *                    produces certificates that are already expired or nearly so
     */
    public static Path generateCert(Path dir, String name, String startOffset, int validityDays)
            throws Exception {
        Path p12 = dir.resolve(name);
        String keytool = System.getProperty("java.home") + "/bin/keytool";
        List<String> command = new ArrayList<>(List.of(
                keytool, "-genkeypair", "-alias", "nfse-dummy",
                "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=NFSE DUMMY TEST, O=NFSE, C=BR",
                "-validity", String.valueOf(validityDays), "-storetype", "PKCS12",
                "-keystore", p12.toString(),
                "-storepass", "changeit", "-keypass", "changeit"));
        if (startOffset != null) {
            command.addAll(List.of("-startdate", startOffset));
        }
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        assumeTrue(p.waitFor() == 0, "keytool unavailable; skipping signing-dependent test");
        return p12;
    }

    /** Stores rooted in a throwaway directory, for tests that do not assert on them. */
    public static br.com.nfse.store.NumberingStore numbering() {
        return new br.com.nfse.store.NumberingStore(scratch());
    }

    public static br.com.nfse.store.EmissionStore emissions() {
        return new br.com.nfse.store.EmissionStore(scratch(), br.com.nfse.http.Json.mapper());
    }

    private static Path scratch() {
        try {
            return java.nio.file.Files.createTempDirectory("nfse-test-store");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A render gate wide enough that tests never queue on it. */
    public static br.com.nfse.http.ConcurrencyGate renderGate() {
        return new br.com.nfse.http.ConcurrencyGate(8, java.time.Duration.ofSeconds(10));
    }

    public static NfseProperties localProps(Path p12) {
        return new NfseProperties(
                Environment.LOCAL, 2,
                "http://localhost", "http://localhost", false,
                new NfseProperties.Certificate(p12 == null ? "unused" : p12.toString(), "changeit", "PKCS12"),
                new NfseProperties.Signature(SignatureAlgorithm.RSA_SHA256),
                new NfseProperties.Http(null, null));
    }

    /** The expectations matching the fixture: Rio de Janeiro, Simples ME/EPP. */
    public static br.com.nfse.dryrun.LintExpectations rioSimples() {
        return new br.com.nfse.dryrun.LintExpectations("3304557", "3");
    }

    /** A JSON emission request mirroring the /dps/dps-valid.xml fixture exactly. */
    public static br.com.nfse.emission.EmitNfseRequest sampleRequest() {
        return new br.com.nfse.emission.EmitNfseRequest(
                new br.com.nfse.emission.EmitNfseRequest.Emitter(
                        "12345678000195", null, "3304557", "3", "1", "0"),
                new br.com.nfse.emission.EmitNfseRequest.Dps(
                        "00001", "1", "2026-08-28T12:00:00-03:00", "2026-08-28"),
                new br.com.nfse.emission.EmitNfseRequest.Service(
                        "010701", null, "Desenvolvimento de software sob encomenda", "115022000", "3304557"),
                new br.com.nfse.emission.EmitNfseRequest.Values(
                        "1000.00", "1", null, "1", "6.00"),
                new br.com.nfse.emission.EmitNfseRequest.IbsCbs(
                        "0", "000001", "0", "000", "000001", null),
                null, null, null);
    }

    /**
     * A service-export request mirroring the DPS that SEFIN restrita ACCEPTED on
     * 2026-08-28 (/dps/dps-export-accepted.xml): Simples ME/EPP, Rio, foreign
     * tomador, no IBS/CBS group, ISSQN as Exportação de Serviço.
     */
    public static br.com.nfse.emission.EmitNfseRequest exportRequest() {
        return new br.com.nfse.emission.EmitNfseRequest(
                new br.com.nfse.emission.EmitNfseRequest.Emitter(
                        "12345678000195", null, "3304557", "3", "1", "0"),
                new br.com.nfse.emission.EmitNfseRequest.Dps(
                        "1", "7", "2026-08-28T12:00:00-03:00", "2026-08-28"),
                new br.com.nfse.emission.EmitNfseRequest.Service(
                        "010401", "003", "Desenvolvimento de software sob encomenda para cliente no exterior",
                        "115022000", "3304557"),
                new br.com.nfse.emission.EmitNfseRequest.Values(
                        "10000.00", "3", "US", "1", "6.00"),
                null,
                new br.com.nfse.emission.EmitNfseRequest.Tomador(
                        null, null, "ACME SOFTWARE CLIENT LLC", null, "1",
                        new br.com.nfse.emission.EmitNfseRequest.EnderecoExterior(
                                "US", "10001", "New York", "NY", "100 Main Street", "100", null, "Downtown")),
                null,
                new br.com.nfse.emission.EmitNfseRequest.ComercioExterior(
                        "1", "0", "220", "2000.00", null, null, null, null));
    }

    public static CertificateLoader loader(Path p12) {
        return new CertificateLoader(localProps(p12));
    }

    /** A builder on the real Brasília clock — the wiring the application uses. */
    public static br.com.nfse.emission.DpsBuilder builder(Path p12) {
        return new br.com.nfse.emission.DpsBuilder(localProps(p12), BrasiliaTime.clock());
    }

    /** The schema-valid Simples/Rio DPS fixture. */
    public static String validDps() {
        try (InputStream in = DryRunTestSupport.class.getResourceAsStream("/dps/dps-valid.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture /dps/dps-valid.xml missing", e);
        }
    }

    public static Document parse(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("test XML failed to parse: " + e.getMessage(), e);
        }
    }

    /**
     * ADN inerte. Estes testes não exercitam a distribuição; apontar para uma
     * porta morta faz qualquer uso acidental falhar de forma legível, em vez de
     * estourar um NullPointerException sem explicação.
     */
    public static AdnClient adn() {
        return new AdnClient(RestClient.builder().baseUrl("http://localhost:1").build());
    }
}

