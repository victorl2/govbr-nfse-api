package br.com.nfse.emission;

/** Outcome of {@code POST /nfse/send}. */
public enum EmissionStatus {
    /** SEFIN accepted the DPS and generated the NFS-e ({@code chaveAcesso} + {@code nfseXml} set). */
    AUTHORIZED,
    /** The DPS failed local validation and was never sent to SEFIN. */
    REJECTED_LOCALLY,
    /** SEFIN refused the DPS — its erros are surfaced as SEFIN-stage findings. */
    REJECTED_BY_SEFIN,
    /**
     * The submission could not complete, AND no NFS-e was found for this DPS id
     * afterwards (or the lookup itself failed — see the findings). Retrying with a
     * new número risks a duplicate whenever the findings say the lookup failed.
     */
    SUBMIT_FAILED
}
