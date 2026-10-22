package dev.aemdebug.jdi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Tiny, side-effect free expression language for breakpoint conditions and logpoint messages, evaluated on
 * captured values (no code runs in the target JVM). Examples: {@code title == null},
 * {@code resource.path contains 'hero' && count > 2}, {@code !enabled || name startsWith "x"}.
 */
public final class Condition {

    /** An expression node. */
    interface Node {
        Object eval(Map<String, Object> vars);
    }

    private final String source;
    private final Node root;

    private Condition(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    public static Condition parse(String text) {
        Parser parser = new Parser(tokenize(text));
        Node node = parser.or();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Unexpected '" + parser.peek() + "' in condition: " + text);
        }
        return new Condition(text, node);
    }

    public String source() {
        return source;
    }

    public boolean test(Map<String, Object> vars) {
        return truthy(root.eval(vars));
    }

    /** Evaluates a single expression, e.g. for {placeholders} in logpoint messages. */
    public static Object evaluate(String expression, Map<String, Object> vars) {
        return parse(expression).root.eval(vars);
    }

    /** Replaces {expr} placeholders with their values. */
    public static String format(String template, Map<String, Object> vars) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) {
                sb.append(template, i, template.length());
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                sb.append(template, i, template.length());
                break;
            }
            sb.append(template, i, open);
            String expression = template.substring(open + 1, close).trim();
            try {
                sb.append(String.valueOf(evaluate(expression, vars)));
            } catch (RuntimeException e) {
                sb.append("<").append(expression).append(": ").append(e.getMessage()).append(">");
            }
            i = close + 1;
        }
        return sb.toString();
    }

    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        return true;
    }

    /** Resolves a dotted name against locals first, then fields of 'this'. */
    static Object resolve(String path, Map<String, Object> vars) {
        String[] parts = path.split("\\.");
        Object current;
        int start = 1;
        if (vars.containsKey(parts[0])) {
            current = vars.get(parts[0]);
        } else if (vars.get("this") instanceof Map<?, ?> && ((Map<?, ?>) vars.get("this")).containsKey(parts[0])) {
            current = ((Map<?, ?>) vars.get("this")).get(parts[0]);
        } else {
            throw new IllegalArgumentException("unknown variable '" + parts[0] + "'");
        }
        for (int i = start; i < parts.length; i++) {
            if (current instanceof Map<?, ?>) {
                current = ((Map<?, ?>) current).get(parts[i]);
            } else if (parts[i].equals("length") && current instanceof String) {
                current = ((String) current).length();
            } else {
                return null;
            }
        }
        return current;
    }

    // ------------------------------------------------------------------ tokenizer

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'' || c == '"') {
                int end = i + 1;
                StringBuilder sb = new StringBuilder();
                while (end < text.length() && text.charAt(end) != c) {
                    if (text.charAt(end) == '\\' && end + 1 < text.length()) {
                        end++;
                    }
                    sb.append(text.charAt(end));
                    end++;
                }
                if (end >= text.length()) {
                    throw new IllegalArgumentException("Unterminated string in condition: " + text);
                }
                tokens.add("\u0000" + sb);
                i = end + 1;
            } else if (Character.isDigit(c) || (c == '-' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))
                    && (tokens.isEmpty() || isOperator(tokens.get(tokens.size() - 1))))) {
                int end = i + 1;
                while (end < text.length() && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(text.substring(i, end));
                i = end;
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < text.length() && (Character.isJavaIdentifierPart(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(text.substring(i, end));
                i = end;
            } else {
                String two = i + 1 < text.length() ? text.substring(i, i + 2) : "";
                if (two.equals("==") || two.equals("!=") || two.equals(">=") || two.equals("<=") || two.equals("&&")
                        || two.equals("||")) {
                    tokens.add(two);
                    i += 2;
                } else if ("<>!()".indexOf(c) >= 0) {
                    tokens.add(String.valueOf(c));
                    i++;
                } else {
                    throw new IllegalArgumentException("Unexpected character '" + c + "' in condition: " + text);
                }
            }
        }
        return tokens;
    }

    private static boolean isOperator(String token) {
        return List.of("==", "!=", ">=", "<=", ">", "<", "&&", "||", "!", "(").contains(token);
    }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        private final List<String> tokens;
        private int position;

        Parser(List<String> tokens) {
            this.tokens = tokens;
        }

        boolean atEnd() {
            return position >= tokens.size();
        }

        String peek() {
            return atEnd() ? "" : tokens.get(position);
        }

        String next() {
            if (atEnd()) {
                throw new IllegalArgumentException("Incomplete condition");
            }
            return tokens.get(position++);
        }

        Node or() {
            Node left = and();
            while (peek().equals("||")) {
                next();
                Node l = left;
                Node r = and();
                left = vars -> truthy(l.eval(vars)) || truthy(r.eval(vars));
            }
            return left;
        }

        Node and() {
            Node left = unary();
            while (peek().equals("&&")) {
                next();
                Node l = left;
                Node r = unary();
                left = vars -> truthy(l.eval(vars)) && truthy(r.eval(vars));
            }
            return left;
        }

        Node unary() {
            if (peek().equals("!")) {
                next();
                Node inner = unary();
                return vars -> !truthy(inner.eval(vars));
            }
            return comparison();
        }

        Node comparison() {
            Node left = primary();
            String op = peek();
            switch (op) {
                case "==":
                case "!=":
                case ">":
                case "<":
                case ">=":
                case "<=":
                case "contains":
                case "startsWith":
                case "endsWith":
                case "matches": {
                    next();
                    Node right = primary();
                    return vars -> compare(op, left.eval(vars), right.eval(vars));
                }
                default:
                    return left;
            }
        }

        Node primary() {
            String token = next();
            if (token.equals("(")) {
                Node inner = or();
                if (!next().equals(")")) {
                    throw new IllegalArgumentException("Missing ')' in condition");
                }
                return inner;
            }
            if (token.startsWith("\u0000")) {
                String literal = token.substring(1);
                return vars -> literal;
            }
            if (token.equals("null")) {
                return vars -> null;
            }
            if (token.equals("true") || token.equals("false")) {
                Boolean literal = Boolean.valueOf(token);
                return vars -> literal;
            }
            if (Character.isDigit(token.charAt(0)) || token.charAt(0) == '-') {
                Double number = Double.valueOf(token);
                return vars -> number;
            }
            if (Character.isJavaIdentifierStart(token.charAt(0))) {
                return vars -> resolve(token, vars);
            }
            throw new IllegalArgumentException("Unexpected '" + token + "' in condition");
        }
    }

    static Object compare(String op, Object left, Object right) {
        switch (op) {
            case "==":
                return equalsLoose(left, right);
            case "!=":
                return !equalsLoose(left, right);
            case "contains":
                return left != null && right != null && String.valueOf(left).contains(String.valueOf(right));
            case "startsWith":
                return left != null && right != null && String.valueOf(left).startsWith(String.valueOf(right));
            case "endsWith":
                return left != null && right != null && String.valueOf(left).endsWith(String.valueOf(right));
            case "matches":
                return left != null && right != null && Pattern.compile(String.valueOf(right))
                        .matcher(String.valueOf(left)).find();
            default:
                Double a = toNumber(left);
                Double b = toNumber(right);
                if (a == null || b == null) {
                    return false;
                }
                switch (op) {
                    case ">":
                        return a > b;
                    case "<":
                        return a < b;
                    case ">=":
                        return a >= b;
                    default:
                        return a <= b;
                }
        }
    }

    static boolean equalsLoose(Object left, Object right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        Double a = toNumber(left);
        Double b = toNumber(right);
        if (a != null && b != null && (left instanceof Number || right instanceof Number)) {
            return a.doubleValue() == b.doubleValue();
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            return String.valueOf(left).equalsIgnoreCase(String.valueOf(right));
        }
        return Objects.equals(String.valueOf(left), String.valueOf(right));
    }

    static Double toNumber(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Character) {
            return (double) (Character) value;
        }
        if (value instanceof String) {
            try {
                return Double.valueOf((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
