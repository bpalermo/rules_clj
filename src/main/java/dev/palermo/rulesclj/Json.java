package dev.palermo.rulesclj;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for Bazel's worker protocol.
 *
 * <p>Hand-written for the same reason as everything else in this package: the shim carries no
 * third-party dependencies, and a JSON library would be one. The scope is small and fixed — Bazel
 * sends work requests and reads work responses, both of which are flat objects of strings,
 * numbers, booleans and arrays.
 *
 * <p>Objects are read as a stream rather than a document: Bazel writes one request after another
 * on the same stdin with no separator, so the reader has to stop at the end of each value and
 * leave the rest for the next call.
 */
final class Json {

    /** Reads one JSON value, leaving the reader positioned after it. Returns null at end of input. */
    static Object read(Reader in) throws IOException {
        int c = skipWhitespace(in);
        if (c < 0) {
            return null;
        }
        return readValue(in, c);
    }

    static String writeObject(Map<String, Object> value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(out, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(e.getKey()));
                out.append(':');
                write(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(out, list.get(i));
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("cannot serialise " + value.getClass());
        }
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }

    private static Object readValue(Reader in, int c) throws IOException {
        return switch (c) {
            case '{' -> readObject(in);
            case '[' -> readArray(in);
            case '"' -> readString(in);
            case 't' -> readLiteral(in, "rue", Boolean.TRUE);
            case 'f' -> readLiteral(in, "alse", Boolean.FALSE);
            case 'n' -> readLiteral(in, "ull", null);
            default -> readNumber(in, c);
        };
    }

    private static Map<String, Object> readObject(Reader in) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        int c = skipWhitespace(in);
        if (c == '}') {
            return map;
        }
        while (true) {
            if (c != '"') {
                throw new IOException("expected a key, got " + (char) c);
            }
            String key = readString(in);
            if (skipWhitespace(in) != ':') {
                throw new IOException("expected ':' after key " + key);
            }
            map.put(key, readValue(in, skipWhitespace(in)));
            c = skipWhitespace(in);
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new IOException("expected ',' or '}' in object, got " + (char) c);
            }
            c = skipWhitespace(in);
        }
    }

    private static List<Object> readArray(Reader in) throws IOException {
        List<Object> list = new ArrayList<>();
        int c = skipWhitespace(in);
        if (c == ']') {
            return list;
        }
        while (true) {
            list.add(readValue(in, c));
            c = skipWhitespace(in);
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new IOException("expected ',' or ']' in array, got " + (char) c);
            }
            c = skipWhitespace(in);
        }
    }

    private static String readString(Reader in) throws IOException {
        StringBuilder out = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new IOException("unterminated string");
            }
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append((char) c);
                continue;
            }
            int esc = in.read();
            switch (esc) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    char[] hex = new char[4];
                    for (int i = 0; i < 4; i++) {
                        int h = in.read();
                        if (h < 0) {
                            throw new IOException("truncated \\u escape");
                        }
                        hex[i] = (char) h;
                    }
                    out.append((char) Integer.parseInt(new String(hex), 16));
                }
                default -> throw new IOException("unknown escape \\" + (char) esc);
            }
        }
    }

    private static Object readLiteral(Reader in, String rest, Object value) throws IOException {
        for (int i = 0; i < rest.length(); i++) {
            if (in.read() != rest.charAt(i)) {
                throw new IOException("bad literal");
            }
        }
        return value;
    }

    private static Object readNumber(Reader in, int first) throws IOException {
        StringBuilder digits = new StringBuilder();
        digits.append((char) first);
        while (true) {
            in.mark(1);
            int c = in.read();
            if (c < 0) {
                break;
            }
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                digits.append((char) c);
            } else {
                in.reset();
                break;
            }
        }
        String text = digits.toString();
        return text.contains(".") || text.contains("e") || text.contains("E")
                ? (Object) Double.valueOf(text)
                : (Object) Long.valueOf(text);
    }

    private static int skipWhitespace(Reader in) throws IOException {
        int c;
        do {
            c = in.read();
        } while (c == ' ' || c == '\n' || c == '\r' || c == '\t');
        return c;
    }

    private Json() {}
}
