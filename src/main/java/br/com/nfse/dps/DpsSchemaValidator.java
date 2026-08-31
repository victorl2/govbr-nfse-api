package br.com.nfse.dps;


/**
 * Validates a DPS XML string against the official {@code DPS_v1.01.xsd}. See
 * {@link XsdValidator} for how the vendored schema set is loaded and which
 * official quirks are shimmed.
 */
public class DpsSchemaValidator extends XsdValidator {

    public DpsSchemaValidator() {
        super("DPS_v1.01.xsd", "DPS");
    }
}
