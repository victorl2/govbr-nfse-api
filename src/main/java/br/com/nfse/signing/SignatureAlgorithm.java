package br.com.nfse.signing;

import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;

/**
 * Signature + digest algorithms for the DPS XMLDSig.
 *
 * <p>Whether the Sistema Nacional NFS-e requires SHA-1 or SHA-256 is the single
 * most important value to confirm in produção restrita (docs/06 §6.3). It is
 * config-driven ({@code nfse.signature.algorithm}) so flipping it is a one-liner.
 */
public enum SignatureAlgorithm {

    RSA_SHA1(SignatureMethod.RSA_SHA1, DigestMethod.SHA1),
    RSA_SHA256("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256", DigestMethod.SHA256);

    private final String signatureMethodUri;
    private final String digestMethodUri;

    SignatureAlgorithm(String signatureMethodUri, String digestMethodUri) {
        this.signatureMethodUri = signatureMethodUri;
        this.digestMethodUri = digestMethodUri;
    }

    public String signatureMethodUri() {
        return signatureMethodUri;
    }

    public String digestMethodUri() {
        return digestMethodUri;
    }
}
