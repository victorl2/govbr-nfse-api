package br.com.nfse.event;


/**
 * The JSON input for cancelling an NFS-e. The author is the party raising the
 * event — for a note we issued, our own CNPJ; the XSD accepts a CPF instead when
 * the author is a natural person.
 *
 * <p>{@code cMotivo}: 1 = Erro na Emissão, 2 = Serviço não Prestado, 9 = Outros.
 * {@code xMotivo} explains the code and must be 15–255 characters — SEFIN's own
 * schema enforces that minimum, so a terse "erro" is rejected before it is sent.
 *
 * <p>Cancellation is bounded by a municipal time window; past it the note can only
 * be replaced (a DPS carrying {@code subst}) or cancelled through fiscal analysis.
 */
public record CancelEventRequest(
        String cnpjAutor,
        String cpfAutor,
        String cMotivo,
        String xMotivo
) {
}
