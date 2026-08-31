package br.com.nfse.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The one JSON mapper the service uses. Spring Boot used to discover Jackson
 * modules on the classpath; wiring by hand means saying which ones we want —
 * java.time support, because {@code CertificateInfo} carries an {@link
 * java.time.Instant}.
 */
public final class Json {

    private Json() {
    }

    public static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // Without this an Instant serialises as a float of epoch seconds
                // ("notAfter": 1800447011.000000000), which is unreadable in a
                // health payload an operator is meant to alert on. ISO-8601 instead.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
