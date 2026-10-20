package bidder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tiny JSON reader/writer so the demo has zero runtime dependencies.
 * Parses into Map / List / String / Double / Boolean / null and writes the same shapes back.
 * Production code would use Jackson; this is ~150 lines you can read in three minutes.
 */
final class Json {
    private Json() {} // static helpers only, like a Python module of functions

    static Map<String, Object> parseObject(String text) {
        Object value = new Parser(text).parseTop();
        if (value instanceof Map<?, ?> map) { // pattern matching instanceof: test + cast in one step
            @SuppressWarnings("unchecked")
            var result = (Map<String, Object>) map;
            return result;
        }
        throw new IllegalArgumentException("expected a JSON object");
    }

    // ---- field helpers used by the request parsers ----

    static String str(Map<String, Object> m, String key) {
        if (m.get(key) instanceof String s) return s;
        throw new IllegalArgumentException("missing string field: " + key);
    }

    static double num(Map<String, Object> m, String key) {
        if (m.get(key) instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("missing number field: " + key);
    }

    static Set<String> strings(Map<String, Object> m, String key) {
        if (!(m.get(key) instanceof List<?> list)) return Set.of();
        return Set.copyOf(list.stream().map(String::valueOf).toList());
    }

    // ---- writer ----

    static String write(Object value) {
        var sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb) {
        switch (value) { // switch on type (Java 21 pattern matching)
            case null -> sb.append("null");
            case String s -> quote(s, sb);
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            case Map<?, ?> m -> {
                sb.append('{');
                var first = true;
                for (var e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            }
            case Collection<?> c -> {
                sb.append('[');
                var first = true;
                for (var item : c) {
                    if (!first) sb.append(',');
                    first = false;
                    write(item, sb);
                }
                sb.append(']');
            }
            default -> quote(value.toString(), sb);
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---- reader: a small recursive-descent parser ----

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        Object parseTop() {
            Object v = value();
            skipWs();
            if (i != s.length()) throw error("trailing characters");
            return v;
        }

        private Object value() {
            skipWs();
            if (i >= s.length()) throw error("unexpected end");
            return switch (s.charAt(i)) { // switch *expression*: it returns a value
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            var map = new LinkedHashMap<String, Object>();
            expect('{');
            skipWs();
            if (peek() == '}') { i++; return map; }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                map.put(key, value());
                skipWs();
                if (peek() == ',') { i++; continue; }
                expect('}');
                return map;
            }
        }

        private List<Object> array() {
            var list = new ArrayList<Object>();
            expect('[');
            skipWs();
            if (peek() == ']') { i++; return list; }
            while (true) {
                list.add(value());
                skipWs();
                if (peek() == ',') { i++; continue; }
                expect(']');
                return list;
            }
        }

        private String string() {
            expect('"');
            var sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> sb.append(e); // \" \\ \/
                }
            }
            throw error("unterminated string");
        }

        private Double number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw error("unexpected character");
            return Double.parseDouble(s.substring(start, i));
        }

        private Object literal(String word, Object result) {
            if (!s.startsWith(word, i)) throw error("bad literal");
            i += word.length();
            return result;
        }

        private void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
        private void expect(char c) { if (peek() != c) throw error("expected '" + c + "'"); i++; }
        private IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("bad JSON at " + i + ": " + msg);
        }
    }
}
