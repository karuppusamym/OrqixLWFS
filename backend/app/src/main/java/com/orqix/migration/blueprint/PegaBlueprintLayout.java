package com.orqix.migration.blueprint;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.orqix.integration.DocumentExtraction.Mark;
import com.orqix.integration.DocumentExtraction.PageGeometry;
import com.orqix.integration.DocumentExtraction.TextRun;
import com.orqix.migration.blueprint.Transcription.*;

/**
 * Reader for Pega's Blueprint "Application Overview Document" PDF export (docs/build/S91), written against one genuine
 * export (Retail Loan Origination, 2024). It reads page geometry, not just text:
 * <ul>
 * <li>font size and weight tell section headings ("Case Types", "Personas", "Live Data"), item titles (a case type,
 * persona or data object), descriptions, stage titles, step cards and the field table apart;</li>
 * <li>x positions assign each step card to its stage column, also when a column continues on the next page;</li>
 * <li>the colour of the icon in front of a step card gives its kind: green user task, blue approval, yellow automation
 * or notification, orange decision. Other colours leave the kind empty.</li>
 * </ul>
 * It transcribes one case type (the requested one, else the first) with its stages, steps, decisions and fields, plus
 * the personas as roles and the Live Data objects as integrations. The export states no step roles, routes, rejection
 * paths, SLAs or outcomes, so those stay empty and surface as findings. The only inferences are proposals a reviewer
 * confirms: steps follow their stage order and their order within a stage, and a decision's default route is the next
 * listed step.
 * <p>
 * It also renders each page in reading order (stage by stage, steps indented under their stage, table cells separated
 * by tabs). That rendering is stored as the page text, so reviewers read the lifecycle column by column and every
 * element excerpt is found in its page. Text is data: nothing in a page can add sections, attributes or dispositions.
 */
public final class PegaBlueprintLayout implements BlueprintLayout {
    public static final String ID = "pega-blueprint";
    public static final String REVISION = "0.1";

    // Font sizes (points) of the export; each band has room for small variations.
    private static final float SECTION = 19f, DOCUMENT_TITLE = 28f, TITLE = 15.5f, BODY = 14f, STAGE = 12.5f;
    private static final float SAME_X = 1.5f, SAME_ROW = 1.5f, STAGE_ROW = 2.5f;
    private static final float ICON_MIN = 11f, ICON_MAX = 17f, ICON_GAP = 8f;
    /** A wrapped line joins one of the most recent blocks only; this bounds the work on pages with very many runs. */
    private static final int MERGE_WINDOW = 200;
    /** Reference icon colours of the export and the step kind each stands for. */
    private static final Map<Integer, String> ICON_KINDS = Map.of(0x357E38, "task", 0x0076D1, "approval", 0xFFC836, "automated", 0xF76923, "decision");
    private static final int ICON_TOLERANCE = 40;
    private static final Pattern SYSTEM_OF_RECORD = Pattern.compile("(?i)\\bconnects\\s+to\\s+(?:the\\s+)?(.{2,200}?)\\s+as\\s+(?:the\\s+|its\\s+)?system\\s+of\\s+record");
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\p{Co}\\p{Cs}\\p{Cc}]");

    @Override public String id() { return ID; }
    @Override public String revision() { return REVISION; }

    /**
     * Recognized when every page has geometry, a section heading reads "Case Types", and a field table header or a
     * step icon of a known colour is present.
     */
    @Override
    public boolean recognizes(List<Page> pages) {
        if (pages.isEmpty() || pages.stream().anyMatch(p -> p.geometry() == null)) return false;
        boolean caseTypes = false, structure = false;
        for (Page page : pages) {
            for (TextRun run : page.geometry().runs()) {
                String text = clean(run.text());
                if (run.bold() && run.size() >= SECTION && text.equalsIgnoreCase("Case Types")) caseTypes = true;
                if (run.bold() && run.size() < TITLE && text.equalsIgnoreCase("Field Name")) structure = true;
            }
            for (Mark mark : page.geometry().marks()) if (icon(mark) && kind(mark.rgb()) != null) structure = true;
        }
        return caseTypes && structure;
    }

    @Override
    public Result transcribe(List<Page> pages, String requestedCaseType) {
        Reader reader = new Reader(requestedCaseType);
        for (Page page : pages) reader.page(page);
        return reader.result();
    }

    // ------------------------------------------------------------------------------------------------ blocks

    /** Wrapped lines of one card, title, paragraph or table cell joined into one block. */
    private record Block(int page, float x, float y, float last, float size, boolean bold, String text, Mark icon) { }
    private record Row(float y, List<Block> blocks) {
        String line() { return String.join("\t", blocks.stream().map(Block::text).toList()); }
        boolean anyBold() { return blocks.stream().anyMatch(Block::bold); }
        float maxSize() { return (float) blocks.stream().mapToDouble(Block::size).max().orElse(0); }
    }

    private static List<Block> blocks(Page page) {
        List<TextRun> runs = new ArrayList<>();
        for (TextRun run : page.geometry().runs()) if (!clean(run.text()).isEmpty()) runs.add(run);
        runs.sort(Comparator.comparingDouble(TextRun::y).thenComparingDouble(TextRun::x));
        List<Mark> icons = page.geometry().marks().stream().filter(PegaBlueprintLayout::icon).toList();
        List<Block> blocks = new ArrayList<>();
        for (TextRun run : runs) {
            String text = clean(run.text());
            Mark icon = run.size() < STAGE ? iconFor(icons, run) : null;
            int open = -1;
            for (int i = blocks.size() - 1; i >= Math.max(0, blocks.size() - MERGE_WINDOW) && open < 0; i--) {
                Block b = blocks.get(i);
                float gap = run.y() - b.last();
                if (Math.abs(b.x() - run.x()) <= SAME_X && b.bold() == run.bold() && Math.abs(b.size() - run.size()) <= 0.3f
                        && gap > SAME_ROW && gap <= b.size() * 1.05f && (icon == null || icon == b.icon())) open = i;
            }
            if (open < 0) { blocks.add(new Block(page.number(), run.x(), run.y(), run.y(), run.size(), run.bold(), text, icon)); continue; }
            Block b = blocks.get(open);
            String joined = b.text().matches(".*\\p{L}-$") && text.matches("^\\p{L}.*") ? b.text() + text : b.text() + " " + text;
            blocks.set(open, new Block(b.page(), b.x(), b.y(), run.y(), b.size(), b.bold(), joined, b.icon()));
        }
        blocks.sort(Comparator.comparingDouble(Block::y).thenComparingDouble(Block::x));
        return blocks;
    }

    private static List<Row> rows(List<Block> blocks) {
        List<Row> rows = new ArrayList<>();
        for (Block b : blocks) {
            if (!rows.isEmpty() && Math.abs(rows.getLast().y() - b.y()) <= SAME_ROW) rows.getLast().blocks().add(b);
            else rows.add(new Row(b.y(), new ArrayList<>(List.of(b))));
        }
        for (Row r : rows) r.blocks().sort(Comparator.comparingDouble(Block::x));
        return rows;
    }

    private static boolean icon(Mark m) {
        return m.width() >= ICON_MIN && m.width() <= ICON_MAX && m.height() >= ICON_MIN && m.height() <= ICON_MAX;
    }

    /** The icon square just left of a run whose height overlaps the run's line; the nearest one wins. */
    private static Mark iconFor(List<Mark> icons, TextRun run) {
        Mark best = null; double distance = Double.MAX_VALUE;
        for (Mark m : icons) {
            float right = m.x() + m.width();
            if (right > run.x() || run.x() - right > ICON_GAP) continue;
            if (m.y() > run.y() + 3 || m.y() + m.height() < run.y() - run.size()) continue;
            double d = Math.abs(m.y() + m.height() / 2 - (run.y() - run.size() / 3));
            if (d < distance) { distance = d; best = m; }
        }
        return best;
    }

    static String kind(int rgb) {
        for (var e : ICON_KINDS.entrySet()) {
            int ref = e.getKey();
            if (Math.abs(((rgb >> 16) & 0xFF) - ((ref >> 16) & 0xFF)) <= ICON_TOLERANCE && Math.abs(((rgb >> 8) & 0xFF) - ((ref >> 8) & 0xFF)) <= ICON_TOLERANCE
                    && Math.abs((rgb & 0xFF) - (ref & 0xFF)) <= ICON_TOLERANCE) return e.getValue();
        }
        return null;
    }

    private static String clean(String text) { return INVISIBLE.matcher(text == null ? "" : text).replaceAll("").replaceAll("\\s+", " ").strip(); }

    // ------------------------------------------------------------------------------------------------ reading

    private enum Section { NONE, CASE_TYPES, PERSONAS, LIVE_DATA, OTHER }

    private static final class Stage { final String name; final Block block; final int order; String id;
        Stage(String name, Block block, int order) { this.name = name; this.block = block; this.order = order; } }
    private record Item(String name, Block block, Stage stage, String kind) { }
    private record FieldRow(String name, String type, int page, String line) { }
    private record Titled(String name, Block title, String line, StringBuilder description) { }

    private static final class CaseDraft {
        final String name; final Block title; final String line;
        final List<Stage> stages = new ArrayList<>();
        final List<Item> items = new ArrayList<>();
        final List<FieldRow> fields = new ArrayList<>();
        List<Stage> lastRow = List.of();
        List<Float> columns = List.of();
        List<String> columnNames = List.of();
        boolean lifecycleStarted, inTable;
        CaseDraft(Block title, String line) { this.name = title.text(); this.title = title; this.line = line; }
    }

    private static final class Reader {
        private final String requested;
        private Section section = Section.NONE;
        private final List<CaseDraft> cases = new ArrayList<>();
        private CaseDraft current;
        private final List<Titled> personas = new ArrayList<>(), dataObjects = new ArrayList<>();
        private Titled openTitled;
        private final List<String> pageTexts = new ArrayList<>();
        private final List<List<Line>> pageLines = new ArrayList<>();
        // Per page: lines (with the case they belong to) and the lifecycle region being collected.
        private List<Line> lines;
        private final List<Block> region = new ArrayList<>();
        private int regionAt;

        /** A rendered line; {@code owner} is the case draft it belongs to (attributed only if that case is selected). */
        private record Line(String text, boolean attributed, CaseDraft owner) { }

        Reader(String requested) { this.requested = requested; }

        void page(Page page) {
            lines = new ArrayList<>();
            for (Row row : rows(blocks(page))) row(row);
            flushRegion();
            pageLines.add(lines);
        }

        private void row(Row row) {
            Block first = row.blocks().getFirst();
            if (row.anyBold() && row.maxSize() >= DOCUMENT_TITLE) { endItem(); lines.add(new Line(row.line(), false, null)); return; }
            if (row.anyBold() && row.maxSize() >= SECTION) {
                endItem();
                current = null;
                String name = clean(row.line()).toLowerCase(Locale.ROOT);
                section = switch (name) { case "case types" -> Section.CASE_TYPES; case "personas" -> Section.PERSONAS; case "live data" -> Section.LIVE_DATA; default -> Section.OTHER; };
                lines.add(new Line(row.line(), true, null));
                return;
            }
            boolean title = row.blocks().size() == 1 && first.bold() && first.size() >= TITLE;
            switch (section) {
                case CASE_TYPES -> caseRow(row, title);
                case PERSONAS, LIVE_DATA -> {
                    if (title) {
                        endItem();
                        openTitled = new Titled(trim(first.text(), 200), first, row.line(), new StringBuilder());
                        (section == Section.PERSONAS ? personas : dataObjects).add(openTitled);
                        lines.add(new Line(row.line(), true, null));
                    } else if (openTitled != null && !row.anyBold() && row.maxSize() >= BODY) {
                        openTitled.description().append(openTitled.description().isEmpty() ? "" : " ").append(row.line());
                        lines.add(new Line(row.line(), true, null));
                    } else { endItem(); lines.add(new Line(row.line(), false, null)); }
                }
                default -> lines.add(new Line(row.line(), false, null));
            }
        }

        private void caseRow(Row row, boolean title) {
            Block first = row.blocks().getFirst();
            if (title) {
                endItem();
                current = new CaseDraft(new Block(first.page(), first.x(), first.y(), first.last(), first.size(), true, trim(first.text(), 200), null), row.line());
                cases.add(current);
                lines.add(new Line(row.line(), true, current));
                return;
            }
            if (current == null) { lines.add(new Line(row.line(), false, null)); return; }
            if (row.anyBold() && row.blocks().stream().anyMatch(b -> b.bold() && b.text().equalsIgnoreCase("Field Name"))) {
                flushRegion();
                current.inTable = true;
                current.columns = row.blocks().stream().map(Block::x).toList();
                current.columnNames = row.blocks().stream().map(b -> b.text().toLowerCase(Locale.ROOT)).toList();
                lines.add(new Line(row.line(), true, current));
                return;
            }
            if (row.anyBold()) { lines.add(new Line(row.line(), false, null)); return; }
            if (current.inTable) {
                Map<String, String> cells = new HashMap<>();
                for (Block b : row.blocks()) {
                    int column = 0;
                    for (int i = 0; i < current.columns.size(); i++) if (current.columns.get(i) <= b.x() + SAME_X) column = i;
                    cells.merge(current.columnNames.get(column), b.text(), (a, c) -> a + " " + c);
                }
                String name = cells.get("field name");
                if (name == null || name.isBlank()) { lines.add(new Line(row.line(), false, null)); return; }
                current.fields.add(new FieldRow(trim(name, 200), cells.get("type"), first.page(), row.line()));
                lines.add(new Line(row.line(), true, current));
                return;
            }
            if (row.maxSize() >= BODY) {
                boolean description = !current.lifecycleStarted;
                lines.add(new Line(row.line(), description, description ? current : null));
                return;
            }
            // Stage titles and step cards: rendered column by column when the region ends.
            if (region.isEmpty()) regionAt = lines.size();
            region.addAll(row.blocks());
            current.lifecycleStarted = true;
        }

        private void endItem() { flushRegion(); openTitled = null; }

        /** Assigns the collected stage titles and step cards to stages and renders them stage by stage. */
        private void flushRegion() {
            if (region.isEmpty() || current == null) { region.clear(); return; }
            CaseDraft c = current;
            List<List<Stage>> stageRows = new ArrayList<>();
            List<Block> steps = new ArrayList<>();
            for (Block b : region) {
                if (b.size() >= STAGE) {
                    Stage stage = new Stage(trim(b.text(), 200), b, c.stages.size());
                    if (!stageRows.isEmpty() && Math.abs(stageRows.getLast().getFirst().block.y() - b.y()) <= STAGE_ROW) stageRows.getLast().add(stage);
                    else stageRows.add(new ArrayList<>(List.of(stage)));
                    c.stages.add(stage);
                } else steps.add(b);
            }
            Map<Stage, List<Item>> byStage = new LinkedHashMap<>();
            List<Item> loose = new ArrayList<>();
            for (Block b : steps) {
                List<Stage> row = c.lastRow;
                for (List<Stage> candidate : stageRows) if (candidate.getFirst().block.y() < b.y()) row = candidate;
                Stage stage = null;
                for (Stage s : row) if (s.block.x() <= b.x() + SAME_X) stage = s;
                if (stage == null && !row.isEmpty()) stage = row.getFirst();
                Item item = new Item(trim(b.text(), 200), b, stage, b.icon() == null ? null : kind(b.icon().rgb()));
                c.items.add(item);
                if (stage == null) loose.add(item); else byStage.computeIfAbsent(stage, s -> new ArrayList<>()).add(item);
            }
            if (!stageRows.isEmpty()) c.lastRow = stageRows.getLast();
            List<Line> rendered = new ArrayList<>();
            Set<Stage> here = new HashSet<>();
            stageRows.forEach(here::addAll);
            byStage.keySet().stream().filter(s -> !here.contains(s)).sorted(Comparator.comparingInt(s -> s.order))
                .forEach(s -> byStage.get(s).forEach(i -> rendered.add(new Line("  " + i.name(), true, c))));
            for (List<Stage> row : stageRows) for (Stage s : row) {
                rendered.add(new Line(s.name, true, c));
                byStage.getOrDefault(s, List.of()).forEach(i -> rendered.add(new Line("  " + i.name(), true, c)));
            }
            loose.forEach(i -> rendered.add(new Line("  " + i.name(), true, c)));
            lines.addAll(Math.min(regionAt, lines.size()), rendered);
            region.clear();
        }

        Result result() {
            List<String> names = cases.stream().map(c -> c.name).toList();
            CaseDraft selected = requested == null ? (cases.isEmpty() ? null : cases.getFirst())
                    : cases.stream().filter(c -> norm(c.name).equals(norm(requested))).findFirst().orElse(null);
            List<Integer> unclassified = new ArrayList<>();
            for (List<Line> page : pageLines) {
                StringBuilder text = new StringBuilder();
                int count = 0;
                for (Line l : page) {
                    text.append(l.text()).append('\n');
                    if (!(l.attributed() && (l.owner() == null || l.owner() == selected))) count++;
                }
                pageTexts.add(text.toString());
                unclassified.add(count);
            }
            Transcription empty = new Transcription(null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            if (selected == null) return new Result(empty, unclassified, pageTexts, names, null);
            return new Result(transcription(selected), unclassified, pageTexts, names, selected.name);
        }

        private Transcription transcription(CaseDraft c) {
            Set<String> ids = new HashSet<>();
            CaseType caseType = new CaseType(c.name, meta(c.title.page(), c.line));
            List<Transcription.Stage> stages = new ArrayList<>();
            for (Stage s : c.stages) { s.id = id("stage-", s.name, ids); stages.add(new Transcription.Stage(s.id, s.name, meta(s.block.page(), s.name))); }
            List<Role> roles = new ArrayList<>();
            for (Titled p : personas) roles.add(new Role(id("role-", p.name(), ids), p.name(), meta(p.title().page(), p.line())));
            List<Field> fields = new ArrayList<>();
            for (FieldRow f : c.fields) fields.add(new Field(fieldKey(f.name(), ids), f.name(), fieldType(f.type()), false, meta(f.page(), f.line())));
            List<Item> items = new ArrayList<>(c.items);
            items.sort(Comparator.comparingInt((Item i) -> i.stage() == null ? Integer.MAX_VALUE : i.stage().order)
                .thenComparingInt(i -> i.block().page()).thenComparingDouble(i -> i.block().y()));
            List<String> itemIds = new ArrayList<>();
            for (Item i : items) itemIds.add(id("decision".equals(i.kind()) ? "decision-" : "step-", i.name(), ids));
            List<Step> steps = new ArrayList<>();
            List<Decision> decisions = new ArrayList<>();
            for (int n = 0; n < items.size(); n++) {
                Item i = items.get(n);
                String next = n + 1 < items.size() ? itemIds.get(n + 1) : null;
                Meta source = meta(i.block().page(), i.name());
                if ("decision".equals(i.kind())) decisions.add(new Decision(itemIds.get(n), i.name(), List.of(), next, source));
                else steps.add(new Step(itemIds.get(n), i.name(), i.stage() == null ? null : i.stage().id, i.kind(), null, next, null, source));
            }
            List<Integration> integrations = new ArrayList<>();
            for (Titled d : dataObjects) {
                Matcher m = SYSTEM_OF_RECORD.matcher(d.description());
                integrations.add(new Integration(id("integration-", d.name(), ids), d.name(), m.find() ? trim(m.group(1).strip(), 200) : null, null, meta(d.title().page(), d.line())));
            }
            return new Transcription(caseType, itemIds.isEmpty() ? null : itemIds.getFirst(), stages, roles, fields, steps, decisions, List.of(), List.of(), integrations);
        }
    }

    /** Pega field types to transcription types; date-times and anything unknown stay empty for the reviewer. */
    static String fieldType(String pegaType) {
        if (pegaType == null) return null;
        return switch (pegaType.strip().toLowerCase(Locale.ROOT)) {
            case "text", "text (single line)", "text (paragraph)", "paragraph", "rich text", "email", "phone", "phone number", "url", "picklist" -> "string";
            case "decimal", "currency", "percentage", "number" -> "number";
            case "integer" -> "integer";
            case "boolean", "true/false", "checkbox" -> "boolean";
            case "date", "date only" -> "date";
            default -> null;
        };
    }

    private static Meta meta(int page, String excerpt) { return new Meta(page, trim(excerpt, 500), Transcription.PROPOSED, null); }
    private static String id(String prefix, String name, Set<String> used) { return GenericOutlineLayout.id(prefix, name, used); }
    private static String fieldKey(String name, Set<String> used) { return GenericOutlineLayout.fieldKey(name, used); }
    private static String norm(String text) { return GenericOutlineLayout.norm(text); }
    private static String trim(String text, int max) { return GenericOutlineLayout.trim(text, max); }
}
