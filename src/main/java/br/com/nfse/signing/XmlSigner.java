package br.com.nfse.signing;

import org.w3c.dom.Document;

/** Applies an enveloped XMLDSig to a DPS (or event) document. */
public interface XmlSigner {

    /**
     * Signs, in place, the element carrying the given {@code Id} with an enveloped
     * signature appended to the document root. For a DPS, {@code referenceId} is
     * the {@code infDPS@Id} (e.g. {@code "DPS" + 42 digits}); the Reference URI
     * becomes {@code "#" + referenceId}. See docs/06 §6.2.
     */
    void signEnveloped(Document document, String referenceId);
}
