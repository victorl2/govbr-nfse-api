package br.com.nfse.emission;

import br.com.nfse.dryrun.ValidationFinding;

import java.util.List;

/** Result of {@code POST /nfse/validate}: the dry-run verdict plus the DPS that was built. */
public record ValidateNfseResponse(boolean valid, List<ValidationFinding> findings, String dpsXml) {
}
