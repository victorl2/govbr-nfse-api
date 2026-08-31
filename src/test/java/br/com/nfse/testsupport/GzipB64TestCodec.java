package br.com.nfse.testsupport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

/** Test-side gzip+base64 encoder for mocking SEFIN envelopes. */
public final class GzipB64TestCodec {

    private GzipB64TestCodec() {
    }

    public static String encode(String xml) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                gzip.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
