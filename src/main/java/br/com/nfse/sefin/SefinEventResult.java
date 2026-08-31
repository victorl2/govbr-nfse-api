package br.com.nfse.sefin;

import java.util.List;

/**
 * Outcome of {@code POST /nfse/{chave}/eventos}. Accepted (HTTP 201): the decoded
 * {@code eventoXml} is set. Rejected: {@code messages} carries the erro — note the
 * eventos endpoint answers a single {@code erro} object where emission answers an
 * {@code erros} array.
 */
public record SefinEventResult(
        boolean accepted,
        String eventoXml,
        List<SefinMessage> messages
) {
}
