package com.clinicqueue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal JSON writer (maps, lists, scalars) and a parser for flat request objects. */
public final class Json {

    private Json() {}

    // ------------------------------------------------------------------ writing

    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof CharSequence || o instanceof Enum<?> || o instanceof Instant) {
            quote(sb, o.toString());
        } else if (o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Number n) {
            if (n instanceof Double d && (d.isNaN() || d.isInfinite())) sb.append("null");
            else sb.append(n);
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object item : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Cannot serialise " + o.getClass().getName());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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

    // ------------------------------------------------------------------ parsing

    /** Parses a flat JSON object into string values (numbers/booleans kept as text, null as null). */
    public static Map<String, String> parseObject(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        return new Parser(text).object();
    }

    private static final class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) { this.s = s; }

        Map<String, String> object() {
            Map<String, String> map = new LinkedHashMap<>();
            ws();
            expect('{');
            ws();
            if (peek() == '}') {
                i++;
            } else {
                while (true) {
                    ws();
                    String key = string();
                    ws();
                    expect(':');
                    ws();
                    map.put(key, value());
                    ws();
                    if (peek() == ',') { i++; continue; }
                    expect('}');
                    break;
                }
            }
            ws();
            if (i != s.length()) throw bad();
            return map;
        }

        private String value() {
            if (peek() == '"') return string();
            int start = i;
            while (i < s.length() && ",}".indexOf(s.charAt(i)) < 0 && !Character.isWhitespace(s.charAt(i))) i++;
            String raw = s.substring(start, i);
            if (raw.isEmpty() || raw.equals("{") || raw.startsWith("[") || raw.startsWith("{")) throw bad();
            return raw.equals("null") ? null : raw;
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw bad();
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw bad();
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw bad();
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw bad();
                        }
                        i += 4;
                    }
                    default -> throw bad();
                }
            }
            return sb.toString();
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private void expect(char c) {
            if (peek() != c) throw bad();
            i++;
        }

        private IllegalArgumentException bad() {
            return new IllegalArgumentException("Invalid JSON body");
        }
    }
}
