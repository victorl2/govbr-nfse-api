package br.com.nfse.config;

/** Which NFS-e environment the app is wired to. Selected by the active Spring profile. */
public enum Environment {
    /** Local dev: dummy cert, mock SEFIN, no gov.br network. */
    LOCAL,
    /** Produção restrita (sandbox): real e-CNPJ, tpAmb=2, no legal value. */
    RESTRITA,
    /** Produção (live): real e-CNPJ, tpAmb=1, legally valid NFS-e. */
    PRODUCAO
}
