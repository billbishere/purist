package purist.ui.browse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import purist.library.query.SqlBuilder.Sql;

/** The SQL that lists the library's tracks that sit directly in one folder (not in its sub-folders). */
public final class FolderQuery {

    /** The order folder tracks are listed in: file name ignoring case, then subsong. */
    public static final String ORDER = "t.path COLLATE NOCASE, t.subsong";

    private static final char ESCAPE = '^';

    private FolderQuery() {
    }

    /**
     * Matches the tracks whose path is {@code folder}, a separator, then a name with no further separator. LIKE
     * ignores case for ASCII, as Windows paths do; {@code %}, {@code _} and the escape character in the folder's
     * name are escaped so they match themselves.
     */
    public static Sql directTracks(Path folder) {
        String text = folder.toString();
        String prefix = text.endsWith("\\") ? text : text + "\\";
        String escaped = escape(prefix);
        List<Object> params = new ArrayList<>();
        params.add(escaped + "%");
        params.add(escaped + "%\\%");
        String where = "t.path LIKE ? ESCAPE '" + ESCAPE + "' AND t.path NOT LIKE ? ESCAPE '" + ESCAPE + "'";
        return new Sql(where, params);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (char c : s.toCharArray()) {
            if (c == '%' || c == '_' || c == ESCAPE) {
                sb.append(ESCAPE);
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
