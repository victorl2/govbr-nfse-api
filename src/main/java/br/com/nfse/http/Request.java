package br.com.nfse.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** An incoming request: its path variables, query parameters and body. */
public final class Request {

    private final HttpExchange exchange;
    private final Map<String, String> pathVars;
    private final ObjectMapper json;
    private String body;

    Request(HttpExchange exchange, Map<String, String> pathVars, ObjectMapper json) {
        this.exchange = exchange;
        this.pathVars = pathVars;
        this.json = json;
    }

    /** A {@code {name}} segment of the matched route. */
    public String path(String name) {
        return pathVars.get(name);
    }

    /** A query parameter, or null when absent. */
    public String query(String name) {
        return queryParams().get(name);
    }

    public String body() {
        if (body == null) {
            try {
                body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new HttpApi.BadRequest("could not read the request body: " + e.getMessage());
            }
        }
        return body;
    }

    /**
     * The body parsed as JSON. Malformed JSON is the caller's mistake, so it
     * surfaces as 400 rather than 500.
     */
    public <T> T bodyAs(Class<T> type) {
        try {
            return json.readValue(body(), type);
        } catch (Exception e) {
            String detail = e instanceof com.fasterxml.jackson.core.JsonProcessingException jpe
                    ? jpe.getOriginalMessage() : e.getMessage();
            throw new HttpApi.BadRequest("malformed JSON body: " + detail);
        }
    }

    private Map<String, String> queryParams() {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return params;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
