package br.com.nfse.event;

import br.com.nfse.dryrun.ValidationFinding;

import java.util.List;

/**
 * Result of registering an event. {@code pedidoXml} is the signed pedido we sent
 * (or the unsigned build, when signing failed); on {@code REGISTERED},
 * {@code eventoXml} is the Evento document SEFIN generated.
 */
public record EventResponse(
        EventStatus status,
        boolean valid,
        List<ValidationFinding> findings,
        String pedidoXml,
        String eventoXml
) {
}
