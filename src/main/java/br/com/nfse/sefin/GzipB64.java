package br.com.nfse.sefin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** The DF-e payload framing confirmed from the restrita Swagger: XML → gzip → Base64. */
final class GzipB64 {

    private GzipB64() {
    }

    static String encode(String xml) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                gzip.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("gzip+base64 encoding failed", e);
        }
    }

    static String decode(String gzipB64) {
        try (GZIPInputStream gzip = new GZIPInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(gzipB64)))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("gzip+base64 decoding failed", e);
        }
    }
}
