package br.com.nfse;

import br.com.nfse.adn.AdnClient;
import br.com.nfse.api.ApiRoutes;
import br.com.nfse.certificate.CertificateInfo;
import br.com.nfse.certificate.CertificateLoader;
import br.com.nfse.config.BrasiliaTime;
import br.com.nfse.config.HttpClients;
import br.com.nfse.config.NfseProperties;
import br.com.nfse.config.Settings;
import br.com.nfse.danfse.DanfseGenerator;
import br.com.nfse.dps.DpsSchemaValidator;
import br.com.nfse.dryrun.DpsDryRunService;
import br.com.nfse.dryrun.DpsLinter;
import br.com.nfse.dryrun.SignatureVerifier;
import br.com.nfse.emission.DpsBuilder;
import br.com.nfse.emission.NfseEmissionService;
import br.com.nfse.event.EventBuilder;
import br.com.nfse.event.NfseEventService;
import br.com.nfse.event.PedRegEventoSchemaValidator;
import br.com.nfse.health.HealthCheck;
import br.com.nfse.http.ConcurrencyGate;
import br.com.nfse.http.HttpApi;
import br.com.nfse.http.Json;
import br.com.nfse.sefin.SefinClient;
import br.com.nfse.signing.EnvelopedXmlSigner;
import br.com.nfse.store.EmissionStore;
import br.com.nfse.store.NumberingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Entry point and object graph.
 *
 * <p>The service wires itself: every collaborator takes its dependencies through
 * a constructor, so a container buys nothing here beyond the classes it loads to
 * do the same job. Reading {@link #main} tells you the whole architecture.
 */
public final class NfseApplication {

    private static final Logger log = LoggerFactory.getLogger(NfseApplication.class);

    public static void main(String[] args) throws Exception {
        NfseProperties props = Settings.fromEnvironment();
        ObjectMapper json = Json.mapper();

        Assembly assembly = assemble(props, Settings.dataDir(), json);
        CertificateLoader certificateLoader = assembly.certificateLoader();

        reportStartup(props, certificateLoader, assembly.sefinClient());
        HttpApi api = assembly.api();
        int port = api.start(Settings.port());
        Runtime.getRuntime().addShutdownHook(new Thread(api::stop));
        log.info("NFS-e API listening on port {} (max {} concurrent DANFSe renders); state in {}",
                port, assembly.renderCapacity(), assembly.stateDir());

    }

    /** The two mTLS endpoints, built together because they share one SSLContext. */
    private record HttpClientPair(RestClient sefin, RestClient adn) {
    }

    /** Everything the service is made of, so callers can reach the few parts they need. */
    record Assembly(HttpApi api, CertificateLoader certificateLoader, SefinClient sefinClient,
                    int renderCapacity, java.nio.file.Path stateDir) {
    }

    /**
     * The whole object graph, in one place.
     *
     * <p>The AOT training run in the Dockerfile drives the real service through
     * this same graph, which is what makes the shipped cache match what the
     * service actually loads.
     */
    static Assembly assemble(NfseProperties props, java.nio.file.Path dataDir, ObjectMapper json)
            throws Exception {
        Clock clock = BrasiliaTime.clock();
        long t0 = System.nanoTime();

        // The three expensive pieces of startup are independent of one another:
        // parsing the PKCS12 and building the SSLContext from it (~180 ms), and
        // compiling each of the two XSD trees (~106 ms together). Sequentially
        // that is the sum; concurrently it is the longest. None of it can be
        // precomputed at build time — the certificate only exists at run time and
        // a compiled Schema is not serialisable — so overlapping is the win
        // available.
        CertificateLoader certificateLoader = new CertificateLoader(props);
        DpsSchemaValidator dpsSchema;
        PedRegEventoSchemaValidator eventSchema;
        HttpClientPair clients;
        try (ExecutorService startup = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<DpsSchemaValidator> dpsSchemaTask = startup.submit(DpsSchemaValidator::new);
            Future<PedRegEventoSchemaValidator> eventSchemaTask =
                    startup.submit(PedRegEventoSchemaValidator::new);
            // Um único SSLContext serve os dois destinos: a SEFIN (emissão) e o
            // ADN (registro nacional). Construí-lo duas vezes pagaria o custo
            // mais caro da inicialização de novo, por nada.
            Future<HttpClientPair> tlsTask = startup.submit(() -> {
                var http = HttpClients.httpClient(props, HttpClients.sslContext(certificateLoader));
                return new HttpClientPair(
                        HttpClients.restClient(props, http, props.sefinBaseUrl()),
                        HttpClients.restClient(props, http, props.adnBaseUrl()));
            });

            dpsSchema = dpsSchemaTask.get();
            eventSchema = eventSchemaTask.get();
            clients = tlsTask.get();
        } catch (ExecutionException e) {
            // Unwrap: a bad certificate must still report itself, not appear as a
            // concurrency failure.
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
        SefinClient sefinClient = new SefinClient(clients.sefin());
        AdnClient adnClient = new AdnClient(clients.adn());
        long tReady = System.nanoTime();

        EnvelopedXmlSigner signer = new EnvelopedXmlSigner(props, certificateLoader);
        SignatureVerifier verifier = new SignatureVerifier();
        DpsDryRunService dryRunService = new DpsDryRunService(
                dpsSchema, new DpsLinter(props), signer, verifier);

        // O estado fica separado POR AMBIENTE, e isso não é organização: o
        // infDPS@Id não carrega tpAmb (o layout não prevê), então restrita e
        // produção produzem o MESMO dpsId para a mesma (série, número). Num
        // diretório único, uma emissão em produção esbarraria no registro de
        // restrita, o guarda de idempotência devolveria o resultado guardado e a
        // resposta viria AUTHORIZED com a chave de restrita — sucesso falso,
        // exibindo como nota real uma chave sem valor legal nenhum.
        java.nio.file.Path envDir = dataDir.resolve(
                props.environment().name().toLowerCase(java.util.Locale.ROOT));
        avisarSobreLayoutAntigo(dataDir, envDir);
        NumberingStore numbering = new NumberingStore(envDir);
        EmissionStore emissions = new EmissionStore(envDir, json);
        NfseEmissionService emissionService = new NfseEmissionService(
                new DpsBuilder(props, clock), dryRunService, sefinClient, numbering, emissions);
        NfseEventService eventService = new NfseEventService(
                new EventBuilder(props, clock), eventSchema,
                signer, verifier, sefinClient);
        long tRest = System.nanoTime();
        log.debug("wiring: certificate + TLS + schemas (concurrent) {} ms, rest {} ms",
                (tReady - t0) / 1_000_000, (tRest - tReady) / 1_000_000);

        HealthCheck healthCheck = new HealthCheck(certificateLoader);
        ConcurrencyGate renderGate =
                new ConcurrencyGate(Settings.maxConcurrentRenders(), Settings.renderQueueTimeout());

        HttpApi api = new HttpApi(json);
        new ApiRoutes(emissionService, eventService, sefinClient, adnClient, new DanfseGenerator(),
                dryRunService, certificateLoader, healthCheck, renderGate,
                numbering, emissions, props, json).register(api);

        return new Assembly(api, certificateLoader, sefinClient, renderGate.capacity(), envDir);
    }

    /**
     * O layout antigo guardava tudo na raiz do dataDir, sem separar ambiente.
     * Esses registros são documentos fiscais: mover sozinho seria pior do que
     * avisar, porque só quem operou sabe de qual ambiente eles vieram.
     */
    private static void avisarSobreLayoutAntigo(java.nio.file.Path dataDir, java.nio.file.Path envDir) {
        for (String legado : new String[] {"emissions", "numbering"}) {
            java.nio.file.Path antigo = dataDir.resolve(legado);
            if (java.nio.file.Files.isDirectory(antigo) && !antigo.startsWith(envDir)) {
                log.warn("Há registros no layout antigo em {} — eles NÃO serão lidos."
                                + " O estado agora fica separado por ambiente, em {}."
                                + " Mova o que for do ambiente certo: mv {}/* {}/",
                        antigo, envDir, antigo, envDir.resolve(legado));
            }
        }
    }

    /** Proves, before any note is issued, that the certificate loads and mTLS works. */
    private static void reportStartup(NfseProperties props, CertificateLoader certificateLoader,
                                      SefinClient sefinClient) {
        CertificateInfo cert = certificateLoader.info();
        log.info("NFS-e starting: environment={} tpAmb={} sefin={} cert.subject='{}' cert.expiresInDays={}",
                props.environment(), props.tpAmb(), props.sefinBaseUrl(), cert.subject(), cert.daysToExpiry());

        if (cert.daysToExpiry() < 30) {
            log.warn("Certificate expires in {} days — renew the e-CNPJ A1 soon.", cert.daysToExpiry());
        }
        if (!props.connectivityCheckOnStartup()) {
            log.info("Startup connectivity check disabled (NFSE_CONNECTIVITY_CHECK=false).");
            return;
        }
        try {
            sefinClient.ping();
            log.info("mTLS connectivity to SEFIN OK (an HTTP answer proves TLS + client cert + routing).");
        } catch (Exception e) {
            log.warn("mTLS connectivity check to SEFIN failed: {}", e.getMessage());
        }
    }
}
