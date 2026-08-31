package br.com.nfse.store;

/**
 * What is known about one emission attempt. The XML documents live beside this
 * record as files rather than inside it, so a note can be handed back verbatim —
 * the bytes SEFIN signed and accepted, not a re-serialisation of them.
 */
public record EmissionRecord(
        String dpsId,
        String serie,
        String number,
        Status status,
        String chaveAcesso,
        String detail,
        String createdAt,
        String updatedAt
) {

    public enum Status {
        /** Claimed and about to be sent, or sent with the answer still unknown. */
        SUBMITTED,
        /** SEFIN generated the NFS-e; {@code chaveAcesso} is set. */
        AUTHORIZED,
        /** SEFIN refused it. The número is spent; the note does not exist. */
        REJECTED_BY_SEFIN,
        /**
         * The submission failed in transit and the follow-up probe could not say
         * whether a note exists. Never retry this número without checking
         * {@code GET /dps/{id}} first.
         */
        SUBMIT_FAILED
    }

    public EmissionRecord withStatus(Status newStatus, String chave, String newDetail, String at) {
        return new EmissionRecord(dpsId, serie, number, newStatus,
                chave != null ? chave : chaveAcesso,
                newDetail != null ? newDetail : detail,
                createdAt, at);
    }
}
