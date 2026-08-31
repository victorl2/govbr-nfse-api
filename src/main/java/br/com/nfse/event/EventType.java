package br.com.nfse.event;

/**
 * The event types an emitter can author, with their numeric code (used in the
 * pedido's Id and in the consultation path) and their XML payload element name.
 * The full catalog lives in {@code tiposEventos_v1.01.xsd}; only the events we
 * can legitimately raise are modelled here — manifestações belong to the
 * tomador/intermediário and 3xx events to the fisco.
 */
public enum EventType {

    /** e101101 — Cancelamento de NFS-e, authored by the emitter. */
    CANCELAMENTO("101101", "e101101", "Cancelamento de NFS-e"),

    /**
     * e105102 — Cancelamento por Substituição. SEFIN raises this one itself when a
     * DPS carries a {@code subst} block; it is listed here so events read back from
     * the API can be named.
     */
    CANCELAMENTO_POR_SUBSTITUICAO("105102", "e105102", "Cancelamento de NFS-e por Substituição"),

    /** e101103 — Solicitação de Análise Fiscal para Cancelamento (past the direct-cancel window). */
    SOLICITACAO_ANALISE_FISCAL("101103", "e101103",
            "Solicitação de Análise Fiscal para Cancelamento de NFS-e");

    private final String code;
    private final String element;
    private final String description;

    EventType(String code, String element, String description) {
        this.code = code;
        this.element = element;
        this.description = description;
    }

    /** The 6-digit code, as it appears in the pedido Id and the consultation path. */
    public String code() {
        return code;
    }

    /** The payload element name inside infPedReg. */
    public String element() {
        return element;
    }

    /** The xDesc value — a fixed enumeration in the XSD, not free text. */
    public String description() {
        return description;
    }
}
