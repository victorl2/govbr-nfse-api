package br.com.nfse.sefin;

/** One {@code MensagemProcessamento} from SEFIN (an erro or an alerta). */
public record SefinMessage(String code, String description, String complement) {
}
