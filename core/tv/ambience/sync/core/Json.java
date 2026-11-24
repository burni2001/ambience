package tv.ambience.sync.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kleiner JSON-Leser ohne Abhängigkeiten, damit der Kern auf dem Mac und auf Android gleich läuft.
 * Objekte werden zu Map, Listen zu List, Zahlen zu Double.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw j.err("characters after the end");
        return v;
    }

    /** Folgt einem Pfad aus Schlüsseln (Objekt) und Zahlen als Text (Liste). Fehlt etwas, kommt null. */
    public static Object at(Object node, String... path) {
        for (int k = 0; k < path.length && node != null; k++) {
            if (node instanceof Map) {
                node = ((Map<?, ?>) node).get(path[k]);
            } else if (node instanceof List) {
                List<?> l = (List<?>) node;
                int idx;
                try {
                    idx = Integer.parseInt(path[k]);
                } catch (NumberFormatException e) {
                    return null;
                }
                node = idx >= 0 && idx < l.size() ? l.get(idx) : null;
            } else {
                return null;
            }
        }
        return node;
    }

    public static String str(Object node, String... path) {
        Object v = at(node, path);
        return v == null ? null : v.toString();
    }

    public static double num(Object node, double fallback, String... path) {
        Object v = at(node, path);
        return v instanceof Double ? ((Double) v).doubleValue() : fallback;
    }

    public static List<?> list(Object node, String... path) {
        Object v = at(node, path);
        return v instanceof List ? (List<?>) v : new ArrayList<Object>();
    }

    /** Text als JSON-Zeichenkette mit Anführungszeichen. */
    public static String quote(String text) {
        StringBuilder b = new StringBuilder("\"");
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < ' ') b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException("JSON: " + m + " at character " + i);
    }

    private void ws() {
        while (i < s.length() && s.charAt(i) <= ' ') i++;
    }

    private Object value() {
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("key expected");
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("':' expected");
            i++;
            ws();
            m.put(k, value());
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("',' expected");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err("',' expected");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw err("unterminated string");
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("incomplete \\u");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: b.append(e);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw err("unexpected character");
        try {
            return Double.valueOf(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw err("invalid number");
        }
    }
}
