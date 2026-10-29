package purist.library.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import purist.library.query.QueryNode.All;
import purist.library.query.QueryNode.And;
import purist.library.query.QueryNode.Field;
import purist.library.query.QueryNode.Not;
import purist.library.query.QueryNode.Op;
import purist.library.query.QueryNode.Or;
import purist.library.query.QueryNode.Term;

class QueryParserTest {

    private static Term t(String s) {
        return new Term(s);
    }

    @Test
    void precedence() {
        assertEquals(new Or(List.of(t("a"), new And(List.of(t("b"), t("c"))))), QueryParser.parse("a OR b c"));
    }

    @Test
    void andBindsTighterThanOrOnBothSides() {
        assertEquals(new Or(List.of(new And(List.of(t("a"), t("b"))), t("c"))), QueryParser.parse("a b OR c"));
    }

    @Test
    void parenthesesOverridePrecedence() {
        assertEquals(new And(List.of(new Or(List.of(t("a"), t("b"))), t("c"))), QueryParser.parse("(a OR b) c"));
    }

    @Test
    void explicitAndEqualsImplicitAnd() {
        assertEquals(QueryParser.parse("a b"), QueryParser.parse("a AND b"));
        assertEquals(new And(List.of(t("a"), t("b"), t("c"))), QueryParser.parse("a AND b c"));
    }

    @Test
    void notBindsTightest() {
        assertEquals(new Or(List.of(new Not(t("a")), t("b"))), QueryParser.parse("NOT a OR b"));
        assertEquals(new And(List.of(new Not(t("a")), t("b"))), QueryParser.parse("NOT a b"));
        assertEquals(new Not(new Not(t("a"))), QueryParser.parse("NOT NOT a"));
        assertEquals(new Not(new Or(List.of(t("a"), t("b")))), QueryParser.parse("NOT (a OR b)"));
    }

    @Test
    void keywordsAreCaseInsensitive() {
        assertEquals(QueryParser.parse("a OR NOT b AND c"), QueryParser.parse("a or not b and c"));
        assertEquals(new Field("artist", Op.HAS, "x"), QueryParser.parse("artist has x"));
    }

    @Test
    void fieldOperators() {
        assertEquals(new Field("artist", Op.HAS, "ted"), QueryParser.parse("artist HAS ted"));
        assertEquals(new Field("format", Op.IS, "dsf"), QueryParser.parse("format IS dsf"));
        assertEquals(new Field("samplerate", Op.GREATER, "96000"), QueryParser.parse("samplerate GREATER 96000"));
        assertEquals(new Field("bitdepth", Op.LESS, "24"), QueryParser.parse("bitdepth LESS 24"));
    }

    @Test
    void fieldNamesAreFoldedToLowerCase() {
        assertEquals(new Field("artist", Op.IS, "Ted"), QueryParser.parse("ARTIST IS Ted"));
    }

    @Test
    void quotedValue() {
        assertEquals(new Field("artist", Op.IS, "ted nugent"), QueryParser.parse("artist IS \"ted nugent\""));
    }

    @Test
    void quotedTermAndDoubledQuote() {
        assertEquals(new Term("two words"), QueryParser.parse("\"two words\""));
        assertEquals(new Term("say \"hi\""), QueryParser.parse("\"say \"\"hi\"\"\""));
    }

    @Test
    void quotedKeywordIsJustText() {
        assertEquals(new And(List.of(t("and"), t("or"))), QueryParser.parse("\"and\" \"or\""));
    }

    @Test
    void backslashIsLiteralSoPathsWork() {
        assertEquals(new Field("path", Op.HAS, "E:\\Music\\Ted"), QueryParser.parse("path HAS \"E:\\Music\\Ted\""));
    }

    @Test
    void termsAndFieldsMix() {
        assertEquals(new And(List.of(t("ted"), new Field("kind", Op.IS, "dsd"), new Not(t("live")))),
                QueryParser.parse("ted kind IS dsd NOT live"));
    }

    @Test
    void emptyIsAll() {
        assertInstanceOf(All.class, QueryParser.parse(""));
        assertInstanceOf(All.class, QueryParser.parse("   \t "));
        assertInstanceOf(All.class, QueryParser.parse(null));
    }

    @Test
    void singleChildGroupsCollapse() {
        assertEquals(t("a"), QueryParser.parse("(a)"));
        assertEquals(t("a"), QueryParser.parse("((a))"));
    }

    @Test
    void unbalancedParenThrowsWithPosition() {
        QueryException open = assertThrows(QueryException.class, () -> QueryParser.parse("(a OR b"));
        assertEquals(7, open.position(), "points just past the end, where ')' was expected");
        QueryException close = assertThrows(QueryException.class, () -> QueryParser.parse("a OR b)"));
        assertEquals(6, close.position(), "points at the stray ')'");
    }

    @Test
    void emptyParenthesesThrow() {
        QueryException e = assertThrows(QueryException.class, () -> QueryParser.parse("a ()"));
        assertEquals(3, e.position());
    }

    @Test
    void danglingOperatorsThrow() {
        assertEquals(4, assertThrows(QueryException.class, () -> QueryParser.parse("a OR")).position());
        assertEquals(5, assertThrows(QueryException.class, () -> QueryParser.parse("a AND")).position());
        assertEquals(3, assertThrows(QueryException.class, () -> QueryParser.parse("NOT")).position());
        assertEquals(0, assertThrows(QueryException.class, () -> QueryParser.parse("OR a")).position());
        assertEquals(10, assertThrows(QueryException.class, () -> QueryParser.parse("artist HAS")).position());
    }

    @Test
    void unterminatedQuoteThrowsAtTheOpeningQuote() {
        QueryException e = assertThrows(QueryException.class, () -> QueryParser.parse("a \"oops"));
        assertEquals(2, e.position());
    }

    @Test
    void greaterAndLessNeedANumber() {
        QueryException e = assertThrows(QueryException.class, () -> QueryParser.parse("samplerate GREATER lots"));
        assertEquals(19, e.position());
        assertEquals(new Field("date", Op.LESS, "-5"), QueryParser.parse("date LESS -5"));
    }

    @Test
    void messageIsHumanReadable() {
        QueryException e = assertThrows(QueryException.class, () -> QueryParser.parse("(a"));
        assertTrue(e.getMessage().contains("')'"), e.getMessage());
    }
}
