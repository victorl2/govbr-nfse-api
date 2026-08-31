package br.com.nfse.event;

import br.com.nfse.dps.XsdValidator;

/**
 * Validates a Pedido de Registro de Evento against the official
 * {@code pedRegEvento_v1.01.xsd}. Catches offline what SEFIN would otherwise
 * reject — notably {@code xMotivo}'s 15-character minimum and the fixed
 * {@code xDesc} enumeration of each event type.
 */
public class PedRegEventoSchemaValidator extends XsdValidator {

    public PedRegEventoSchemaValidator() {
        super("pedRegEvento_v1.01.xsd", "pedido de registro de evento");
    }
}
