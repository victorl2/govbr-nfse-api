package br.com.nfse.event;

/** Outcome of registering an event on an existing NFS-e. */
public enum EventStatus {
    /** SEFIN registered the event and returned the generated Evento document. */
    REGISTERED,
    /** The pedido passed local validation and was signed, but was not sent (dry run). */
    VALIDATED,
    /** The pedido failed local validation and was never sent to SEFIN. */
    REJECTED_LOCALLY,
    /** SEFIN refused the pedido — its erro is surfaced as a SEFIN-stage finding. */
    REJECTED_BY_SEFIN,
    /** The submission could not complete (network/transport); check the note's events before retrying. */
    SUBMIT_FAILED
}
