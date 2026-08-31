package br.com.nfse.http;

import java.util.HashMap;
import java.util.Map;

/**
 * One method + path pattern, where a segment in braces is a variable.
 * {@link #match} returns the captured variables, or null when the route does
 * not apply.
 */
record Route(String method, String pattern, HttpApi.Handler handler) {

    private static final Map<String, String[]> SEGMENT_CACHE = new HashMap<>();

    /** How many segments are literal — used to prefer /nfse/danfse over /nfse/{chave}. */
    int literalSegments() {
        int literal = 0;
        for (String segment : segments(pattern)) {
            if (!segment.startsWith("{")) {
                literal++;
            }
        }
        return literal;
    }

    Map<String, String> match(String requestMethod, String path) {
        if (!method.equalsIgnoreCase(requestMethod)) {
            return null;
        }
        String[] want = segments(pattern);
        String[] got = segments(path);
        if (want.length != got.length) {
            return null;
        }
        Map<String, String> vars = new HashMap<>();
        for (int i = 0; i < want.length; i++) {
            if (want[i].startsWith("{") && want[i].endsWith("}")) {
                vars.put(want[i].substring(1, want[i].length() - 1), got[i]);
            } else if (!want[i].equals(got[i])) {
                return null;
            }
        }
        return vars;
    }

    private static String[] segments(String path) {
        return SEGMENT_CACHE.computeIfAbsent(path,
                p -> p.replaceAll("^/+|/+$", "").split("/"));
    }
}
