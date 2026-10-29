package purist.library.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import purist.library.query.QueryNode.All;
import purist.library.query.QueryNode.And;
import purist.library.query.QueryNode.Field;
import purist.library.query.QueryNode.Not;
import purist.library.query.QueryNode.Op;
import purist.library.query.QueryNode.Or;
import purist.library.query.QueryNode.Term;

/**
 * Recursive-descent parser for the foobar-style search subset (spec §8):
 *
 * <pre>
 * or      := and ("OR" and)*
 * and     := not (["AND"] not)*            (implicit AND between neighbours)
 * not     := "NOT" not | primary
 * primary := "(" or ")" | WORD op value | value
 * op      := "HAS" | "IS" | "GREATER" | "LESS"
 * value   := QUOTED | WORD
 * </pre>
 *
 * Keywords are case-insensitive when written bare; inside quotes they are plain text. A quote inside quotes is
 * written twice ({@code "say ""hi"""}); a backslash is an ordinary character, so Windows paths need no escaping.
 * The empty query is {@link All}.
 */
public final class QueryParser {

    /** Deep enough for any human query, shallow enough that recursion cannot overflow the stack. */
    private static final int MAX_DEPTH = 100;

    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,18}");

    private enum Type { LPAREN, RPAREN, QUOTED, WORD, END }

    private record Token(Type type, String text, int pos) {

        boolean isWord(String keyword) {
            return type == Type.WORD && text.equalsIgnoreCase(keyword);
        }

        boolean isOperator() {
            return type == Type.WORD && operator(text) != null;
        }

        boolean isLogicWord() {
            return isWord("AND") || isWord("OR");
        }
    }

    private final String text;
    private final List<Token> tokens;
    private int next;
    private int depth;

    private QueryParser(String text) {
        this.text = text;
        this.tokens = tokenize(text);
    }

    /**
     * Parses {@code query}. A null or blank query is {@link All}.
     *
     * @throws QueryException if the text is not a valid query
     */
    public static QueryNode parse(String query) {
        if (query == null || query.isBlank()) {
            return new All();
        }
        QueryParser p = new QueryParser(query);
        QueryNode node = p.parseOr();
        Token left = p.peek();
        if (left.type() == Type.RPAREN) {
            throw new QueryException("Unmatched ')'", left.pos());
        }
        if (left.type() != Type.END) { // cannot happen: parseOr only stops at ')' or the end
            throw new QueryException("Unexpected " + describe(left), left.pos());
        }
        return node;
    }

    // ---- grammar ----

    private QueryNode parseOr() {
        List<QueryNode> parts = new ArrayList<>();
        parts.add(parseAnd());
        while (peek().isWord("OR")) {
            next++;
            parts.add(parseAnd());
        }
        return parts.size() == 1 ? parts.get(0) : new Or(parts);
    }

    private QueryNode parseAnd() {
        List<QueryNode> parts = new ArrayList<>();
        parts.add(parseNot());
        while (true) {
            Token t = peek();
            if (t.isWord("AND")) {
                next++;
                parts.add(parseNot());
            } else if (startsPrimary(t)) {
                parts.add(parseNot());
            } else {
                break;
            }
        }
        return parts.size() == 1 ? parts.get(0) : new And(parts);
    }

    private QueryNode parseNot() {
        enter();
        try {
            if (peek().isWord("NOT")) {
                next++;
                return new Not(parseNot());
            }
            return parsePrimary();
        } finally {
            depth--;
        }
    }

    private QueryNode parsePrimary() {
        Token t = peek();
        switch (t.type()) {
            case LPAREN -> {
                next++;
                QueryNode inner = parseOr();
                Token close = peek();
                if (close.type() != Type.RPAREN) {
                    throw new QueryException("Missing ')' to close the '(' at position " + t.pos(), close.pos());
                }
                next++;
                return inner;
            }
            case QUOTED -> {
                next++;
                return new Term(t.text());
            }
            case WORD -> {
                if (t.isLogicWord()) {
                    throw new QueryException("Unexpected " + t.text().toUpperCase(Locale.ROOT)
                            + ": expected something to search for", t.pos());
                }
                next++;
                Token opToken = peek();
                if (opToken.isOperator()) {
                    next++;
                    return field(t, opToken);
                }
                return new Term(t.text());
            }
            case RPAREN -> throw new QueryException("Expected something to search for before ')'", t.pos());
            default -> throw new QueryException("Expected something to search for after '"
                    + previousText() + "'", t.pos());
        }
    }

    private QueryNode field(Token name, Token opToken) {
        Op op = operator(opToken.text());
        Token value = peek();
        if (value.type() != Type.WORD && value.type() != Type.QUOTED) {
            throw new QueryException("Expected a value after " + opToken.text().toUpperCase(Locale.ROOT),
                    value.pos());
        }
        next++;
        if ((op == Op.GREATER || op == Op.LESS) && !INTEGER.matcher(value.text()).matches()) {
            throw new QueryException(opToken.text().toUpperCase(Locale.ROOT) + " needs a whole number, not '"
                    + value.text() + "'", value.pos());
        }
        return new Field(name.text().toLowerCase(Locale.ROOT), op, value.text());
    }

    // ---- helpers ----

    private static boolean startsPrimary(Token t) {
        return switch (t.type()) {
            case LPAREN, QUOTED -> true;
            case WORD -> !t.isWord("OR"); // "AND" is consumed by the caller, so a primary never starts with it
            default -> false;
        };
    }

    private Token peek() {
        return tokens.get(next);
    }

    private String previousText() {
        return next == 0 ? "" : text.substring(tokens.get(next - 1).pos(), Math.min(text.length(), endOf(next - 1)));
    }

    private int endOf(int index) {
        return index + 1 < tokens.size() ? tokens.get(index + 1).pos() : text.length();
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new QueryException("The query is nested too deeply", peek().pos());
        }
    }

    private static Op operator(String word) {
        return switch (word.toUpperCase(Locale.ROOT)) {
            case "HAS" -> Op.HAS;
            case "IS" -> Op.IS;
            case "GREATER" -> Op.GREATER;
            case "LESS" -> Op.LESS;
            default -> null;
        };
    }

    private static String describe(Token t) {
        return t.type() == Type.END ? "end of query" : "'" + t.text() + "'";
    }

    // ---- tokens ----

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(') {
                out.add(new Token(Type.LPAREN, "(", i++));
            } else if (c == ')') {
                out.add(new Token(Type.RPAREN, ")", i++));
            } else if (c == '"') {
                int start = i++;
                StringBuilder b = new StringBuilder();
                boolean closed = false;
                while (i < n) {
                    char q = s.charAt(i++);
                    if (q == '"') {
                        if (i < n && s.charAt(i) == '"') {
                            b.append('"');
                            i++;
                        } else {
                            closed = true;
                            break;
                        }
                    } else {
                        b.append(q);
                    }
                }
                if (!closed) {
                    throw new QueryException("Missing closing quote", start);
                }
                out.add(new Token(Type.QUOTED, b.toString(), start));
            } else {
                int start = i;
                while (i < n && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '(' && s.charAt(i) != ')') {
                    i++;
                }
                out.add(new Token(Type.WORD, s.substring(start, i), start));
            }
        }
        out.add(new Token(Type.END, "", n));
        return out;
    }
}
