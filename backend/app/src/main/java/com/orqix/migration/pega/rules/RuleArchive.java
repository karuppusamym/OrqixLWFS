package com.orqix.migration.pega.rules;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import com.orqix.migration.camunda.*;
import com.orqix.shared.json.Json;

/**
 * Decodes a native Pega rule archive (ZIP, RAP or JAR) into rule-instance pages (docs/build/S112 "Archive" and "Page
 * grammar"). Nothing in the archive is executed. Entries that cannot be decoded are kept as refused entries: they could
 * hold rules that change resolution, so conversion stays blocked while any exists.
 */
public final class RuleArchive {
    public static final Set<String> AVAILABILITY = Set.of("Yes", "No", "Blocked", "Withdrawn", "Final");
    static final Pattern VERSION = Pattern.compile("(\\d{2})-(\\d{2})-(\\d{2})");
    static final Pattern NAME = Pattern.compile("[A-Za-z@][A-Za-z0-9_@.-]{0,127}");
    private static final Pattern STAMP = Pattern.compile(" #\\d{8}T\\d{6}\\.\\d{3} GMT");
    static final Pattern DATE = Pattern.compile("\\d{8}");
    static final Pattern DATETIME = Pattern.compile("\\d{8}T\\d{6}\\.\\d{3} GMT");

    /**
     * A rule qualifier (docs/build/S115): a property circumstance, a date circumstance or a date range. Several on one
     * rule are {@code multivariate}. Dates keep their source text (YYYYMMDD, or YYYYMMDDTHHMMSS.mmm GMT for ranges).
     */
    public record Qualifier(String kind, String property, String value, String date, String from, String to, List<Qualifier> parts) {
        public String key() { return kind + "|" + property + "|" + value + "|" + date + "|" + from + "|" + to + "|" + (parts == null ? "" : parts.stream().map(Qualifier::key).toList()); }
    }
    private static final Limits LIMITS = Limits.DEFAULT;

    /**
     * One archive entry. {@code status}: manifest, rule, data or refused (with {@code reason}). Rule identity fields are
     * set for rules only.
     */
    public record Entry(String path, String sha256, String status, String reason, String detail, String ruleType, String className,
            String ruleName, String ruleset, String version, String availability, boolean circumstanced, String insKey, Page page, Qualifier qualifier) {
        public boolean isRule() { return "rule".equals(status); }
        public int[] versionParts() { return parts(version); }
        public String key() { return ruleType + " " + ruleName.toUpperCase(Locale.ROOT); }
        public String label() { return ruleType + " " + className + "." + ruleName + " " + ruleset + ":" + version; }
    }

    private RuleArchive() { }

    public static List<Entry> decode(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw SourceException.invalid("Expected a Pega ZIP, RAP or JAR rule archive.");
        List<Entry> entries = new ArrayList<>(); Set<String> names = new HashSet<>(); long expanded = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName().replace('\\', '/');
                if (name.length() > 500 || name.startsWith("/") || name.contains(":") || Arrays.asList(name.split("/")).contains("..") || !names.add(name))
                    throw SourceException.invalid("Unsafe or duplicate archive path.");
                if (names.size() > 200) throw new SourceException("Archive exceeds 200 entries.", true);
                var content = new ByteArrayOutputStream(); long size = 0; byte[] buffer = new byte[8192]; int read;
                while ((read = zip.read(buffer)) != -1) {
                    size += read; expanded += read; content.write(buffer, 0, read);
                    if (size > 1_048_576 || expanded > 4_194_304) throw new SourceException("Archive exceeds extraction limits.", true);
                }
                if (!entry.isDirectory() && !name.endsWith("/")) entries.add(entry(name, content.toByteArray()));
            }
        } catch (IOException e) { throw SourceException.invalid("The archive is corrupt or unsupported."); }
        if (entries.isEmpty()) throw SourceException.invalid("The archive contains no files.");
        return List.copyOf(entries);
    }

    private static Entry refused(String path, String hash, String reason, String detail) {
        return new Entry(path, hash, "refused", reason, detail, null, null, null, null, null, null, false, null, null, null);
    }

    static Entry entry(String path, byte[] content) {
        String hash = Json.sha256Hex(content);
        if (path.equals("META-INF/MANIFEST.MF")) return new Entry(path, hash, "manifest", null, null, null, null, null, null, null, null, false, null, null, null);
        if (!path.toLowerCase(Locale.ROOT).endsWith(".xml"))
            return refused(path, hash, "PEGA_NATIVE_ENTRY_UNDECODED", "Binary or non-XML entry. It could hold serialized rules, so the archive cannot be resolved without it.");
        XElement root;
        try { root = SafeXml.parse(new String(content, StandardCharsets.UTF_8), LIMITS); }
        catch (SourceException e) { return refused(path, hash, "PEGA_NATIVE_XML_INVALID", e.getMessage()); }
        if (!root.name().equals("pagedata") || !root.ns().isEmpty() || !root.attributes().isEmpty() || !directText(root).isBlank())
            return refused(path, hash, "PEGA_NATIVE_PAGE_ROOT", "A rule instance must be a <pagedata> page without namespace or attributes.");
        Page page;
        try { page = properties(root, path + "#"); }
        catch (Page.Grammar e) { return refused(path, hash, "PEGA_NATIVE_PAGE_GRAMMAR", e.sourcePath() + ": " + e.getMessage()); }
        try { return identity(path, hash, page); }
        catch (Page.Grammar e) { return refused(path, hash, "PEGA_NATIVE_IDENTITY_INVALID", e.sourcePath() + ": " + e.getMessage()); }
    }

    /** Text directly inside an element (SafeXml keeps each element's own character data). */
    private static String directText(XElement e) { return e.text(); }

    private static Page properties(XElement node, String path) {
        Map<String, Page.Value> fields = new LinkedHashMap<>();
        for (XElement child : node.children()) {
            String childPath = path + "/" + child.name();
            if (!child.ns().isEmpty()) throw new Page.Grammar(childPath, "namespaced elements are not part of the page grammar");
            if (fields.containsKey(child.name())) throw new Page.Grammar(childPath, "repeated property");
            fields.put(child.name(), value(child, childPath));
        }
        return new Page(path, Collections.unmodifiableMap(fields));
    }

    private static Page.Value value(XElement node, String path) {
        String kind = node.attributes().get("REPEATINGTYPE");
        if (!node.attributes().keySet().stream().allMatch("REPEATINGTYPE"::equals)) throw new Page.Grammar(path, "unsupported attribute");
        if (kind == null) {
            if (!node.children().isEmpty()) throw new Page.Grammar(path, "embedded single pages are not supported");
            return new Page.Text(path, node.text());
        }
        if (!kind.equals("PageList") && !kind.equals("PropertyList")) throw new Page.Grammar(path, "unsupported list kind " + kind);
        if (!directText(node).isBlank()) throw new Page.Grammar(path, "unexpected text in a list");
        List<Page> pages = new ArrayList<>(); List<String> texts = new ArrayList<>();
        int index = 0;
        for (XElement row : node.children()) {
            index++;
            String rowPath = path + "/" + index;
            if (!row.name().equals("rowdata") || !row.ns().isEmpty() || !row.attributes().equals(Map.of("REPEATINGINDEX", String.valueOf(index))))
                throw new Page.Grammar(rowPath, "rows must be <rowdata> numbered from 1");
            if (kind.equals("PropertyList")) {
                if (!row.children().isEmpty()) throw new Page.Grammar(rowPath, "property list rows hold text");
                texts.add(row.text());
            } else {
                if (!directText(row).isBlank()) throw new Page.Grammar(rowPath, "page list rows hold properties");
                pages.add(properties(row, rowPath));
            }
        }
        return kind.equals("PageList") ? new Page.Pages(path, List.copyOf(pages)) : new Page.Texts(path, List.copyOf(texts));
    }

    private static Entry identity(String path, String hash, Page page) {
        String obj = page.text("pxObjClass");
        if (obj == null || obj.isEmpty()) throw new Page.Grammar(page.pathOf("pxObjClass"), "pxObjClass is required");
        if (obj.startsWith("Data-")) {
            String key = page.text("pzInsKey");
            if (key == null || key.isEmpty()) throw new Page.Grammar(page.pathOf("pzInsKey"), "pzInsKey is required");
            return new Entry(path, hash, "data", null, null, obj, null, null, null, null, null, false, key, page, null);
        }
        if (obj.equals("System-User-MyRules")) {
            // A rule delegation record: provenance of the delegated rule, never converted.
            for (String field : List.of("pzInsKey", "pyRuleInsKey", "pyAccessGroup")) {
                String value = page.text(field);
                if (value == null || value.isEmpty() || value.length() > 300) throw new Page.Grammar(page.pathOf(field), field + " is required");
            }
            return new Entry(path, hash, "delegation", null, null, obj, null, null, null, null, null, false, page.text("pzInsKey"), page, null);
        }
        if (!obj.startsWith("Rule-")) throw new Page.Grammar(page.pathOf("pxObjClass"), "only rule and data instances are supported");
        for (String key : List.of("pyClassName", "pyRuleName", "pyRuleSet", "pyRuleSetVersion", "pyRuleAvailable", "pzInsKey")) {
            String value = page.text(key);
            if (value == null || value.isEmpty() || value.length() > 200 || value.contains("\n")) throw new Page.Grammar(page.pathOf(key), key + " is required");
        }
        for (String key : List.of("pyRuleName", "pyClassName", "pyRuleSet"))
            if (!NAME.matcher(page.text(key)).matches()) throw new Page.Grammar(page.pathOf(key), "invalid name");
        if (!VERSION.matcher(page.text("pyRuleSetVersion")).matches()) throw new Page.Grammar(page.pathOf("pyRuleSetVersion"), "expected NN-NN-NN");
        if (!AVAILABILITY.contains(page.text("pyRuleAvailable"))) throw new Page.Grammar(page.pathOf("pyRuleAvailable"), "unknown availability");
        String prefix = (obj + " " + page.text("pyClassName") + " " + page.text("pyRuleName")).toUpperCase(Locale.ROOT);
        String key = page.text("pzInsKey");
        if (!key.toUpperCase(Locale.ROOT).startsWith(prefix) || !(key.length() == prefix.length() || STAMP.matcher(key.substring(prefix.length())).matches()))
            throw new Page.Grammar(page.pathOf("pzInsKey"), "pzInsKey does not match the rule identity");
        Qualifier qualifier = qualifier(page);
        return new Entry(path, hash, "rule", null, null, obj, page.text("pyClassName"), page.text("pyRuleName"), page.text("pyRuleSet"),
                page.text("pyRuleSetVersion"), page.text("pyRuleAvailable"), qualifier != null, key, page, qualifier);
    }

    private static Qualifier qualifier(Page page) {
        List<Qualifier> parts = new ArrayList<>();
        var f = page.fields();
        if (f.containsKey("pyCircumstanceProp") || f.containsKey("pyCircumstanceVal")) {
            if (blank(page.text("pyCircumstanceProp")) || blank(page.text("pyCircumstanceVal")))
                throw new Page.Grammar(page.pathOf("pyCircumstanceProp"), "a circumstance needs a property and a value");
            parts.add(new Qualifier("property", page.text("pyCircumstanceProp"), page.text("pyCircumstanceVal"), null, null, null, null));
        }
        if (f.containsKey("pyCircumstanceDateProp") || f.containsKey("pyCircumstanceDate")) {
            String date = page.text("pyCircumstanceDate");
            if (blank(page.text("pyCircumstanceDateProp")) || date == null || !DATE.matcher(date).matches())
                throw new Page.Grammar(page.pathOf("pyCircumstanceDate"), "a date circumstance needs a property and a YYYYMMDD date");
            parts.add(new Qualifier("date", page.text("pyCircumstanceDateProp"), null, date, null, null, null));
        }
        if (f.containsKey("pyStartDateTime") || f.containsKey("pyEndDateTime")) {
            String from = blank(page.text("pyStartDateTime")) ? null : page.text("pyStartDateTime");
            String to = blank(page.text("pyEndDateTime")) ? null : page.text("pyEndDateTime");
            for (String v : Arrays.asList(from, to))
                if (v != null && !DATETIME.matcher(v).matches()) throw new Page.Grammar(page.pathOf("pyStartDateTime"), "a date range needs YYYYMMDDTHHMMSS.mmm GMT bounds");
            if (from == null && to == null || from != null && to != null && from.compareTo(to) > 0)
                throw new Page.Grammar(page.pathOf("pyStartDateTime"), "a date range needs at least one bound, in order");
            parts.add(new Qualifier("dateRange", null, null, null, from, to, null));
        }
        if (parts.isEmpty()) return null;
        return parts.size() == 1 ? parts.getFirst() : new Qualifier("multivariate", null, null, null, null, null, List.copyOf(parts));
    }

    static boolean blank(String s) { return s == null || s.isBlank(); }

    static int[] parts(String version) {
        var m = VERSION.matcher(version);
        if (!m.matches()) throw new IllegalArgumentException(version);
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }
}
