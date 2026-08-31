package br.com.nfse.dps;

import org.w3c.dom.ls.LSInput;

import java.io.InputStream;
import java.io.Reader;

/** Minimal {@link LSInput} that feeds an XSD include/import from a classpath stream. */
class ClasspathLSInput implements LSInput {

    private final String publicId;
    private final String systemId;
    private InputStream byteStream;

    ClasspathLSInput(String publicId, String systemId, InputStream byteStream) {
        this.publicId = publicId;
        this.systemId = systemId;
        this.byteStream = byteStream;
    }

    @Override public InputStream getByteStream() { return byteStream; }
    @Override public void setByteStream(InputStream byteStream) { this.byteStream = byteStream; }
    @Override public String getPublicId() { return publicId; }
    @Override public void setPublicId(String publicId) { /* fixed at construction */ }
    @Override public String getSystemId() { return systemId; }
    @Override public void setSystemId(String systemId) { /* fixed at construction */ }

    // Unused parts of the LSInput contract.
    @Override public Reader getCharacterStream() { return null; }
    @Override public void setCharacterStream(Reader characterStream) { }
    @Override public String getStringData() { return null; }
    @Override public void setStringData(String stringData) { }
    @Override public String getBaseURI() { return null; }
    @Override public void setBaseURI(String baseURI) { }
    @Override public String getEncoding() { return null; }
    @Override public void setEncoding(String encoding) { }
    @Override public boolean getCertifiedText() { return false; }
    @Override public void setCertifiedText(boolean certifiedText) { }
}
