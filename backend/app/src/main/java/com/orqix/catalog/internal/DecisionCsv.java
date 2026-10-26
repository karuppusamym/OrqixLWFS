package com.orqix.catalog.internal;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * CSV form of a decision table (G3, docs/build/S89-decision-csv-io.md). Pure and thread-safe.
 *
 * <p>The format is deliberately narrow so that nothing needs guessing: one header record naming the hit policy, the
 * effective-date columns, typed input columns and typed output columns, then one record per rule in priority order
 * (top to bottom is the order of the table) and one final {@code default} record. Every deviation is reported with
 * the line and the column it was found at; nothing is coerced. Cells are text: no formula is ever evaluated here, and
 * on export a cell that a spreadsheet could read as a formula is prefixed with an apostrophe, which import removes
 * again, so an export imports back to the identical table.
 */
public final class DecisionCsv {

    /** Largest accepted file. A full table (50 rows, 30 inputs, 10 outputs) is far below this. */
    public static final int MAX_BYTES = 128 * 1024;
    /** Most records (lines of the table, including the header) read; a table has at most 52. */
    public static final int MAX_RECORDS = 60;
    public static final int MAX_RULES = 50;
    public static final int MAX_INPUTS = 30;
    public static final int MAX_OUTPUTS = 10;
    public static final int MAX_CELL_CHARS = 120;
    /** Most problems reported for one file. */
    public static final int MAX_PROBLEMS = 100;

    private static final List<String> OPERATORS = List.of("eq", "ne", "gte", "gt", "lt", "lte");
    private static final List<String> TYPES = List.of("text", "number", "boolean");
    private static final Pattern FIELD_REF = Pattern.compile(
            "^/(input/[a-z][A-Za-z0-9]{0,63}(/count|/sum/[a-z][A-Za-z0-9]{0,63})?|steps/(?!.*__)[A-Za-z][A-Za-z0-9_-]{0,63}/[a-z][A-Za-z0-9]{0,63})$");
    private static final Pattern OUTPUT_KEY = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");
    private static final Pattern NUMBER = Pattern.compile("^-?(0|[1-9][0-9]{0,17})(\\.[0-9]{1,17})?$");
    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    /** First characters a spreadsheet may read as a formula or command (OWASP CSV injection), plus the escape itself. */
    private static final String RISKY_START = "=+-@\t\r'";

    private DecisionCsv() {
    }

    /** One problem with the 1-based line (record start) and 1-based column (cell) it was found at. */
    public record Problem(int line, int column, String code, String message) {
    }

    /** Result of {@link #parse}: exactly one of {@code table} and {@code problems} is meaningful. */
    public record Parsed(ObjectNode table, List<Problem> problems, Locator locator) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /**
     * Maps JSON pointers inside the produced table back to CSV positions, so the compiler's findings about the table
     * (overlap, unknown field, type mismatch ...) are reported against a line and a column of the file.
     */
    public static final class Locator {
        private final List<Integer> ruleLines = new ArrayList<>();
        private final List<List<int[]>> inputCells = new ArrayList<>();
        private final List<Map<String, Integer>> outputCols = new ArrayList<>();
        private final List<int[]> windowCols = new ArrayList<>();
        private int defaultLine = 1;
        private final Map<String, Integer> defaultCols = new LinkedHashMap<>();
        private int firstOutputCol = 1;

        /** @param rest pointer relative to the table, for example {@code /rows/2/then/risk}; {line, column} */
        public int[] locate(String rest) {
            String[] part = rest.split("/");
            if (part.length >= 3 && part[1].equals("rows") && isIndex(part[2], ruleLines.size())) {
                int row = Integer.parseInt(part[2]);
                int line = ruleLines.get(row);
                String section = part.length > 3 ? part[3] : "";
                switch (section) {
                    case "when" -> {
                        List<int[]> cells = inputCells.get(row);
                        int pick = 0;
                        if (part.length > 5 && part[4].equals("all") && isIndex(part[5], cells.size())) {
                            pick = Integer.parseInt(part[5]);
                        }
                        return new int[] {line, cells.isEmpty() ? 1 : cells.get(pick)[0]};
                    }
                    case "then" -> {
                        Integer col = part.length > 4 ? outputCols.get(row).get(part[4]) : null;
                        return new int[] {line, col == null ? firstOutputCol : col};
                    }
                    case "effective" -> {
                        int[] w = windowCols.get(row);
                        return new int[] {line, part.length > 4 && part[4].equals("to") ? w[1] : w[0]};
                    }
                    default -> {
                        return new int[] {line, 1};
                    }
                }
            }
            if (part.length >= 2 && part[1].equals("default")) {
                Integer col = part.length > 2 ? defaultCols.get(part[2]) : null;
                return new int[] {defaultLine, col == null ? firstOutputCol : col};
            }
            return new int[] {1, 1};
        }

        private static boolean isIndex(String text, int size) {
            try {
                int i = Integer.parseInt(text);
                return i >= 0 && i < size;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ parse

    /** Parses a file into a decision table (hit policy, rows, default). The table key and version are not part of it. */
    public static Parsed parse(byte[] bytes) {
        List<Problem> problems = new ArrayList<>();
        Locator locator = new Locator();
        List<Rec> records = records(bytes, problems);
        ObjectNode built = null;
        if (problems.isEmpty()) {
            built = interpret(records, problems, locator);
        }
        if (problems.size() > MAX_PROBLEMS) {
            problems = new ArrayList<>(problems.subList(0, MAX_PROBLEMS));
        }
        if (!problems.isEmpty()) {
            return new Parsed(null, List.copyOf(problems), locator);
        }
        return new Parsed(built, List.of(), locator);
    }

    private record Rec(int line, List<String> cells) {
    }

    private static List<Rec> records(byte[] bytes, List<Problem> problems) {
        if (bytes.length == 0) {
            problems.add(new Problem(1, 1, "empty", "The file is empty."));
            return List.of();
        }
        if (bytes.length > MAX_BYTES) {
            problems.add(new Problem(1, 1, "tooLarge", "The file is larger than " + MAX_BYTES / 1024 + " KiB."));
            return List.of();
        }
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF
                ? 3 : 0;
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes, start, bytes.length - start);
        CharBuffer out = CharBuffer.allocate(bytes.length - start + 1);
        CoderResult result = decoder.decode(in, out, true);
        if (result.isError()) {
            int line = 1;
            int column = 1;
            for (int i = start; i < in.position(); i++) {
                if (bytes[i] == '\n') {
                    line++;
                    column = 1;
                } else if ((bytes[i] & 0xC0) != 0x80) {
                    column++;
                }
            }
            problems.add(new Problem(line, column, "encoding",
                    "The file is not valid UTF-8. Save it as CSV UTF-8 (comma delimited)."));
            return List.of();
        }
        out.flip();
        String text = out.toString();

        List<Rec> records = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        int line = 1;
        int recordLine = 1;
        int column = 1;
        boolean quoted = false;
        boolean afterQuote = false;
        boolean cellStarted = false;
        boolean quotedInRecord = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                        afterQuote = true;
                    }
                } else if (c == '\n' || c == '\r') {
                    problems.add(new Problem(line, cells.size() + 1, "lineBreakInCell",
                            "A cell contains a line break. Cells hold a single line of text."));
                    return List.of();
                } else {
                    controlCheck(c, line, cells.size() + 1, problems);
                    cell.append(c);
                }
                continue;
            }
            if (c == ',' || c == '\n' || c == '\r') {
                if (c == '\r') {
                    if (i + 1 >= n || text.charAt(i + 1) != '\n') {
                        problems.add(new Problem(line, cells.size() + 1, "lineBreak",
                                "A lone carriage return was found. Use a normal line break."));
                        return List.of();
                    }
                    i++;
                    c = '\n';
                }
                cells.add(cell.toString());
                cell.setLength(0);
                afterQuote = false;
                cellStarted = false;
                if (c == '\n') {
                    if (cells.size() == 1 && cells.get(0).isEmpty() && !quotedInRecord) {
                        // An empty line: allowed only as the very last line break of the file.
                        if (i + 1 < n) {
                            problems.add(new Problem(line, 1, "emptyLine", "Empty lines are not allowed."));
                            return List.of();
                        }
                    } else {
                        records.add(new Rec(recordLine, cells));
                    }
                    cells = new ArrayList<>();
                    quotedInRecord = false;
                    line++;
                    recordLine = line;
                    if (records.size() > MAX_RECORDS) {
                        problems.add(new Problem(line, 1, "tooManyLines", "The file has more than " + MAX_RECORDS
                                + " lines. A table has a header, at most " + MAX_RULES + " rules and a default."));
                        return List.of();
                    }
                }
                continue;
            }
            if (afterQuote) {
                problems.add(new Problem(line, cells.size() + 1, "quote",
                        "After the closing quote only a comma or the end of the line may follow."));
                return List.of();
            }
            if (c == '"') {
                if (cellStarted) {
                    problems.add(new Problem(line, cells.size() + 1, "quote",
                            "A quote may only start a cell. Put the whole cell in quotes and double any quote inside."));
                    return List.of();
                }
                quoted = true;
                quotedInRecord = true;
                cellStarted = true;
                continue;
            }
            cellStarted = true;
            controlCheck(c, line, cells.size() + 1, problems);
            cell.append(c);
        }
        if (quoted) {
            problems.add(new Problem(recordLine, cells.size() + 1, "quote", "A quoted cell is never closed."));
            return List.of();
        }
        if (cellStarted || afterQuote || !cells.isEmpty()) {
            cells.add(cell.toString());
            records.add(new Rec(recordLine, cells));
        }
        if (records.size() > MAX_RECORDS) {
            problems.add(new Problem(MAX_RECORDS + 1, 1, "tooManyLines", "The file has more than " + MAX_RECORDS + " lines."));
            return List.of();
        }
        for (Rec r : records) {
            for (int i = 0; i < r.cells().size(); i++) {
                if (r.cells().get(i).length() > MAX_CELL_CHARS) {
                    problems.add(new Problem(r.line(), i + 1, "cellTooLong",
                            "A cell is longer than " + MAX_CELL_CHARS + " characters."));
                }
            }
        }
        return records;
    }

    private static void controlCheck(char c, int line, int column, List<Problem> problems) {
        if (c < 0x20 || c == 0x7F) {
            problems.add(new Problem(line, column, "controlCharacter", "The cell contains a control character"
                    + (c == '\t' ? " (a tab)" : "") + ", which is not allowed."));
        }
    }

    // ------------------------------------------------------------------ interpret

    private record Column(String type, String name) {
    }

    private static ObjectNode interpret(List<Rec> records, List<Problem> problems, Locator locator) {
        if (records.isEmpty()) {
            problems.add(new Problem(1, 1, "empty", "The file has no header line."));
            return null;
        }
        Rec header = records.get(0);
        List<String> head = new ArrayList<>();
        for (int c = 0; c < header.cells().size(); c++) {
            head.add(unescape(header.cells().get(c), header.line(), c + 1, problems));
        }
        if (!problems.isEmpty()) {
            return null;
        }
        // Fixed columns.
        String hit = head.isEmpty() ? "" : head.get(0);
        if (!hit.equals("hitPolicy:UNIQUE") && !hit.equals("hitPolicy:FIRST")) {
            problems.add(new Problem(1, 1, "header", "The first header cell must be hitPolicy:UNIQUE or hitPolicy:FIRST"
                    + (hit.isEmpty() ? "." : ", not \"" + shorten(hit) + "\".")));
            return null;
        }
        if (head.size() < 3 || !head.get(1).equals("effectiveFrom")) {
            problems.add(new Problem(1, 2, "header", "The second header cell must be effectiveFrom."));
            return null;
        }
        if (!head.get(2).equals("effectiveTo")) {
            problems.add(new Problem(1, 3, "header", "The third header cell must be effectiveTo."));
            return null;
        }
        List<Column> inputs = new ArrayList<>();
        List<Column> outputs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int c = 3; c < head.size(); c++) {
            String h = head.get(c);
            boolean input = h.startsWith("in:");
            boolean output = h.startsWith("out:");
            if (!input && !output) {
                problems.add(new Problem(1, c + 1, "header", "Unknown column \"" + shorten(h)
                        + "\". After effectiveTo come in:<type>:<field> columns, then out:<type>:<result> columns."));
                continue;
            }
            String[] parts = h.substring(input ? 3 : 4).split(":", 2);
            if (parts.length != 2 || !TYPES.contains(parts[0])) {
                problems.add(new Problem(1, c + 1, "header", "A column heading is " + (input ? "in" : "out")
                        + ":<type>:<name> with the type text, number or boolean."));
                continue;
            }
            boolean valid = input ? FIELD_REF.matcher(parts[1]).matches() : OUTPUT_KEY.matcher(parts[1]).matches();
            if (!valid) {
                problems.add(new Problem(1, c + 1, "header", input
                        ? "\"" + shorten(parts[1]) + "\" is not a field reference such as /input/amount or /steps/<step>/<name>."
                        : "\"" + shorten(parts[1]) + "\" is not a result name (a lowercase letter, then letters and digits)."));
                continue;
            }
            if (!seen.add(h)) {
                problems.add(new Problem(1, c + 1, "header", "The column " + shorten(h) + " appears twice."));
                continue;
            }
            if (input) {
                if (!outputs.isEmpty()) {
                    problems.add(new Problem(1, c + 1, "header", "Input columns (in:) must come before output columns (out:)."));
                    continue;
                }
                inputs.add(new Column(parts[0], parts[1]));
            } else {
                outputs.add(new Column(parts[0], parts[1]));
            }
        }
        if (inputs.isEmpty() && problems.isEmpty()) {
            problems.add(new Problem(1, 4, "header", "There must be at least one in:<type>:<field> column."));
        }
        if (outputs.isEmpty() && problems.isEmpty()) {
            problems.add(new Problem(1, head.size() + 1, "header", "There must be at least one out:<type>:<result> column."));
        }
        if (inputs.size() > MAX_INPUTS && problems.isEmpty()) {
            problems.add(new Problem(1, 4 + MAX_INPUTS, "header", "At most " + MAX_INPUTS + " input columns are allowed."));
        }
        if (outputs.size() > MAX_OUTPUTS && problems.isEmpty()) {
            problems.add(new Problem(1, 4 + inputs.size() + MAX_OUTPUTS, "header",
                    "At most " + MAX_OUTPUTS + " output columns are allowed."));
        }
        if (!problems.isEmpty()) {
            return null;
        }
        int width = head.size();
        int firstOut = 4 + inputs.size();
        locator.firstOutputCol = firstOut;

        JsonNodeFactory f = JsonNodeFactory.instance;
        ArrayNode rows = f.arrayNode();
        ObjectNode defaults = null;
        int defaultAt = -1;
        for (int r = 1; r < records.size(); r++) {
            Rec rec = records.get(r);
            if (defaultAt >= 0) {
                problems.add(new Problem(rec.line(), 1, "afterDefault", "Nothing may follow the default line."));
                return null;
            }
            if (rec.cells().size() != width) {
                problems.add(new Problem(rec.line(), Math.min(rec.cells().size(), width) + 1, "cellCount", "This line has "
                        + rec.cells().size() + " cells, but the header has " + width + "."));
                continue;
            }
            List<String> cells = new ArrayList<>();
            int before = problems.size();
            for (int c = 0; c < width; c++) {
                cells.add(unescape(rec.cells().get(c), rec.line(), c + 1, problems));
            }
            if (problems.size() > before) {
                continue;
            }
            String kind = cells.get(0);
            if (!kind.equals("rule") && !kind.equals("default")) {
                problems.add(new Problem(rec.line(), 1, "rowKind", "The first cell must be rule or default"
                        + (kind.isEmpty() ? "." : ", not \"" + shorten(kind) + "\".")));
                continue;
            }
            boolean isDefault = kind.equals("default");
            // Effective window.
            ObjectNode effective = f.objectNode();
            int[] windowCols = {2, 3};
            for (int w = 0; w < 2; w++) {
                String d = cells.get(1 + w);
                if (d.isEmpty()) {
                    continue;
                }
                if (isDefault) {
                    problems.add(new Problem(rec.line(), 2 + w, "defaultCell", "The default line has no effective dates."));
                } else if (!DATE.matcher(d).matches()) {
                    problems.add(new Problem(rec.line(), 2 + w, "date", "Write the date as YYYY-MM-DD, not \""
                            + shorten(d) + "\"."));
                } else {
                    effective.put(w == 0 ? "from" : "to", d);
                }
            }
            if (effective.has("from") && effective.has("to")
                    && effective.get("from").stringValue().compareTo(effective.get("to").stringValue()) > 0) {
                problems.add(new Problem(rec.line(), 3, "date", "effectiveTo is before effectiveFrom."));
            }
            // Inputs.
            ObjectNode when = null;
            List<ObjectNode> terms = new ArrayList<>();
            List<int[]> termCells = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                String cell = cells.get(3 + i);
                if (cell.isEmpty()) {
                    continue;
                }
                if (isDefault) {
                    problems.add(new Problem(rec.line(), 4 + i, "defaultCell",
                            "The default line has no conditions; leave this cell empty."));
                    continue;
                }
                ObjectNode term = condition(inputs.get(i), cell, rec.line(), 4 + i, problems);
                if (term != null) {
                    terms.add(term);
                    termCells.add(new int[] {4 + i});
                }
            }
            if (!isDefault && terms.isEmpty() && problems.size() == before) {
                problems.add(new Problem(rec.line(), 4, "noCondition",
                        "A rule needs at least one condition; leave no rule line without one."));
            }
            // Outputs.
            ObjectNode then = f.objectNode();
            Map<String, Integer> outCols = new LinkedHashMap<>();
            for (int o = 0; o < outputs.size(); o++) {
                String cell = cells.get(3 + inputs.size() + o);
                int col = firstOut + o;
                if (cell.isEmpty()) {
                    problems.add(new Problem(rec.line(), col, "emptyResult", "Every result needs a value; "
                            + outputs.get(o).name() + " is empty."));
                    continue;
                }
                JsonNode literal = literal(outputs.get(o).type(), cell, rec.line(), col, problems);
                if (literal != null) {
                    then.set(outputs.get(o).name(), literal);
                    outCols.put(outputs.get(o).name(), col);
                }
            }
            if (problems.size() > before) {
                continue;
            }
            if (isDefault) {
                defaults = then;
                defaultAt = r;
                locator.defaultLine = rec.line();
                locator.defaultCols.putAll(outCols);
                continue;
            }
            if (rows.size() >= MAX_RULES) {
                problems.add(new Problem(rec.line(), 1, "tooManyRules", "At most " + MAX_RULES + " rules are allowed."));
                continue;
            }
            when = terms.size() == 1 ? terms.get(0) : f.objectNode();
            if (terms.size() > 1) {
                ArrayNode all = ((ObjectNode) when).putArray("all");
                terms.forEach(all::add);
            }
            ObjectNode row = f.objectNode();
            row.set("when", when);
            row.set("then", then);
            if (effective.size() > 0) {
                row.set("effective", effective);
            }
            rows.add(row);
            locator.ruleLines.add(rec.line());
            locator.inputCells.add(termCells);
            locator.outputCols.add(outCols);
            locator.windowCols.add(windowCols);
        }
        if (!problems.isEmpty()) {
            return null;
        }
        if (rows.isEmpty()) {
            problems.add(new Problem(records.get(records.size() - 1).line(), 1, "noRules",
                    "There must be at least one rule line before the default line."));
            return null;
        }
        if (defaults == null) {
            problems.add(new Problem(records.get(records.size() - 1).line() + 1, 1, "noDefault",
                    "The last line must be the default line (default in the first cell)."));
            return null;
        }
        ObjectNode table = f.objectNode();
        table.put("hitPolicy", hit.substring("hitPolicy:".length()));
        table.set("rows", rows);
        table.set("default", defaults);
        return table;
    }

    private static ObjectNode condition(Column column, String cell, int line, int col, List<Problem> problems) {
        int space = cell.indexOf(' ');
        if (space < 0) {
            problems.add(new Problem(line, col, "condition", "Write a comparison and a value, for example gte 100 or eq approved."));
            return null;
        }
        String operator = cell.substring(0, space);
        if (!OPERATORS.contains(operator)) {
            problems.add(new Problem(line, col, "operator", "Unknown comparison \"" + shorten(operator)
                    + "\". Use eq, ne, gte, gt, lt or lte."));
            return null;
        }
        String value = cell.substring(space + 1);
        JsonNode literal = literal(column.type(), value, line, col, problems);
        if (literal == null) {
            return null;
        }
        ObjectNode term = JsonNodeFactory.instance.objectNode();
        term.put("fieldRef", column.name());
        term.put("operator", operator);
        term.set("value", literal);
        return term;
    }

    private static JsonNode literal(String type, String text, int line, int col, List<Problem> problems) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (!text.equals(text.strip()) || text.isEmpty()) {
            problems.add(new Problem(line, col, "spaces", "Use exactly one space after the comparison and no space at the end."));
            return null;
        }
        switch (type) {
            case "number" -> {
                if (!NUMBER.matcher(text).matches()) {
                    problems.add(new Problem(line, col, "number", "\"" + shorten(text) + "\" is not a plain number. Use digits"
                            + " with an optional minus sign and decimal point, at most 18 digits (no thousands separator,"
                            + " no exponent, no currency)."));
                    return null;
                }
                return f.numberNode(new BigDecimal(text));
            }
            case "boolean" -> {
                if (!text.equals("true") && !text.equals("false")) {
                    problems.add(new Problem(line, col, "boolean", "\"" + shorten(text) + "\" is not true or false (lowercase)."));
                    return null;
                }
                return f.booleanNode(text.equals("true"));
            }
            default -> {
                return f.stringNode(text);
            }
        }
    }

    /** Removes the spreadsheet-safety apostrophe added on export; any other leading apostrophe is ambiguous. */
    private static String unescape(String cell, int line, int col, List<Problem> problems) {
        String value = cell;
        if (value.startsWith("'")) {
            if (value.length() < 2 || RISKY_START.indexOf(value.charAt(1)) < 0) {
                problems.add(new Problem(line, col, "apostrophe", "A leading apostrophe is only allowed before = + - @ or"
                        + " another apostrophe (it is the spreadsheet-safety prefix). Remove it."));
                return value;
            }
            value = value.substring(1);
        }
        if (!value.equals(value.strip())) {
            problems.add(new Problem(line, col, "spaces", "The cell starts or ends with a space. Remove it."));
        }
        return value;
    }

    private static String shorten(String text) {
        return text.length() > 40 ? text.substring(0, 40) + "..." : text;
    }

    // ------------------------------------------------------------------ export

    /** The table cannot be written as CSV (for example an OR row); {@code reasons} name the rows. */
    public static final class NotRepresentable extends Exception {
        private final transient List<String> reasons;

        NotRepresentable(List<String> reasons) {
            super(reasons.get(0));
            this.reasons = List.copyOf(reasons);
        }

        public List<String> reasons() {
            return reasons;
        }
    }

    /** Writes the table as CSV text (LF line breaks, no BOM, all text is plain ASCII-safe literals). */
    public static String export(JsonNode table) throws NotRepresentable {
        return write(table, false);
    }

    /**
     * A starting file with the table's columns, one example rule and the default line. It imports as it is, but is
     * meant to be edited.
     */
    public static String template(JsonNode table) throws NotRepresentable {
        return write(table, true);
    }

    private static String write(JsonNode table, boolean template) throws NotRepresentable {
        List<String> reasons = new ArrayList<>();
        JsonNode rows = table.path("rows");
        JsonNode def = table.path("default");
        String policy = table.path("hitPolicy").asString("");
        if (!policy.equals("UNIQUE") && !policy.equals("FIRST")) {
            reasons.add("The table has no hit policy.");
        }
        // Columns in order of first appearance.
        Map<String, String> inputType = new LinkedHashMap<>();
        for (int r = 0; r < rows.size(); r++) {
            JsonNode when = rows.get(r).path("when");
            if (when.has("any") && !template) {
                reasons.add("Row " + (r + 1) + " is an OR row (Any comparison). CSV holds one condition per field; "
                        + "split it into one row per alternative under the first-match policy.");
            }
            for (JsonNode term : terms(when)) {
                String ref = term.path("fieldRef").asString("");
                String type = typeOf(term.path("value"));
                String known = inputType.putIfAbsent(ref, type);
                if (known != null && !known.equals(type) && !template) {
                    reasons.add("Row " + (r + 1) + " compares " + ref + " with " + type + " but another row uses " + known + ".");
                }
            }
            if (!when.has("any") && !when.has("all") && !template && !when.has("fieldRef")) {
                reasons.add("Row " + (r + 1) + " has no condition.");
            }
        }
        if (!template) {
            for (int r = 0; r < rows.size(); r++) {
                JsonNode when = rows.get(r).path("when");
                List<String> refs = new ArrayList<>();
                for (JsonNode term : terms(when)) {
                    refs.add(term.path("fieldRef").asString(""));
                }
                if (new LinkedHashSet<>(refs).size() != refs.size()) {
                    reasons.add("Row " + (r + 1) + " tests the same field twice.");
                }
            }
        }
        if (inputType.isEmpty()) {
            reasons.add("The table has no conditions to write.");
        }
        if (inputType.size() > MAX_INPUTS) {
            reasons.add("The table tests more than " + MAX_INPUTS + " fields.");
        }
        Map<String, String> outputType = new LinkedHashMap<>();
        def.properties().forEach(e -> outputType.put(e.getKey(), typeOf(e.getValue())));
        if (outputType.isEmpty() || outputType.size() > MAX_OUTPUTS) {
            reasons.add("The table must have 1 to " + MAX_OUTPUTS + " results.");
        }
        for (int r = 0; r < rows.size() && !template; r++) {
            JsonNode then = rows.get(r).path("then");
            if (!keys(then).equals(keys(def))) {
                reasons.add("Row " + (r + 1) + " does not set exactly the same results as the default.");
                continue;
            }
            for (Map.Entry<String, JsonNode> e : then.properties()) {
                if (!outputType.get(e.getKey()).equals(typeOf(e.getValue()))) {
                    reasons.add("Row " + (r + 1) + " sets " + e.getKey() + " to a different kind of value than the default.");
                }
            }
        }
        if (rows.size() > MAX_RULES) {
            reasons.add("The table has more than " + MAX_RULES + " rows.");
        }
        if (!reasons.isEmpty()) {
            throw new NotRepresentable(reasons);
        }
        List<String> inputRefs = new ArrayList<>(inputType.keySet());
        List<String> outputKeys = new ArrayList<>(outputType.keySet());
        StringBuilder csv = new StringBuilder();
        List<String> head = new ArrayList<>(List.of("hitPolicy:" + policy, "effectiveFrom", "effectiveTo"));
        inputRefs.forEach(ref -> head.add("in:" + inputType.get(ref) + ":" + ref));
        outputKeys.forEach(key -> head.add("out:" + outputType.get(key) + ":" + key));
        line(csv, head, reasons);

        if (template) {
            List<String> example = new ArrayList<>(List.of("rule", "", ""));
            for (int i = 0; i < inputRefs.size(); i++) {
                example.add(i == 0 ? switch (inputType.get(inputRefs.get(0))) {
                    case "number" -> "gte 0";
                    case "boolean" -> "eq true";
                    default -> "eq example";
                } : "");
            }
            outputKeys.forEach(key -> example.add(plain(def.path(key))));
            line(csv, example, reasons);
        } else {
            for (JsonNode row : rows) {
                List<String> cells = new ArrayList<>(List.of("rule", row.path("effective").path("from").asString(""),
                        row.path("effective").path("to").asString("")));
                Map<String, JsonNode> byRef = new LinkedHashMap<>();
                for (JsonNode term : terms(row.path("when"))) {
                    byRef.put(term.path("fieldRef").asString(""), term);
                }
                for (String ref : inputRefs) {
                    JsonNode term = byRef.get(ref);
                    cells.add(term == null ? "" : term.path("operator").asString("") + " " + plain(term.path("value")));
                }
                for (String key : outputKeys) {
                    cells.add(plain(row.path("then").path(key)));
                }
                line(csv, cells, reasons);
            }
        }
        List<String> last = new ArrayList<>(List.of("default", "", ""));
        inputRefs.forEach(ref -> last.add(""));
        outputKeys.forEach(key -> last.add(plain(def.path(key))));
        line(csv, last, reasons);
        if (!reasons.isEmpty()) {
            throw new NotRepresentable(reasons);
        }
        String text = csv.toString();
        if (!template) {
            // The promise is an exact round trip, so prove it for this very table before handing the file out.
            Parsed back = parse(text.getBytes(StandardCharsets.UTF_8));
            if (!back.ok()) {
                back.problems().forEach(p -> reasons.add("Line " + p.line() + ", column " + p.column() + ": " + p.message()));
                throw new NotRepresentable(reasons);
            }
            if (!same(table.path("rows"), back.table().path("rows")) || !same(def, back.table().path("default"))) {
                throw new NotRepresentable(List.of("The table cannot be written as CSV and read back identically."));
            }
        }
        return text;
    }

    /** Structural equality where numbers compare by value and the conditions of an All row by field (order-free). */
    static boolean same(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isObject() && b.isObject()) {
            if (!keys(a).equals(keys(b))) {
                return false;
            }
            for (Map.Entry<String, JsonNode> e : a.properties()) {
                JsonNode other = b.get(e.getKey());
                boolean ok = e.getKey().equals("all") && e.getValue().isArray() && other.isArray()
                        ? sameTerms(e.getValue(), other) : same(e.getValue(), other);
                if (!ok) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!same(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static boolean sameTerms(JsonNode a, JsonNode b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, JsonNode> byRef = new LinkedHashMap<>();
        b.forEach(t -> byRef.put(t.path("fieldRef").asString(""), t));
        for (JsonNode t : a) {
            JsonNode other = byRef.get(t.path("fieldRef").asString(""));
            if (other == null || !same(t, other)) {
                return false;
            }
        }
        return true;
    }

    private static List<JsonNode> terms(JsonNode when) {
        List<JsonNode> terms = new ArrayList<>();
        if (when.has("all")) {
            when.get("all").forEach(terms::add);
        } else if (when.has("any")) {
            when.get("any").forEach(terms::add);
        } else if (when.has("fieldRef")) {
            terms.add(when);
        }
        return terms;
    }

    private static Set<String> keys(JsonNode object) {
        Set<String> keys = new LinkedHashSet<>();
        object.propertyNames().forEach(keys::add);
        return keys;
    }

    private static String typeOf(JsonNode value) {
        return value.isNumber() ? "number" : value.isBoolean() ? "boolean" : "text";
    }

    private static String plain(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue().toPlainString();
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "true" : "false";
        }
        return value.asString("");
    }

    /** Appends one record. A value that cannot be read back exactly is reported instead of written. */
    private static void line(StringBuilder out, List<String> cells, List<String> reasons) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String cell = cells.get(i);
            String problem = unwritable(cell);
            if (problem != null) {
                reasons.add(problem);
                continue;
            }
            // Spreadsheet formula injection (OWASP): a risky first character gets an apostrophe, which import removes.
            if (!cell.isEmpty() && RISKY_START.indexOf(cell.charAt(0)) >= 0) {
                cell = "'" + cell;
            }
            if (cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0) {
                out.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                out.append(cell);
            }
        }
        out.append('\n');
    }

    private static String unwritable(String cell) {
        for (int i = 0; i < cell.length(); i++) {
            char c = cell.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return "A value contains a control character, which CSV cannot carry.";
            }
        }
        if (!cell.equals(cell.strip())) {
            return "The value \"" + shorten(cell) + "\" starts or ends with a space, which CSV import would not accept.";
        }
        if (cell.length() > MAX_CELL_CHARS) {
            return "A value is longer than " + MAX_CELL_CHARS + " characters.";
        }
        return null;
    }
}
