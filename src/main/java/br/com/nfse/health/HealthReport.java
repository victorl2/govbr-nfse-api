package br.com.nfse.health;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/**
 * What {@code GET /health} answers.
 *
 * <p>{@code status} stays the first field and keeps the values {@code UP} /
 * {@code DOWN}, because the container's {@code HEALTHCHECK} greps for
 * {@code "status":"UP"}.
 */
public record HealthReport(
        String status,
        Certificate certificate,
        List<String> warnings,
        @JsonIgnore int httpStatus
) {

    /** Non-sensitive certificate facts: enough to alert on, nothing identifying. */
    public record Certificate(Instant notAfter, long daysToExpiry) {
    }

    public static HealthReport up(Certificate certificate, List<String> warnings) {
        return new HealthReport("UP", certificate, warnings, 200);
    }

    public static HealthReport down(Certificate certificate, List<String> warnings) {
        return new HealthReport("DOWN", certificate, warnings, 503);
    }
}
