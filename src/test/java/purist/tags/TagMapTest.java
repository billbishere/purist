package purist.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TagMapTest {

    @Test
    void keysAreUpperCasedAndTrimmed() {
        TagMap t = new TagMap();
        t.add("  title ", "Song");
        assertEquals(Optional.of("Song"), t.first("TITLE"));
        assertEquals(Optional.of("Song"), t.first("title"));
        assertEquals(Optional.of("Song"), t.first(" Title  "));
        assertEquals(List.of("TITLE"), List.copyOf(t.asMap().keySet()));
    }

    @Test
    void aliasesFoldOntoTheCanonicalKey() {
        TagMap t = new TagMap();
        t.add("Album Artist", "A");
        t.add("year", "1999");
        t.add("Track", "3");
        assertEquals(Optional.of("A"), t.first("ALBUMARTIST"));
        assertEquals(Optional.of("1999"), t.first("DATE"));
        assertEquals(Optional.of("3"), t.first("TRACKNUMBER"));
        // Lookups go through the same aliasing.
        assertEquals(Optional.of("A"), t.first("album artist"));
        assertEquals(Optional.of("1999"), t.first("YEAR"));
        assertEquals(Optional.of("3"), t.first("track"));
        assertEquals(List.of("ALBUMARTIST", "DATE", "TRACKNUMBER"), List.copyOf(t.asMap().keySet()));
    }

    @Test
    void aliasAndCanonicalKeysShareOneValueList() {
        TagMap t = new TagMap();
        t.add("DATE", "1999");
        t.add("YEAR", "2000");
        assertEquals(List.of("1999", "2000"), t.all("DATE"));
    }

    @Test
    void blankValuesAreIgnored() {
        TagMap t = new TagMap();
        t.add("TITLE", "");
        t.add("TITLE", "   ");
        t.add("TITLE", "\t\n");
        t.add("TITLE", null);
        assertTrue(t.isEmpty());
        assertEquals(Optional.empty(), t.first("TITLE"));
        assertEquals(List.of(), t.all("TITLE"));
        assertTrue(t.asMap().isEmpty());
    }

    @Test
    void blankOrNullKeysAreIgnored() {
        TagMap t = new TagMap();
        t.add("", "x");
        t.add("   ", "x");
        t.add(null, "x");
        assertTrue(t.isEmpty());
    }

    @Test
    void multipleValuesKeepInsertionOrder() {
        TagMap t = new TagMap();
        t.add("ARTIST", "One");
        t.add("ARTIST", "Two");
        t.add("artist", "Three");
        assertEquals(List.of("One", "Two", "Three"), t.all("ARTIST"));
        assertEquals(Optional.of("One"), t.first("ARTIST"));
    }

    @Test
    void valuesAreStoredAsGiven() {
        TagMap t = new TagMap();
        t.add("TITLE", "  Padded  ");
        assertEquals(Optional.of("  Padded  "), t.first("TITLE"));
    }

    @Test
    void missingKeyGivesEmptyResults() {
        TagMap t = new TagMap();
        assertEquals(Optional.empty(), t.first("NOPE"));
        assertEquals(List.of(), t.all("NOPE"));
        assertEquals(Optional.empty(), t.first(null));
    }

    @Test
    void returnedViewsCannotChangeTheMap() {
        TagMap t = new TagMap();
        t.add("ARTIST", "One");
        assertThrows(UnsupportedOperationException.class, () -> t.all("ARTIST").add("x"));
        assertThrows(UnsupportedOperationException.class, () -> t.asMap().put("X", List.of("y")));
        assertThrows(UnsupportedOperationException.class, () -> t.asMap().get("ARTIST").add("x"));
        // A snapshot taken earlier does not see later additions.
        Map<String, List<String>> before = t.asMap();
        t.add("ARTIST", "Two");
        assertEquals(List.of("One"), before.get("ARTIST"));
        assertEquals(List.of("One", "Two"), t.all("ARTIST"));
    }

    @Test
    void putAllAbsentOnlyFillsMissingKeys() {
        TagMap base = new TagMap();
        base.add("TITLE", "Base title");
        base.add("ARTIST", "Base A");
        base.add("ARTIST", "Base B");
        TagMap other = new TagMap();
        other.add("TITLE", "Other title");
        other.add("ALBUM", "Other album 1");
        other.add("ALBUM", "Other album 2");
        base.putAllAbsent(other);
        assertEquals(List.of("Base title"), base.all("TITLE"));
        assertEquals(List.of("Base A", "Base B"), base.all("ARTIST"));
        assertEquals(List.of("Other album 1", "Other album 2"), base.all("ALBUM"));
    }

    @Test
    void putAllReplacesWholeKeys() {
        TagMap base = new TagMap();
        base.add("TITLE", "Base title");
        base.add("ARTIST", "Base A");
        base.add("ARTIST", "Base B");
        base.add("COMMENT", "keep me");
        TagMap other = new TagMap();
        other.add("TITLE", "New title");
        other.add("ARTIST", "New A");
        other.add("GENRE", "Rock");
        base.putAll(other);
        assertEquals(List.of("New title"), base.all("TITLE"));
        assertEquals(List.of("New A"), base.all("ARTIST"));
        assertEquals(List.of("Rock"), base.all("GENRE"));
        assertEquals(List.of("keep me"), base.all("COMMENT"));
    }

    @Test
    void mergingWithItselfOrAnEmptyMapIsHarmless() {
        TagMap t = new TagMap();
        t.add("TITLE", "x");
        t.putAll(t);
        t.putAllAbsent(t);
        t.putAll(new TagMap());
        t.putAllAbsent(new TagMap());
        assertEquals(List.of("x"), t.all("TITLE"));
    }

    @Test
    void mergedValuesAreCopiedNotShared() {
        TagMap a = new TagMap();
        a.add("ARTIST", "One");
        TagMap b = new TagMap();
        b.putAll(a);
        a.add("ARTIST", "Two");
        assertEquals(List.of("One"), b.all("ARTIST"));
    }

    @Test
    void equalMapsAreEqual() {
        TagMap a = new TagMap();
        a.add("TITLE", "x");
        TagMap b = new TagMap();
        b.add("title", "x");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        b.add("ARTIST", "y");
        assertFalse(a.equals(b));
    }
}
