package br.com.nfse.danfse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /nfse/danfse} renders a PDF from XML the caller supplies, so its
 * parser reads untrusted input. A parser left at JAXP's defaults resolves
 * external entities: it would read local files into the rendered document and
 * make outbound requests to any URL the document names (SSRF), and expand
 * nested entities until the heap is gone.
 */
class DanfseXxeTest {

    @TempDir
    Path tempDir;

    private final DanfseGenerator generator = new DanfseGenerator();

    private static String sampleNfse() throws Exception {
        try (InputStream in = DanfseXxeTest.class.getResourceAsStream("/dps/nfse-export-sample.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void anExternalEntityIsNeverResolvedIntoTheRenderedPdf() throws Exception {
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-CONTENT");
        String attack = sampleNfse().replace("<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE NFSe [<!ENTITY xxe SYSTEM \""
                        + secret.toUri() + "\">]>")
                .replace("<xDescServ>", "<xDescServ>&xxe;");
        assertTrue(attack.contains("<!DOCTYPE"), "the test payload must actually carry a DOCTYPE");

        // Rejecting the document outright is the desired behaviour; what must never
        // happen is a PDF that quietly contains the file's content. The content
        // stream is compressed, so the text has to be extracted, not grepped.
        try {
            byte[] pdf = generator.generate(attack);
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
                assertFalse(text.contains("TOP-SECRET"), "the external entity was resolved into the PDF");
            }
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage() != null && expected.getMessage().toLowerCase().contains("doctype"),
                    "expected the DOCTYPE to be refused, got: " + expected.getMessage());
        }
    }

    /** A "billion laughs" body must be refused, not expanded until the heap dies. */
    @Test
    void anEntityExpansionBombIsRefused() {
        String bomb = """
                <?xml version="1.0"?>
                <!DOCTYPE NFSe [
                 <!ENTITY a "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa">
                 <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
                 <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
                 <!ENTITY d "&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;">
                 <!ENTITY e "&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;">
                ]>
                <NFSe xmlns="http://www.sped.fazenda.gov.br/nfse" versao="1.01"><infNFSe Id="x">&e;</infNFSe></NFSe>
                """;
        assertThrows(RuntimeException.class, () -> generator.generate(bomb));
    }
}
