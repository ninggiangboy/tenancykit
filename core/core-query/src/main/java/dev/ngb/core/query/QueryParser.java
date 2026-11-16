package dev.ngb.core.query;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.util.PageSize;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the query string of a listing into a {@link ListQuery} checked against a {@link FilterSchema}.
 *
 * <p>Grammar: {@code filter[field][op]=value} joined with AND; {@code filter[or][i][field][op]=value} forms OR branches
 * (predicates sharing an {@code i} are AND-ed, branches are OR-ed); {@code sort=-a,b}; {@code limit}; {@code offset}
 * or {@code cursor}; {@code page_info=true|cursor}. {@code in} and {@code nin} take comma-separated values. A parameter
 * the parser does not know is refused rather than ignored, because a silently dropped filter returns more rows than the
 * caller asked for.
 */
public final class QueryParser {

    public static final int MAX_PREDICATES = 20;
    public static final int MAX_IN_VALUES = 100;
    public static final int MAX_OR_BRANCHES = 10;
    public static final int MAX_SORT_KEYS = 4;
    public static final int MAX_OFFSET = 10_000;
    public static final int DEFAULT_LIMIT = 50;

    private static final Pattern AND_KEY = Pattern.compile("^filter\\[([a-z][a-z0-9_.]*)]\\[([a-z_]+)]$");
    private static final Pattern OR_KEY = Pattern.compile("^filter\\[or]\\[(\\d{1,3})]\\[([a-z][a-z0-9_.]*)]\\[([a-z_]+)]$");

    private QueryParser() {}

    /** @see #parse(FilterSchema, Map, Set) */
    public static ListQuery parse(FilterSchema schema, Map<String, List<String>> params) {
        return parse(schema, params, Set.of());
    }

    /**
     * @param params the request parameters by name
     * @param passthrough parameters the endpoint reads itself, which the parser leaves alone
     * @throws BadRequestException for anything the schema or a limit does not allow
     */
    public static ListQuery parse(FilterSchema schema, Map<String, List<String>> params, Set<String> passthrough) {
        List<Filter> all = new ArrayList<>();
        TreeMap<Integer, List<Filter>> branches = new TreeMap<>();
        String sort = null;
        String limit = null;
        String offset = null;
        String cursor = null;
        String pageInfo = null;
        int[] predicates = {0};
        for (Map.Entry<String, List<String>> param : params.entrySet()) {
            String key = param.getKey();
            List<String> values = param.getValue();
            switch (key) {
                case "sort" -> sort = single(key, values);
                case "limit" -> limit = single(key, values);
                case "offset" -> offset = single(key, values);
                case "cursor" -> cursor = single(key, values);
                case "page_info" -> pageInfo = single(key, values);
                default -> {
                    Matcher or = OR_KEY.matcher(key);
                    Matcher and = AND_KEY.matcher(key);
                    if (or.matches()) {
                        Filter filter = filter(schema, or.group(2), or.group(3), values, predicates);
                        branches.computeIfAbsent(Integer.parseInt(or.group(1)), i -> new ArrayList<>()).add(filter);
                    } else if (and.matches()) {
                        all.add(filter(schema, and.group(1), and.group(2), values, predicates));
                    } else if (!passthrough.contains(key)) {
                        throw new BadRequestException("the parameter " + key + " is not supported on this listing");
                    }
                }
            }
        }
        if (branches.size() > MAX_OR_BRANCHES) {
            throw new BadRequestException("a filter has at most " + MAX_OR_BRANCHES + " OR branches");
        }
        List<SortKey> order = schema.parseSort(sort);
        List<List<Filter>> anyOf = List.copyOf(branches.values());
        String shape = shape(all, anyOf, order);
        int size = limit == null ? PageSize.of(DEFAULT_LIMIT) : PageSize.of(integer("limit", limit, Integer.MIN_VALUE, Integer.MAX_VALUE));
        int skip = offset == null ? 0 : integer("offset", offset, 0, MAX_OFFSET);
        if (cursor != null && skip > 0) {
            throw new BadRequestException("send either offset or cursor, not both");
        }
        List<Object> after = cursor == null ? null : Cursor.decode(cursor, order, shape);
        return new ListQuery(all, anyOf, order, size, skip, after, pageFacts(pageInfo), shape);
    }

    /** @return what the answer's {@code page} carries: nothing, the cursor alone, or every fact */
    private static PageFacts pageFacts(String raw) {
        if (raw == null || raw.equals("false")) {
            return PageFacts.NONE;
        }
        return switch (raw) {
            case "true" -> PageFacts.FULL;
            case "cursor" -> PageFacts.CURSOR;
            default -> throw new BadRequestException("the parameter page_info takes true, false or cursor");
        };
    }

    private static Filter filter(FilterSchema schema, String fieldName, String opName, List<String> raw, int[] predicates) {
        predicates[0]++;
        if (predicates[0] > MAX_PREDICATES) {
            throw new BadRequestException("a filter has at most " + MAX_PREDICATES + " predicates");
        }
        FieldSpec field = schema.field(fieldName).orElseThrow(
                () -> new BadRequestException("the field " + fieldName + " cannot be filtered; filterable fields: " + schema.filterableNames()));
        Op op = Op.ofWire(opName).orElseThrow(() -> new BadRequestException("the operator " + opName + " is not supported"));
        if (!field.ops().contains(op)) {
            throw new BadRequestException("the operator " + op.wire() + " does not apply to field " + field.name() + "; allowed: "
                    + field.ops().stream().map(Op::wire).sorted().toList());
        }
        if (op == Op.IS_NULL) {
            return new Filter(field, op, List.of(bool(field.name() + "[is_null]", single(field.name(), raw))));
        }
        if (op.multiValued()) {
            List<Object> values = new ArrayList<>();
            for (String joined : raw) {
                for (String one : joined.split(",", -1)) {
                    values.add(field.type().parse(field, one));
                }
            }
            if (values.isEmpty() || values.size() > MAX_IN_VALUES) {
                throw new BadRequestException(op.wire() + " takes between 1 and " + MAX_IN_VALUES + " values");
            }
            return new Filter(field, op, values);
        }
        return new Filter(field, op, List.of(field.type().parse(field, single(field.name(), raw))));
    }

    private static String single(String name, List<String> values) {
        if (values.size() != 1) {
            throw new BadRequestException("the parameter " + name + " takes exactly one value");
        }
        return values.get(0);
    }

    private static int integer(String name, String raw, int min, int max) {
        try {
            int value = Integer.parseInt(raw);
            if (value < min || value > max) {
                throw new BadRequestException("the parameter " + name + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BadRequestException("the parameter " + name + " takes a whole number");
        }
    }

    private static boolean bool(String name, String raw) {
        if (raw == null || raw.equals("false")) {
            return false;
        }
        if (raw.equals("true")) {
            return true;
        }
        throw new BadRequestException("the parameter " + name + " takes true or false");
    }

    /** A digest of everything that decides which rows a cursor runs over: the filter and the ordering, not the paging. */
    private static String shape(List<Filter> all, List<List<Filter>> anyOf, List<SortKey> order) {
        StringBuilder text = new StringBuilder();
        text.append(canonical(all)).append('#');
        anyOf.stream().map(QueryParser::canonical).sorted().forEach(branch -> text.append(branch).append('/'));
        text.append('#');
        order.forEach(key -> text.append(key.wire()).append(','));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always present", e);
        }
    }

    private static String canonical(List<Filter> filters) {
        return filters.stream()
                .map(f -> f.field().name() + ':' + f.op().wire() + ':'
                        + String.join(",", f.values().stream().map(v -> f.op() == Op.IS_NULL ? v.toString() : f.field().type().format(v)).toList()))
                .sorted(Comparator.naturalOrder())
                .reduce("", (a, b) -> a + b + ';');
    }
}
