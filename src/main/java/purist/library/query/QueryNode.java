package purist.library.query;

import java.util.List;
import java.util.Objects;

/**
 * A parsed foobar-style search query (spec §8). {@link QueryParser#parse} builds the tree and
 * {@link SqlBuilder#build} turns it into a SQL condition.
 */
public sealed interface QueryNode {

    /** How a {@link Field} compares the tag (or pseudo-field) with its value. */
    enum Op {
        /** The value is a case-insensitive substring. */
        HAS,
        /** The value is the whole text, ignoring case. */
        IS,
        /** The field, read as an integer, is greater than the value. */
        GREATER,
        /** The field, read as an integer, is less than the value. */
        LESS
    }

    /** Every child matches. A parsed {@code And} always has at least two children. */
    record And(List<QueryNode> children) implements QueryNode {
        public And {
            children = List.copyOf(children);
        }
    }

    /** At least one child matches. A parsed {@code Or} always has at least two children. */
    record Or(List<QueryNode> children) implements QueryNode {
        public Or {
            children = List.copyOf(children);
        }
    }

    /** The child does not match. */
    record Not(QueryNode child) implements QueryNode {
        public Not {
            Objects.requireNonNull(child, "child");
        }
    }

    /**
     * {@code field op value}. The parser folds {@code field} to lower case; the builder maps it to a tag or to
     * one of the pseudo-fields {@code format samplerate bitdepth channels kind path}.
     */
    record Field(String field, Op op, String value) implements QueryNode {
        public Field {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(value, "value");
        }
    }

    /** A bare word: a case-insensitive substring of any tag. */
    record Term(String text) implements QueryNode {
        public Term {
            Objects.requireNonNull(text, "text");
        }
    }

    /** The empty query: every track. */
    record All() implements QueryNode {
    }
}
