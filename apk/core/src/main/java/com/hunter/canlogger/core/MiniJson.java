package com.hunter.canlogger.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON reader/writer (no Android or library dependency, so the core stays testable with plain javac).
 * Values: Map (LinkedHashMap), List, String, Double, Boolean, null.
 */
public final class MiniJson {
    private MiniJson() {}

    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("trailing data");
        return v;
    }

    private static final class P {
        final String s;
        int i;

        P(String s) { this.s = s; }

        void ws() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) i++;
        }

        char peek() {
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            return s.charAt(i);
        }

        Object value(int depth) {
            if (depth > 40) throw new IllegalArgumentException("too deep");
            char c = peek();
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek() == '}') { i++; return m; }
            for (;;) {
                ws();
                if (peek() != '"') throw new IllegalArgumentException("key expected");
                String k = string();
                ws();
                if (peek() != ':') throw new IllegalArgumentException(": expected");
                i++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                char c = peek();
                i++;
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException(", expected");
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (peek() == ']') { i++; return l; }
            for (;;) {
                ws();
                l.add(value(depth + 1));
                ws();
                char c = peek();
                i++;
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException(", expected");
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            for (;;) {
                char c = peek();
                i++;
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = peek();
                    i++;
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case '/': sb.append('/'); break;
                        case '\\': sb.append('\\'); break;
                        case '"': sb.append('"'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("bad escape");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw new IllegalArgumentException("bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Double number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("value expected");
            return Double.valueOf(s.substring(st, i));
        }
    }

    public static String write(Object v, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, pretty, 0);
        return sb.toString();
    }

    private static void indent(StringBuilder sb, boolean pretty, int d) {
        if (!pretty) return;
        sb.append('\n');
        for (int i = 0; i < d; i++) sb.append(' ');
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v, boolean pretty, int d) {
        if (v == null) sb.append("null");
        else if (v instanceof String) quote(sb, (String) v);
        else if (v instanceof Boolean) sb.append(v.toString());
        else if (v instanceof Number) sb.append(Text.num(((Number) v).doubleValue()));
        else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                indent(sb, pretty, d + 1);
                quote(sb, e.getKey());
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), pretty, d + 1);
            }
            indent(sb, pretty, d);
            sb.append('}');
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) sb.append(',');
                first = false;
                indent(sb, pretty, d + 1);
                write(sb, o, pretty, d + 1);
            }
            indent(sb, pretty, d);
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
