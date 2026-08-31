# 6. Signing & Security

This is where most integrations fail. The DPS is rejected far more often for a
malformed signature than for bad business data. Treat signing as a first-class,
well-tested module.

## 6.1 Two uses of one certificate

| Layer | What it secures | Java mechanism |
|-------|-----------------|----------------|
| **mTLS (transport)** | The HTTPS connection to SEFIN/ADN | `SSLContext` with a PKCS12 `KeyStore` (our `.pfx`) as key manager |
| **XMLDSig (document)** | The DPS / event XML content | `javax.xml.crypto.dsig` (JSR-105, built into the JDK) or Apache Santuario |

Both use the **same e-CNPJ A1** private key + certificate.

## 6.2 XML signature shape (DPS)

The signature is an **enveloped XMLDSig** placed as the last child of the `DPS`
root, signing the `infDPS` element by its `Id`.

```
<DPS versao="1.01" xmlns="http://www.sped.fazenda.gov.br/nfse">
  <infDPS Id="DPS{42 digits}"> ... </infDPS>
  <Signature xmlns="http://www.w3.org/2000/09/xmldsig#">
    <SignedInfo>
      <CanonicalizationMethod Algorithm=".../xml-c14n-20010315"/>
      <SignatureMethod        Algorithm=".../xmldsig#rsa-sha1"/>   <!-- see 6.3 -->
      <Reference URI="#DPS{42 digits}">                            <!-- = infDPS@Id -->
        <Transforms>
          <Transform Algorithm=".../xmldsig#enveloped-signature"/>
          <Transform Algorithm=".../xml-c14n-20010315"/>
        </Transforms>
        <DigestMethod Algorithm=".../xmldsig#sha1"/>               <!-- see 6.3 -->
        <DigestValue>...</DigestValue>
      </Reference>
    </SignedInfo>
    <SignatureValue>...</SignatureValue>
    <KeyInfo><X509Data><X509Certificate>...</X509Certificate></X509Data></KeyInfo>
  </Signature>
</DPS>
```

Rules that bite:
- **`Reference URI` must equal `#` + the `infDPS@Id`** exactly.
- The `<KeyInfo>` must contain the signer **X509Certificate**.
- Use **C14N** canonicalization (not exclusive C14N unless the spec says so) and
  the **enveloped-signature** transform.
- Sign the **`infDPS`** element, not the whole `DPS`, not a detached fragment.
- Do not pretty-print / reformat the XML after signing — any whitespace change
  invalidates the digest. Serialize once, canonical, and send those exact bytes.

## 6.3 Algorithm: SHA-1 vs SHA-256  — **[TO CONFIRM]**

The national system inherits the **SPED/NF-e** convention, which historically is
**RSA-SHA1 + SHA-1 digest + C14N**. Some 2026-era documents move to **SHA-256**.

> **Action before coding the signer:** confirm `SignatureMethod` / `DigestMethod`
> from **Anexo I (RN)** or the restrita Swagger. Implement the signer with the
> algorithm as a **config parameter** so flipping SHA-1↔SHA-256 is a one-liner.
> This is the single most important value to verify in produção restrita.

## 6.4 mTLS in Java / Spring

- Load the `.pfx` into a PKCS12 `KeyStore`; build a `KeyManagerFactory` from it.
- Trust store: the server chain is public ICP-Brasil / gov.br — typically chains
  to roots already in the JDK truststore. If not, add the **ICP-Brasil** root/AC
  chain to a custom truststore. **[TO CONFIRM]** which roots are needed.
- Wire the `SSLContext` into the HTTP client backing Spring's `RestClient` /
  `WebClient` (e.g. Apache HttpClient 5 or Reactor Netty).

```java
KeyStore ks = KeyStore.getInstance("PKCS12");
try (var in = Files.newInputStream(pfxPath)) { ks.load(in, pfxPassword); }
KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX");
kmf.init(ks, pfxPassword);
SSLContext ssl = SSLContext.getInstance("TLS");
ssl.init(kmf.getKeyManagers(), null, null);   // default truststore unless custom
// → feed ssl into the HttpClient used by RestClient
```

## 6.5 Certificate & key handling (operational security)

- **Never commit** the `.pfx` or its password. Use env vars / a secrets manager
  (Vault, AWS Secrets Manager, etc.).
- The A1 certificate **expires yearly** — track expiry and alert ≥30 days ahead;
  a lapsed certificate stops emission entirely.
- Restrict the running service's access to the key (filesystem perms / KMS).
- Log signatures' digest/cert serial for audit, never the private key.
- Keep the **signed DPS and returned NFS-e XML** immutably — they are the legal
  artifacts and the basis for any later audit or event.

## 6.6 Local verification before sending

Build a self-check that, for every signed DPS:
1. validates against `DPS_v1.01.xsd`,
2. re-verifies our own signature (digest + signature value) using the public
   cert,
3. confirms `Reference URI` matches `infDPS@Id`.

Catching a bad signature locally is free; catching it as a SEFIN rejection costs
a round trip and noise in production logs.

## 6.7 Parsing untrusted XML

Two endpoints parse XML supplied by the caller: `POST /nfse/danfse` (render a
PDF from an NFS-e you already hold) and `POST /internal/dry-run`. JAXP's default
`DocumentBuilderFactory` resolves external entities, which on those endpoints
means a document could

- name a local file, whose content is then parsed into the tree and rendered
  into the returned PDF (`file:///etc/passwd` → readable output),
- name a URL, turning the service into an SSRF proxy against anything its
  network can reach, or
- define nested entities that expand until the heap is gone ("billion laughs").

This was **live in the code until 2026-08-28** — a probe with the exact factory
configuration the service used resolved a local file into the rendered PDF.

Every parser now comes from `SafeXml.documentBuilderFactory()`, which disallows
DOCTYPE declarations outright (`disallow-doctype-decl`) plus secure processing,
no external general/parameter entities, no external DTD or schema access, and no
XInclude. Disallowing DTDs closes all three problems in one rule and costs
nothing, because none of the NFS-e layouts uses one — the document is rejected
with a clear message rather than silently sanitised. The XSD `Validator` reading
the instance document is hardened the same way.

Regression tests: `DanfseXxeTest` (entity must never reach the PDF; expansion
bomb must be refused) and `DpsDryRunServiceTest`.

