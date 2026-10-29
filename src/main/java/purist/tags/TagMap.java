package purist.tags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The tags of one track: case-insensitive keys, each holding one or more text values in the order they were
 * added. Keys are trimmed and upper-cased, and a few spellings fold onto one canonical key
 * ({@code ALBUM ARTIST} to {@code ALBUMARTIST}, {@code YEAR} to {@code DATE}, {@code TRACK} to
 * {@code TRACKNUMBER}); lookups fold the same way. Blank keys and blank or null values are ignored, so a
 * parser can add whatever it finds without checking. Values are otherwise stored as given.
 *
 * <p>Not thread-safe. Keys iterate in the order they were first added.
 */
public final class TagMap {

    private final Map<String, List<String>> map = new LinkedHashMap<>();

    /** Adds {@code value} under {@code key}, after any values the key already holds. */
    public void add(String key, String value) {
        String k = normalize(key);
        if (k == null || value == null || value.isBlank()) {
            return;
        }
        map.computeIfAbsent(k, x -> new ArrayList<>(1)).add(value);
    }

    /** The first value of {@code key}, if it has any. */
    public Optional<String> first(String key) {
        List<String> values = map.get(normalize(key));
        return values == null ? Optional.empty() : Optional.of(values.get(0));
    }

    /** All values of {@code key} in the order they were added; empty if there are none. Unmodifiable. */
    public List<String> all(String key) {
        List<String> values = map.get(normalize(key));
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** An unmodifiable snapshot of every key and its values. Later changes to this map do not show in it. */
    public Map<String, List<String>> asMap() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Copies in the keys of {@code other} that this map does not have yet; keys already present are untouched. */
    public void putAllAbsent(TagMap other) {
        for (Map.Entry<String, List<String>> e : snapshot(other).entrySet()) {
            map.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    /** Copies in every key of {@code other}, replacing all values this map held for that key. */
    public void putAll(TagMap other) {
        map.putAll(snapshot(other));
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TagMap t && map.equals(t.map);
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }

    @Override
    public String toString() {
        return map.toString();
    }

    /** A deep copy of {@code other}'s entries, so merging a map into itself or sharing lists cannot happen. */
    private static Map<String, List<String>> snapshot(TagMap other) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : other.map.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }

    /** The canonical key, or null if there is none to be had. */
    private static String normalize(String key) {
        if (key == null) {
            return null;
        }
        String k = key.strip().toUpperCase(Locale.ROOT);
        if (k.isEmpty()) {
            return null;
        }
        return switch (k) {
            case "ALBUM ARTIST" -> "ALBUMARTIST";
            case "YEAR" -> "DATE";
            case "TRACK" -> "TRACKNUMBER";
            default -> k;
        };
    }
}
