package br.com.nfse.sefin;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GzipB64Test {

    @Test
    void roundTripsXml() {
        String xml = "<DPS versao=\"1.01\"><infDPS>conteúdo açêntos</infDPS></DPS>";
        assertEquals(xml, GzipB64.decode(GzipB64.encode(xml)));
    }

    @Test
    void encodingIsRealGzipInsideBase64() throws Exception {
        String xml = "<NFSe/>";
        byte[] gzipped = Base64.getDecoder().decode(GzipB64.encode(xml));
        String inflated = new String(
                new GZIPInputStream(new ByteArrayInputStream(gzipped)).readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals(xml, inflated);
    }
}
