package br.com.nfse.emission;

import br.com.nfse.dryrun.ValidationFinding;

import java.util.List;

/**
 * Result of {@code POST /nfse/send}. {@code dpsXml} is the signed document when
 * signing succeeded (otherwise the unsigned build); on {@code AUTHORIZED},
 * {@code chaveAcesso} and the decoded {@code nfseXml} come from SEFIN.
 */
public record SendNfseResponse(
        EmissionStatus status,
        boolean valid,
        List<ValidationFinding> findings,
        String dpsXml,
        String chaveAcesso,
        String nfseXml
) {
}
