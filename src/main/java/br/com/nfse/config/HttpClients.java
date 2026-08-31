package br.com.nfse.config;

import br.com.nfse.certificate.CertificateLoader;
import br.com.nfse.certificate.KeyMaterial;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.net.http.HttpClient;

/**
 * Builds the mTLS HTTP stack: an {@link SSLContext} backed by our PKCS#12 key
 * material, a {@link HttpClient} that presents it, and one {@link RestClient}
 * per upstream (SEFIN / ADN).
 *
 * <p>Plain factory methods — the objects are wired by hand in
 * {@code NfseApplication}. RestClient is the one piece of Spring kept in the
 * runtime: SefinClient's live behaviour was proven through it, so it stays.
 */
public final class HttpClients {

    private HttpClients() {
    }

    public static SSLContext sslContext(CertificateLoader certificateLoader) {
        try {
            KeyMaterial km = certificateLoader.load();
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX");
            kmf.init(km.keyStore(), km.password());
            SSLContext ctx = SSLContext.getInstance("TLS");
            // JDK default roots + vendored extras: *.nfse.gov.br serves a SERPRO
            // chain anchored at GlobalSign Root R46, absent from older cacerts.
            ctx.init(kmf.getKeyManagers(),
                    new TrustManager[]{TrustAnchors.compositeTrustManager()}, null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build mTLS SSLContext: " + e.getMessage(), e);
        }
    }

    public static HttpClient httpClient(NfseProperties props, SSLContext sslContext) {
        return HttpClient.newBuilder()
                .sslContext(sslContext)
                // SEFIN's front-end resets h2 streams ("Use HTTP/1.1 for request").
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.httpOrDefault().connectTimeoutOrDefault())
                .build();
    }

    public static RestClient restClient(NfseProperties props, HttpClient httpClient, String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.httpOrDefault().readTimeoutOrDefault());
        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .build();
    }
}
