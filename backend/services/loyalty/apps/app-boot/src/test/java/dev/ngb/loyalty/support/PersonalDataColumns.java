package dev.ngb.loyalty.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The classification of every text, jsonb and array column of loyalty's schema, read from
 * {@code personal-data-columns.txt} in the test resources.
 *
 * <p>Two tests read the same file, which is the point: the column test proves every column is classified, and the erasure
 * test proves that the columns classified as personal hold nothing after an erasure. A list that only one of them read
 * could drift into a comfortable lie.
 */
public final class PersonalDataColumns {

    private static final String RESOURCE = "/personal-data-columns.txt";

    /** A column name is {@code table.column}, both lowercase, so a test can build SQL from it and a typo fails loudly. */
    private static final String NAME = "[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*";

    private static final List<String> PERSONAL = new ArrayList<>();
    private static final List<String> PURGED = new ArrayList<>();
    private static final List<String> OTHER = new ArrayList<>();

    static {
        parse();
    }

    private PersonalDataColumns() {}

    /** The columns an erasure clears: the ones the erasure test then asserts are empty. */
    public static Set<String> personal() {
        return new TreeSet<>(PERSONAL);
    }

    /** The columns a person's data can reach and the housekeeping retention then removes with their row. */
    public static Set<String> purged() {
        return new TreeSet<>(PURGED);
    }

    /** The columns that cannot hold a person's data at all. */
    public static Set<String> other() {
        return new TreeSet<>(OTHER);
    }

    /** Every classified column, so a test can compare the file with the schema in both directions. */
    public static Set<String> all() {
        Set<String> all = new TreeSet<>(PERSONAL);
        all.addAll(PURGED);
        all.addAll(OTHER);
        return all;
    }

    /** The tables a personal column may live in, because those are the tables an erasure rewrites. */
    public static Set<String> personalTables() {
        Set<String> tables = new TreeSet<>();
        PERSONAL.forEach(column -> tables.add(table(column)));
        return tables;
    }

    public static String table(String column) {
        return column.substring(0, column.indexOf('.'));
    }

    public static String name(String column) {
        return column.substring(column.indexOf('.') + 1);
    }

    private static void parse() {
        List<String> section = null;
        for (String raw : lines()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                section = sectionOf(line);
                continue;
            }
            if (!line.matches(NAME)) {
                throw new IllegalStateException(RESOURCE + ": " + line + " is not a table.column");
            }
            if (section == null) {
                throw new IllegalStateException(RESOURCE + ": " + line + " is outside a section");
            }
            section.add(line);
        }
        List<String> all = new ArrayList<>(PERSONAL);
        all.addAll(PURGED);
        all.addAll(OTHER);
        if (new TreeSet<>(all).size() != all.size()) {
            throw new IllegalStateException(RESOURCE + ": a column is classified more than once");
        }
    }

    private static List<String> sectionOf(String header) {
        return switch (header) {
            case "[PERSONAL]" -> PERSONAL;
            case "[PURGED]" -> PURGED;
            case "[OTHER]" -> OTHER;
            default -> throw new IllegalStateException(RESOURCE + ": unknown section " + header);
        };
    }

    private static List<String> lines() {
        try (InputStream stream = PersonalDataColumns.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath");
            }
            return List.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }
}
