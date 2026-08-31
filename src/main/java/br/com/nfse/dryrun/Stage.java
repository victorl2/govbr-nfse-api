package br.com.nfse.dryrun;

/** The dry-run pipeline stage a finding was produced by. */
public enum Stage {
    /** Well-formedness + validation against the vendored DPS_v1.01.xsd. */
    XSD,
    /** Local business-rule lint (RTC/Simples/Rio/environment checks). */
    LINT,
    /** Signing the DPS with the configured certificate. */
    SIGN,
    /** Cryptographic self-verification of the produced signature. */
    VERIFY,
    /** Messages returned by SEFIN itself during submission (erros / alertas). */
    SEFIN
}
