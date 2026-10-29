package purist.library.query;

/** A search query that cannot be understood, with where in the text the problem is. */
public final class QueryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int position;

    /**
     * @param message  what is wrong, in words for the user
     * @param position 0-based index into the query text; the text length when the query ended too soon
     */
    public QueryException(String message, int position) {
        super(message);
        this.position = position;
    }

    /** 0-based index into the query text where the problem was found. */
    public int position() {
        return position;
    }
}
