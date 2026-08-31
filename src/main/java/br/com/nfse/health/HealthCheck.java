package br.com.nfse.health;

import br.com.nfse.certificate.CertificateInfo;
import br.com.nfse.certificate.CertificateLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * The health of this service is, in practice, the health of one thing: the
 * e-CNPJ certificate. Everything else it does is computation.
 *
 * <p>The distinction the report draws is between *unusable* and *expiring*. An
 * expired or unloadable certificate means no note can be signed, so the service
 * reports DOWN and an orchestrator is right to replace it. A certificate with
 * three weeks left is still perfectly good — reporting DOWN there would have a
 * scheduler kill a working service, so it stays UP and raises a warning instead.
 */
public final class HealthCheck {

    /** Renewing an ICP-Brasil A1 takes days, so warn with a month in hand. */
    static final long WARN_BELOW_DAYS = 30;

    private final CertificateLoader certificateLoader;

    public HealthCheck(CertificateLoader certificateLoader) {
        this.certificateLoader = certificateLoader;
    }

    public HealthReport report() {
        CertificateInfo info;
        try {
            info = certificateLoader.info();
        } catch (Exception e) {
            return HealthReport.down(null,
                    List.of("the certificate could not be loaded: " + e.getMessage()));
        }

        HealthReport.Certificate certificate =
                new HealthReport.Certificate(info.notAfter(), info.daysToExpiry());
        List<String> warnings = new ArrayList<>();

        if (info.daysToExpiry() <= 0) {
            warnings.add("the certificate expired on " + info.notAfter()
                    + " — no note can be signed until the e-CNPJ A1 is renewed");
            return HealthReport.down(certificate, warnings);
        }
        if (info.daysToExpiry() < WARN_BELOW_DAYS) {
            warnings.add("the certificate expires in " + info.daysToExpiry()
                    + " days (" + info.notAfter() + ") — renew the e-CNPJ A1 now");
        }
        return HealthReport.up(certificate, warnings);
    }
}
