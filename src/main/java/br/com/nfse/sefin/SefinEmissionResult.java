package br.com.nfse.sefin;

import java.util.List;

/**
 * Outcome of {@code POST /nfse}. Accepted (HTTP 201): {@code chaveAcesso} and the
 * decoded {@code nfseXml} are set, {@code messages} carries any alertas. Rejected
 * (HTTP 400/403/500 with a parseable body): {@code messages} carries the erros.
 */
public record SefinEmissionResult(
        boolean accepted,
        String chaveAcesso,
        String nfseXml,
        List<SefinMessage> messages
) {
}
