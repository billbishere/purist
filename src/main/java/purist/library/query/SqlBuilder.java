package purist.library.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import purist.library.query.QueryNode.All;
import purist.library.query.QueryNode.And;
import purist.library.query.QueryNode.Field;
import purist.library.query.QueryNode.Not;
import purist.library.query.QueryNode.Or;
import purist.library.query.QueryNode.Term;

/**
 * Turns a {@link QueryNode} into a SQL condition over {@code tracks t} for {@code LibraryDb.tracks(where, params,
 * orderBy)} (spec §8). Everything the user typed, field names included, goes into the bound parameters: the SQL
 * text is assembled only from fixed fragments and a whitelist of column names.
 */
public final class SqlBuilder {

    /** A condition and the values for its {@code ?} marks, in order. */
    public record Sql(String where, List<Object> params) {
        public Sql {
            params = List.copyOf(params);
        }
    }

    /** A pseudo-field: a column of {@code tracks}, and whether it holds a number. */
    private record Column(String sql, boolean numeric) {
    }

    private static final Map<String, Column> PSEUDO_FIELDS = Map.of(
            "format", new Column("t.format", false),
            "samplerate", new Column("t.rate", true),
            "bitdepth", new Column("t.bits", true),
            "channels", new Column("t.channels", true),
            "kind", new Column("t.kind", false),
            "path", new Column("t.path", false));

    private static final String TAG_SELECT = "EXISTS(SELECT 1 FROM tags g WHERE g.track_id=t.id";
    private static final String LIKE_ESCAPE = " ESCAPE '\\'";

    private SqlBuilder() {
    }

    /**
     * The condition for {@code node}. The empty query ({@link All}) gives {@code "1"}.
     *
     * @throws QueryException if a GREATER or LESS value is not a whole number (the parser already rejects that;
     *                        this guards trees built by hand)
     */
    public static Sql build(QueryNode node) {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        append(node, where, params);
        return new Sql(where.toString(), params);
    }

    private static void append(QueryNode node, StringBuilder sql, List<Object> params) {
        switch (node) {
            case All a -> sql.append('1');
            case Term t -> {
                sql.append(TAG_SELECT).append(" AND g.value LIKE ?").append(LIKE_ESCAPE).append(')');
                params.add(contains(t.text()));
            }
            case Not n -> {
                // A comparison with an unknown column is NULL, and NOT NULL is NULL: count it as no match first.
                sql.append("NOT COALESCE(");
                append(n.child(), sql, params);
                sql.append(", 0)");
            }
            case And a -> join(a.children(), " AND ", "1", sql, params);
            case Or o -> join(o.children(), " OR ", "0", sql, params);
            case Field f -> field(f, sql, params);
        }
    }

    private static void join(List<QueryNode> children, String op, String empty, StringBuilder sql,
            List<Object> params) {
        if (children.isEmpty()) {
            sql.append(empty);
            return;
        }
        sql.append('(');
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sql.append(op);
            }
            append(children.get(i), sql, params);
        }
        sql.append(')');
    }

    private static void field(Field f, StringBuilder sql, List<Object> params) {
        Column column = PSEUDO_FIELDS.get(f.field().toLowerCase(Locale.ROOT));
        if (column != null) {
            pseudo(column, f, sql, params);
            return;
        }
        sql.append(TAG_SELECT).append(" AND g.field=? AND ");
        params.add(tagKey(f.field()));
        switch (f.op()) {
            case HAS -> {
                sql.append("g.value LIKE ?").append(LIKE_ESCAPE);
                params.add(contains(f.value()));
            }
            case IS -> {
                sql.append("g.value = ? COLLATE NOCASE");
                params.add(f.value());
            }
            case GREATER -> {
                sql.append("CAST(g.value AS INTEGER) > ?");
                params.add(number(f.value()));
            }
            case LESS -> {
                sql.append("CAST(g.value AS INTEGER) < ?");
                params.add(number(f.value()));
            }
        }
        sql.append(')');
    }

    private static void pseudo(Column column, Field f, StringBuilder sql, List<Object> params) {
        String col = column.sql();
        switch (f.op()) {
            case HAS -> {
                sql.append(col).append(" LIKE ?").append(LIKE_ESCAPE);
                params.add(contains(f.value()));
            }
            case IS -> {
                Object number = column.numeric() ? numberOrNull(f.value()) : null;
                if (number != null) {
                    sql.append(col).append(" = ?");
                    params.add(number);
                } else {
                    sql.append(col).append(" = ? COLLATE NOCASE");
                    params.add(f.value());
                }
            }
            case GREATER, LESS -> {
                String cmp = f.op() == QueryNode.Op.GREATER ? " > ?" : " < ?";
                sql.append(column.numeric() ? col : "CAST(" + col + " AS INTEGER)").append(cmp);
                params.add(number(f.value()));
            }
        }
    }

    /** The key {@code TagMap} stores a tag under: upper case, with its three aliases folded. */
    private static String tagKey(String field) {
        String k = field.strip().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "YEAR" -> "DATE";
            case "TRACK" -> "TRACKNUMBER";
            default -> k;
        };
    }

    /** {@code %text%} with LIKE's wildcards and the escape character itself made literal. */
    private static String contains(String text) {
        StringBuilder b = new StringBuilder(text.length() + 2).append('%');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                b.append('\\');
            }
            b.append(c);
        }
        return b.append('%').toString();
    }

    private static Object number(String value) {
        Object n = numberOrNull(value);
        if (n == null) {
            throw new QueryException("'" + value + "' is not a whole number", 0);
        }
        return n;
    }

    /** An {@code Integer} when it fits, else a {@code Long}; null if {@code value} is not a whole number. */
    private static Object numberOrNull(String value) {
        try {
            long n = Long.parseLong(value.strip());
            return n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? (Object) (int) n : (Object) n;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
