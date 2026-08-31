package br.com.nfse.dps;

/** Thrown when a DPS fails XSD validation against the official schema. */
public class DpsValidationException extends RuntimeException {
    public DpsValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
