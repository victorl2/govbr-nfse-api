package br.com.nfse.config;

import br.com.nfse.testsupport.DryRunTestSupport;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpClientsTest {

    /** SEFIN's front-end resets h2 streams with "Use HTTP/1.1 for request". */
    @Test
    void speaksHttp11BecauseSefinRejectsH2() throws Exception {
        HttpClient client = HttpClients.httpClient(
                DryRunTestSupport.localProps(null), SSLContext.getDefault());
        assertEquals(HttpClient.Version.HTTP_1_1, client.version());
    }
}
