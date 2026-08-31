package br.com.nfse.certificate;

import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/** The private key + certificate (and the keystore behind them) used for mTLS and XML signing. */
public record KeyMaterial(
        KeyStore keyStore,
        char[] password,
        String alias,
        X509Certificate certificate,
        PrivateKey privateKey
) {}
