package br.com.nfse.config;

/**
 * Identity of this emitter software, written into every fiscal document we
 * produce as {@code verAplic} — the DPS and the Pedido de Registro de Evento
 * alike. SEFIN echoes its own {@code versaoAplicativo} back; this is ours.
 */
public final class EmitterSoftware {

    /** TSVerAplic: at most 20 characters. */
    public static final String VER_APLIC = "nfse-svc-0.1";

    private EmitterSoftware() {
    }
}
