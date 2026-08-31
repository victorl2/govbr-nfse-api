package br.com.nfse.store;

import br.com.nfse.config.BrasiliaTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O infDPS@Id não carrega tpAmb — o layout não prevê — então restrita e produção
 * geram o MESMO dpsId para a mesma (série, número). O guarda de idempotência é o
 * registro local, e ele responde por dpsId.
 *
 * <p>Se os dois ambientes dividissem o mesmo diretório, uma emissão em produção
 * esbarraria no registro de restrita e voltaria AUTHORIZED com a chave de
 * restrita, sem emitir nada: sucesso falso, exibindo como nota real uma chave
 * sem valor legal. Por isso o estado é separado por ambiente, e é isso que estes
 * testes prendem.
 */
class EnvironmentIsolationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** O mesmo id que restrita e produção produzem para a mesma (série, número). */
    private static final String DPS_ID = "DPS330455722123456780001950700000000000000000001";

    private static Path env(Path dataDir, String environment) {
        return dataDir.resolve(environment.toLowerCase(Locale.ROOT));
    }

    @Test
    void producaoDoesNotSeeARestritaEmission(@TempDir Path dataDir) throws Exception {
        EmissionStore restrita = new EmissionStore(env(dataDir, "RESTRITA"), JSON);
        EmissionStore producao = new EmissionStore(env(dataDir, "PRODUCAO"), JSON);

        restrita.begin(DPS_ID, "70000", "1", "<DPS/>", BrasiliaTime.now());
        restrita.authorized(DPS_ID, "chave-de-restrita", "<NFSe/>", BrasiliaTime.now());

        // Em produção o mesmo dpsId tem de estar livre: nada foi emitido lá.
        assertTrue(producao.byDpsId(DPS_ID).isEmpty(),
                "produção enxergou um registro de restrita");
        producao.begin(DPS_ID, "70000", "1", "<DPS/>", BrasiliaTime.now());
        producao.authorized(DPS_ID, "chave-de-producao", "<NFSe/>", BrasiliaTime.now());

        assertEquals("chave-de-restrita", restrita.byDpsId(DPS_ID).orElseThrow().chaveAcesso());
        assertEquals("chave-de-producao", producao.byDpsId(DPS_ID).orElseThrow().chaveAcesso());
    }

    /** Dentro de um mesmo ambiente o guarda continua valendo, que é o objetivo dele. */
    @Test
    void theGuardStillWorksWithinOneEnvironment(@TempDir Path dataDir) throws Exception {
        EmissionStore store = new EmissionStore(env(dataDir, "RESTRITA"), JSON);
        store.begin(DPS_ID, "70000", "1", "<DPS/>", BrasiliaTime.now());

        assertThrows(EmissionStore.DuplicateEmission.class,
                () -> store.begin(DPS_ID, "70000", "1", "<DPS/>", BrasiliaTime.now()));
    }

    /** A numeração de produção começa limpa, sem herdar a sequência de restrita. */
    @Test
    void numberingDoesNotCarryOverBetweenEnvironments(@TempDir Path dataDir) throws Exception {
        NumberingStore restrita = new NumberingStore(env(dataDir, "RESTRITA"));
        NumberingStore producao = new NumberingStore(env(dataDir, "PRODUCAO"));

        long ultimo = 0;
        for (int i = 0; i < 9; i++) {
            ultimo = restrita.next("70000");
        }
        assertEquals(9, ultimo, "restrita deveria ter consumido 1..9");

        assertEquals(1, producao.next("70000"),
                "produção herdou a numeração de restrita");
    }
}
