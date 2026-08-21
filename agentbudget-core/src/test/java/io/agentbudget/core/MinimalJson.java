package io.agentbudget.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for the real-provider accuracy suite (issue 06), which needs to read
 * OpenAI and Anthropic SSE payloads without pulling a JSON dependency into a module whose
 * compile-scope surface is meant to be the JDK alone — see the CI check in {@code ci.yml}. Handles
 * objects, arrays, strings with escapes, numbers, booleans, and null: the full surface both
 * providers' streaming payloads use, nothing more. Not validating, not forgiving of malformed
 * input — a real API response is well-formed, and a malformed one should fail loudly rather than
 * silently mis-parse.
 */
final class MinimalJson {

    private final String input;
    private int pos;

    private MinimalJson(String input) {
        this.input = input;
    }

    static Object parse(String json) {
        MinimalJson parser = new MinimalJson(json);
        parser.skipWhitespace();
        return parser.parseValue();
    }

    /**
     * Walks a parsed tree by a path of {@link String} object keys and {@link Integer} array
     * indices, short-circuiting to {@code null} the moment a step is missing — the common case for
     * provider chunks, most of which carry only a fraction of the fields a full payload can have.
     */
    static Object get(Object node, Object... path) {
        Object current = node;
        for (Object step : path) {
            if (current == null) {
                return null;
            }
            if (step instanceof String key) {
                current = ((Map<?, ?>) current).get(key);
            } else {
                current = ((List<?>) current).get((Integer) step);
            }
        }
        return current;
    }

    static long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** Renders a Java string as a JSON string literal, for building request bodies by hand. */
    static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private Object parseValue() {
        char c = input.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // '{'
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            pos++; // ':'
            skipWhitespace();
            map.put(key, parseValue());
            skipWhitespace();
            if (input.charAt(pos++) == '}') {
                break;
            }
        }
        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        pos++; // '['
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(parseValue());
            skipWhitespace();
            if (input.charAt(pos++) == ']') {
                break;
            }
        }
        return list;
    }

    private String parseString() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = input.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                char escape = input.charAt(pos++);
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(input.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(escape);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Boolean parseBoolean() {
        if (input.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        pos += 5; // "false"
        return Boolean.FALSE;
    }

    private Object parseNull() {
        pos += 4; // "null"
        return null;
    }

    private Number parseNumber() {
        int start = pos;
        while (pos < input.length() && "-+.eE0123456789".indexOf(input.charAt(pos)) >= 0) {
            pos++;
        }
        String token = input.substring(start, pos);
        if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
            return Double.parseDouble(token);
        }
        return Long.parseLong(token);
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        return input.charAt(pos);
    }
}
