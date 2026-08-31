package br.com.nfse.http;

import java.nio.charset.StandardCharsets;

/** What a handler returns: a status, a content type and the bytes to send. */
public record Response(int status, String contentType, byte[] body) {

    public static Response json(int status, byte[] body) {
        return new Response(status, "application/json;charset=UTF-8", body);
    }

    public static Response xml(String body) {
        return new Response(200, "application/xml;charset=UTF-8", body.getBytes(StandardCharsets.UTF_8));
    }

    public static Response pdf(byte[] body) {
        return new Response(200, "application/pdf", body);
    }
}
