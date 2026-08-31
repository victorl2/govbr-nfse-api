package br.com.nfse.dryrun;

/**
 * Full dry-run result: the report plus the signed DPS produced along the way
 * ({@code signedDpsXml} is null when signing was not reached or failed).
 */
public record DryRunOutcome(DryRunReport report, String signedDpsXml) {
}
