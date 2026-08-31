package br.com.nfse.dryrun;

/**
 * One problem (or note) found during a dry-run. {@code line} is only set for
 * XSD findings, where the parser knows the position in the submitted XML.
 */
public record ValidationFinding(Stage stage, Severity severity, String code, String message, Integer line) {

    public static ValidationFinding error(Stage stage, String code, String message) {
        return new ValidationFinding(stage, Severity.ERROR, code, message, null);
    }

    public static ValidationFinding warn(Stage stage, String code, String message) {
        return new ValidationFinding(stage, Severity.WARN, code, message, null);
    }

    public static ValidationFinding info(Stage stage, String code, String message) {
        return new ValidationFinding(stage, Severity.INFO, code, message, null);
    }
}
