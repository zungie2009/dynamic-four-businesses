package com.fourbusiness;

import java.util.*;

/**
 * Minimal, dependency-free JSON reader/writer. Supports objects, arrays,
 * strings (with standard escapes and \\uXXXX), numbers, booleans and null.
 */
public final class Json {
    private Json() {}

    // ---- writing ----

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String s) { writeString(s, out); return; }
        if (value instanceof Boolean b) { out.append(b.toString()); return; }
        if (value instanceof java.math.BigDecimal bd) {
            // Written verbatim (toPlainString, never through double) so the declared
            // DECIMAL_19_2 scale - e.g. "100.00" - is never collapsed to "100".
            out.append(bd.toPlainString());
            return;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                out.append((long) d);
            } else {
                out.append(n.toString());
            }
            return;
        }
        if (value instanceof Map<?,?> map) {
            out.append('{');
            boolean first = true;
            for (var e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), out);
                out.append(':');
                writeValue(e.getValue(), out);
            }
            out.append('}');
            return;
        }
        if (value instanceof Iterable<?> it) {
            out.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) out.append(',');
                first = false;
                writeValue(o, out);
            }
            out.append(']');
            return;
        }
        writeString(String.valueOf(value), out);
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    // ---- parsing ----

    public static Object parse(String text) {
        Cursor c = new Cursor(text);
        c.skipWhitespace();
        Object v = c.readValue();
        c.skipWhitespace();
        return v;
    }

    /** Parses a JSON object; returns an empty map for blank/invalid input rather than throwing. */
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(String text) {
        if (blank(text)) return new LinkedHashMap<>();
        try {
            Object v = parse(text);
            if (v instanceof Map<?,?> m) {
                Map<String,Object> out = new LinkedHashMap<>();
                for (var e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
                return out;
            }
        } catch (RuntimeException ignored) { /* fall through to empty */ }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String,Object> asObject(Object v) {
        if (v instanceof Map<?,?> m) {
            Map<String,Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object v) {
        if (v instanceof List<?> l) return (List<Object>) l;
        return List.of();
    }

    public static String text(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    public static double number(Object v, double fallback) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ---- request-envelope convenience (FlowEngine/server build requests as {kind, body, prior}) ----

    public static Map<String,Object> body(Map<String,Object> request) {
        return object(text(request.get("body")));
    }

    public static Map<String,Object> prior(Map<String,Object> request) {
        return asObject(request.get("prior"));
    }

    public static String kind(Map<String,Object> request) {
        return text(request.get("kind"));
    }

    /** First non-blank string among the given values, in order. */
    public static String firstText(Object... values) {
        for (Object v : values) {
            String s = text(v);
            if (!blank(s)) return s;
        }
        return null;
    }

    // ---- recursive-descent cursor ----

    private static final class Cursor {
        private final String text;
        private int pos;

        Cursor(String text) { this.text = text; this.pos = 0; }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        char peek() {
            if (pos >= text.length()) throw new IllegalArgumentException("Unexpected end of JSON input");
            return text.charAt(pos);
        }

        void expect(char c) {
            if (pos >= text.length() || text.charAt(pos) != c)
                throw new IllegalArgumentException("Expected '" + c + "' at position " + pos);
            pos++;
        }

        Object readValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String,Object> readObject() {
            Map<String,Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                char c = text.charAt(pos++);
                if (c == '}') break;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or '}' at position " + (pos - 1));
            }
            return map;
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = text.charAt(pos++);
                if (c == ']') break;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or ']' at position " + (pos - 1));
                skipWhitespace();
            }
            return list;
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = text.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            String hex = text.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("Invalid escape \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Boolean readBoolean() {
            if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("Invalid literal at position " + pos);
        }

        Object readNull() {
            if (text.startsWith("null", pos)) { pos += 4; return null; }
            throw new IllegalArgumentException("Invalid literal at position " + pos);
        }

        Double readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.'
                    || text.charAt(pos) == 'e' || text.charAt(pos) == 'E' || text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
            String numText = text.substring(start, pos);
            if (numText.isEmpty()) throw new IllegalArgumentException("Invalid number at position " + start);
            return Double.parseDouble(numText);
        }
    }
}
