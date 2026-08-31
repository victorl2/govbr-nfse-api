package br.com.nfse.store;

import br.com.nfse.http.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An authorised NFS-e must outlive the HTTP response that carried it. Before
 * this store the only copy of a note's access key was whatever the caller did
 * with the JSON body — lose that and the document is findable only by walking
 * the ADN feed, despite being legally ours to keep for five years.
 */
class EmissionStoreTest {

    private static final String DPS_ID = "DPS330455721234567800019500000000000012608127";
    private static final String CHAVE = "33045572212345678000195000000000000126081270635662";

    @TempDir
    Path dir;

    private EmissionStore store() {
        return new EmissionStore(dir, Json.mapper());
    }

    @Test
    void recordsASubmissionAndReadsItBack() {
        EmissionStore store = store();

        store.begin(DPS_ID, "1", "19", "<DPS>signed</DPS>", "2026-08-28T20:00:00-03:00");

        EmissionRecord record = store.byDpsId(DPS_ID).orElseThrow();
        assertEquals(DPS_ID, record.dpsId());
        assertEquals("1", record.serie());
        assertEquals("19", record.number());
        assertEquals(EmissionRecord.Status.SUBMITTED, record.status());
        assertEquals("<DPS>signed</DPS>", store.dpsXml(DPS_ID).orElseThrow());
    }

    /**
     * The claim is the idempotency guard: the same (CNPJ, série, número) yields
     * the same dpsId, so a retry must be recognised rather than re-sent.
     */
    @Test
    void refusesToClaimTheSameDpsIdTwice() {
        EmissionStore store = store();
        store.begin(DPS_ID, "1", "19", "<DPS/>", "2026-08-28T20:00:00-03:00");

        assertThrows(EmissionStore.DuplicateEmission.class,
                () -> store.begin(DPS_ID, "1", "19", "<DPS/>", "2026-08-28T20:00:01-03:00"));
    }

    @Test
    void promotesToAuthorizedAndBecomesFindableByAccessKey() {
        EmissionStore store = store();
        store.begin(DPS_ID, "1", "19", "<DPS/>", "2026-08-28T20:00:00-03:00");

        store.authorized(DPS_ID, CHAVE, "<NFSe>authorized</NFSe>", "2026-08-28T20:00:02-03:00");

        EmissionRecord record = store.byChave(CHAVE).orElseThrow();
        assertEquals(DPS_ID, record.dpsId());
        assertEquals(EmissionRecord.Status.AUTHORIZED, record.status());
        assertEquals(CHAVE, record.chaveAcesso());
        assertEquals("<NFSe>authorized</NFSe>", store.nfseXml(DPS_ID).orElseThrow());
    }

    @Test
    void recordsARejectionWithItsReason() {
        EmissionStore store = store();
        store.begin(DPS_ID, "1", "19", "<DPS/>", "2026-08-28T20:00:00-03:00");

        store.finished(DPS_ID, EmissionRecord.Status.REJECTED_BY_SEFIN,
                "E0312 cTribMun ausente", "2026-08-28T20:00:02-03:00");

        EmissionRecord record = store.byDpsId(DPS_ID).orElseThrow();
        assertEquals(EmissionRecord.Status.REJECTED_BY_SEFIN, record.status());
        assertTrue(record.detail().contains("E0312"), record.detail());
    }

    /** The whole point: the record must be there after the process dies. */
    @Test
    void survivesARestart() {
        store().begin(DPS_ID, "1", "19", "<DPS>persisted</DPS>", "2026-08-28T20:00:00-03:00");
        store().authorized(DPS_ID, CHAVE, "<NFSe/>", "2026-08-28T20:00:02-03:00");

        EmissionStore reopened = new EmissionStore(dir, Json.mapper());
        assertEquals(EmissionRecord.Status.AUTHORIZED, reopened.byDpsId(DPS_ID).orElseThrow().status());
        assertEquals(DPS_ID, reopened.byChave(CHAVE).orElseThrow().dpsId());
        assertEquals("<DPS>persisted</DPS>", reopened.dpsXml(DPS_ID).orElseThrow());
    }

    @Test
    void returnsEmptyForUnknownIdentifiers() {
        EmissionStore store = store();

        assertEquals(Optional.empty(), store.byDpsId(DPS_ID));
        assertEquals(Optional.empty(), store.byChave(CHAVE));
        assertEquals(Optional.empty(), store.dpsXml(DPS_ID));
    }

    /** Identifiers become path segments, so anything path-like has to be refused. */
    @Test
    void rejectsIdentifiersThatCouldEscapeTheDirectory() {
        EmissionStore store = store();

        assertThrows(IllegalArgumentException.class,
                () -> store.begin("../../etc/passwd", "1", "19", "<DPS/>", "now"));
        assertThrows(IllegalArgumentException.class, () -> store.byChave("../secrets"));
    }

    /** A submission that provably never arrived must be retryable on the same número. */
    @Test
    void discardingAnUnsentClaimFreesTheDpsIdAgain() {
        EmissionStore store = store();
        store.begin(DPS_ID, "1", "19", "<DPS/>", "2026-08-28T20:00:00-03:00");

        store.discard(DPS_ID);

        assertEquals(Optional.empty(), store.byDpsId(DPS_ID));
        store.begin(DPS_ID, "1", "19", "<DPS/>", "2026-08-28T20:00:10-03:00");  // must not throw
    }

    /** Discarding an authorised note would let a duplicate be issued for it. */
    @Test
    void refusesToDiscardAnAuthorizedEmission() {
        EmissionStore store = store();
        store.begin(DPS_ID, "1", "19", "<DPS/>", "2026-08-28T20:00:00-03:00");
        store.authorized(DPS_ID, CHAVE, "<NFSe/>", "2026-08-28T20:00:02-03:00");

        assertThrows(IllegalStateException.class, () -> store.discard(DPS_ID));
        assertTrue(store.byDpsId(DPS_ID).isPresent());
    }

    @Test
    void listsRecentEmissionsNewestFirst() {
        EmissionStore store = store();
        store.begin(DPS_ID + "A", "1", "19", "<DPS/>", "2026-08-28T20:00:00-03:00");
        store.begin(DPS_ID + "B", "1", "20", "<DPS/>", "2026-08-28T20:00:05-03:00");
        store.begin(DPS_ID + "C", "1", "21", "<DPS/>", "2026-08-28T20:00:09-03:00");

        var recent = store.recent(2);

        assertEquals(2, recent.size());
        assertEquals(DPS_ID + "C", recent.get(0).dpsId());
        assertEquals(DPS_ID + "B", recent.get(1).dpsId());
    }
}
