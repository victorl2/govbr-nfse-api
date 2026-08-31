package br.com.nfse.event;

import br.com.nfse.testsupport.DryRunTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBuilderTest {

    private static final String CHAVE = "33045572212345678000195000000000000126081270635662";

    private final EventBuilder builder =
            new EventBuilder(DryRunTestSupport.localProps(null), br.com.nfse.config.BrasiliaTime.clock());

    private static CancelEventRequest cancellation() {
        return new CancelEventRequest("12345678000195", null, "1",
                "Erro na emissao: valor do servico incorreto");
    }

    @Test
    void buildsASchemaValidCancellationRequest() {
        String xml = builder.buildCancellation(CHAVE, cancellation());
        assertEquals(List.of(), new PedRegEventoSchemaValidator().validateCollecting(xml),
                "pedido de registro de evento must pass the official XSD, got:\n" + xml);
    }

    /** Id = "PRE" + chave de acesso(50) + tipo do evento(6) — TSIdPedRegEvt. */
    @Test
    void composesThePedidoIdFromTheAccessKeyAndTheEventType() {
        assertEquals("PRE" + CHAVE + "101101", builder.pedidoId(CHAVE, EventType.CANCELAMENTO));
        assertTrue(builder.buildCancellation(CHAVE, cancellation())
                .contains("Id=\"PRE" + CHAVE + "101101\""));
    }

    /** The cancellation payload's xDesc is a fixed enumeration value in the XSD. */
    @Test
    void cancellationCarriesTheFixedDescriptionAndTheMotive() {
        String xml = builder.buildCancellation(CHAVE, cancellation());
        assertTrue(xml.contains("<e101101><xDesc>Cancelamento de NFS-e</xDesc><cMotivo>1</cMotivo>"
                + "<xMotivo>Erro na emissao: valor do servico incorreto</xMotivo></e101101>"), xml);
        assertTrue(xml.contains("<chNFSe>" + CHAVE + "</chNFSe>"), xml);
        assertTrue(xml.contains("<CNPJAutor>12345678000195</CNPJAutor>"), xml);
    }

    @Test
    void tpAmbComesFromConfigurationNotFromTheCaller() {
        assertTrue(builder.buildCancellation(CHAVE, cancellation()).contains("<tpAmb>2</tpAmb>"));
    }

    /** Like every other timestamp we write, dhEvento is horário de Brasília. */
    @Test
    void dhEventoIsHorarioDeBrasiliaWhateverZoneTheHostRunsIn() {
        EventBuilder onUtcHost = new EventBuilder(DryRunTestSupport.localProps(null),
                Clock.fixed(Instant.parse("2026-09-01T02:30:00Z"), ZoneOffset.UTC));
        assertTrue(onUtcHost.buildCancellation(CHAVE, cancellation())
                        .contains("<dhEvento>2026-08-31T23:30:00-03:00</dhEvento>"),
                onUtcHost.buildCancellation(CHAVE, cancellation()));
    }

    /** An author may also be a natural person (CPFAutor) — the XSD is a choice. */
    @Test
    void aNaturalPersonAuthorUsesCpfAutor() {
        String xml = builder.buildCancellation(CHAVE,
                new CancelEventRequest(null, "12345678901", "2", "Servico nao prestado ao cliente"));
        assertEquals(List.of(), new PedRegEventoSchemaValidator().validateCollecting(xml), xml);
        assertTrue(xml.contains("<CPFAutor>12345678901</CPFAutor>"), xml);
    }
}
