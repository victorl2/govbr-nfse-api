package br.com.nfse.dryrun;

import java.util.List;

/** Outcome of a validation dry-run. {@code valid} means no ERROR-severity finding. */
public record DryRunReport(boolean valid, List<ValidationFinding> findings) {

    public static DryRunReport of(List<ValidationFinding> findings) {
        boolean valid = findings.stream().noneMatch(f -> f.severity() == Severity.ERROR);
        return new DryRunReport(valid, List.copyOf(findings));
    }
}
