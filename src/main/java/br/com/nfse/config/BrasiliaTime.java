package br.com.nfse.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Horário de Brasília — the civil time of every timestamp on a Brazilian fiscal
 * document. An NFS-e is a Brazilian document, so {@code dhEmi}, {@code dCompet}
 * and {@code dhEvento} are always Brazilian local time, whatever zone the host
 * happens to run in.
 *
 * <p>This matters concretely: a Linux container defaults to UTC, and at 02:30 UTC
 * on the first of a month it is still 23:30 of the previous day in Brasília — a
 * host-zone timestamp would book the note into the <em>next month's</em>
 * competência and record an emission time three hours off.
 *
 * <p>Brazil abolished daylight saving in 2019, so the offset is -03:00 all year;
 * the zone (not a fixed offset) is used anyway so historic dates and any future
 * change stay correct.
 */
public final class BrasiliaTime {

    /** America/Sao_Paulo — the reference zone for horário de Brasília. */
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    /** The layout's TSDateTimeUTC accepts only numeric whole-hour offsets, never 'Z'. */
    public static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private BrasiliaTime() {
    }

    /** A system clock pinned to Brasília — the {@code Clock} bean the services get. */
    public static Clock clock() {
        return Clock.system(ZONE);
    }

    /** Now, in Brasília, truncated to seconds and formatted for the layout. */
    public static String now(Clock clock) {
        return OffsetDateTime.now(clock.withZone(ZONE)).withNano(0).format(TIMESTAMP);
    }

    /**
     * Now, in Brasília, for audit timestamps that are not part of a signed
     * document — a stored record's created/updated time. Document timestamps go
     * through {@link #now(Clock)} with the injected clock, so tests can pin them.
     *
     * <p>Millisecond precision, unlike the layout's whole seconds: two notes
     * issued in the same second must still sort in the order they happened.
     * The format stays fixed-width ISO, so lexicographic order is chronological.
     */
    public static String now() {
        return OffsetDateTime.now(clock().withZone(ZONE))
                .truncatedTo(ChronoUnit.MILLIS)
                .format(AUDIT_TIMESTAMP);
    }

    private static final DateTimeFormatter AUDIT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    /** Today's date in Brasília — the competência a note emitted "now" belongs to. */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /**
     * Re-expresses an offset-carrying ISO timestamp in Brasília time. Values that
     * already carry -03:00 (everything SEFIN sends) come back unchanged; anything
     * else is converted rather than read at face value. Unparseable input is
     * returned as-is — rendering a document is never worth an exception.
     */
    public static String toBrasilia(String isoTimestamp) {
        try {
            return OffsetDateTime.parse(isoTimestamp).atZoneSameInstant(ZONE).format(TIMESTAMP);
        } catch (RuntimeException e) {
            return isoTimestamp;
        }
    }
}
