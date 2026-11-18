package dev.ngb.core.query;

import dev.ngb.core.error.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The opaque position of a page: the sort values of the last row served and a digest of the filter and sort it was
 * issued for. Not signed: a forged cursor only moves the start of a read within rows the caller may already see, since
 * tenant isolation and caller scoping are applied by the query itself.
 */
final class Cursor {

    private static final String VERSION = "1";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_IN = Base64.getUrlDecoder();

    private Cursor() {}

    static String encode(String shape, List<SortKey> sort, List<Object> values) {
        if (values.size() != sort.size()) {
            throw new IllegalArgumentException("a cursor needs one value per sort key");
        }
        StringBuilder text = new StringBuilder(VERSION).append('|').append(shape);
        for (int i = 0; i < sort.size(); i++) {
            String value = sort.get(i).field().type().format(values.get(i));
            text.append('|').append(URL.encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        return URL.encodeToString(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    static List<Object> decode(String token, List<SortKey> sort, String shape) {
        String[] parts;
        try {
            parts = new String(URL_IN.decode(token), StandardCharsets.UTF_8).split("\\|", -1);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("the cursor is not valid");
        }
        if (parts.length != sort.size() + 2 || !parts[0].equals(VERSION)) {
            throw new BadRequestException("the cursor is not valid");
        }
        if (!parts[1].equals(shape)) {
            throw new BadRequestException("the cursor was issued for a different filter or sort");
        }
        List<Object> values = new ArrayList<>(sort.size());
        for (int i = 0; i < sort.size(); i++) {
            FieldSpec field = sort.get(i).field();
            String raw;
            try {
                raw = new String(URL_IN.decode(parts[i + 2]), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("the cursor is not valid");
            }
            try {
                values.add(field.type().parseStored(field, raw));
            } catch (BadRequestException e) {
                throw new BadRequestException("the cursor is not valid");
            }
        }
        return values;
    }
}
