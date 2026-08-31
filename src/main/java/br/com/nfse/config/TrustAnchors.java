package br.com.nfse.config;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * Server-trust material for the mTLS stack: the JDK's default roots PLUS the
 * PEM bundle vendored at {@code classpath:truststore/extra-roots.pem} (e.g.
 * GlobalSign Root R46, which anchors the SERPRO chain of *.nfse.gov.br but is
 * missing from older JDK cacerts).
 */
final class TrustAnchors {

    private static final String EXTRA_ROOTS = "truststore/extra-roots.pem";

    private TrustAnchors() {
    }

    static X509ExtendedTrustManager compositeTrustManager() {
        X509ExtendedTrustManager defaults = trustManagerFor(null);
        X509ExtendedTrustManager extras = trustManagerFor(extraRootsKeyStore());
        return new Composite(defaults, extras);
    }

    /** null keystore → the JDK's default cacerts. */
    private static X509ExtendedTrustManager trustManagerFor(KeyStore keyStore) {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager x509) {
                    return x509;
                }
            }
            throw new IllegalStateException("no X509ExtendedTrustManager available");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build trust manager: " + e.getMessage(), e);
        }
    }

    private static KeyStore extraRootsKeyStore() {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(EXTRA_ROOTS)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + EXTRA_ROOTS);
            }
            Collection<? extends Certificate> roots =
                    CertificateFactory.getInstance("X.509").generateCertificates(in);
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate root : roots) {
                ks.setCertificateEntry("extra-" + i++, root);
            }
            return ks;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load " + EXTRA_ROOTS + ": " + e.getMessage(), e);
        }
    }

    /** Accepts a chain when either delegate does; issuers are the union of both. */
    private static final class Composite extends X509ExtendedTrustManager {

        private final X509ExtendedTrustManager first;
        private final X509ExtendedTrustManager second;

        private Composite(X509ExtendedTrustManager first, X509ExtendedTrustManager second) {
            this.first = first;
            this.second = second;
        }

        @FunctionalInterface
        private interface Check {
            void run(X509ExtendedTrustManager tm) throws CertificateException;
        }

        private void either(Check check) throws CertificateException {
            try {
                check.run(first);
            } catch (CertificateException e) {
                check.run(second);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            either(tm -> tm.checkServerTrusted(chain, authType));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            either(tm -> tm.checkServerTrusted(chain, authType, socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            either(tm -> tm.checkServerTrusted(chain, authType, engine));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            either(tm -> tm.checkClientTrusted(chain, authType));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            either(tm -> tm.checkClientTrusted(chain, authType, socket));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            either(tm -> tm.checkClientTrusted(chain, authType, engine));
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> union = new ArrayList<>();
            Stream.of(first.getAcceptedIssuers(), second.getAcceptedIssuers())
                    .flatMap(Stream::of)
                    .forEach(union::add);
            return union.toArray(new X509Certificate[0]);
        }
    }
}
