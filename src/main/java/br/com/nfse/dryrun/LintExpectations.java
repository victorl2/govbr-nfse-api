package br.com.nfse.dryrun;

/**
 * Caller-supplied expectations for a dry-run: what THIS emitter's DPS should
 * look like. Every field is optional — an unset field disables its check, so the
 * service itself stays company-neutral.
 */
public record LintExpectations(String expectedMunicipality, String expectedOpSimpNac) {

    public static LintExpectations none() {
        return new LintExpectations(null, null);
    }

    public boolean municipalityConfigured() {
        return expectedMunicipality != null && !expectedMunicipality.isBlank();
    }

    public boolean opSimpNacConfigured() {
        return expectedOpSimpNac != null && !expectedOpSimpNac.isBlank();
    }

    /** True when the expected situation is a Simples Nacional optante (2 = MEI, 3 = ME/EPP). */
    public boolean simplesOptante() {
        return "2".equals(expectedOpSimpNac) || "3".equals(expectedOpSimpNac);
    }
}
