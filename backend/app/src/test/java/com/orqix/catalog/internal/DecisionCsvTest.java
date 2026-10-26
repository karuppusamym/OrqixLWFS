package com.orqix.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** G3 (docs/build/S89): the strict CSV form of a decision table, without a database. */
class DecisionCsvTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HEAD = "hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:/input/amount,in:text:/input/costCenter,out:text:route\n";

    private static DecisionCsv.Parsed parse(String text) {
        return DecisionCsv.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode table(String json) {
        return JSON.readTree(json);
    }

    private static DecisionCsv.Problem first(String text) {
        DecisionCsv.Parsed parsed = parse(text);
        assertThat(parsed.ok()).as(text).isFalse();
        return parsed.problems().get(0);
    }

    @Test
    void parsesTypedColumnsRowsDatesAndDefault() {
        DecisionCsv.Parsed parsed = parse(HEAD
                + "rule,2026-01-01,2026-12-31,gte 50000,,executiveReview\n"
                + "rule,,,gte 5000.50,eq IT,financeReview\n"
                + "default,,,,,standard\n");
        assertThat(parsed.ok()).as(parsed.problems().toString()).isTrue();
        JsonNode t = parsed.table();
        assertThat(t.path("hitPolicy").asString()).isEqualTo("FIRST");
        assertThat(t.path("rows")).hasSize(2);
        JsonNode r0 = t.path("rows").get(0);
        assertThat(r0.path("when").toString()).isEqualTo("{\"fieldRef\":\"/input/amount\",\"operator\":\"gte\",\"value\":50000}");
        assertThat(r0.path("effective").toString()).isEqualTo("{\"from\":\"2026-01-01\",\"to\":\"2026-12-31\"}");
        JsonNode r1 = t.path("rows").get(1);
        assertThat(r1.path("when").path("all")).hasSize(2);
        assertThat(r1.path("when").path("all").get(0).path("value").decimalValue()).isEqualByComparingTo("5000.50");
        assertThat(r1.has("effective")).isFalse();
        assertThat(t.path("default").toString()).isEqualTo("{\"route\":\"standard\"}");
    }

    @Test
    void acceptsByteOrderMarkCrlfAndQuotedFields() {
        String body = HEAD.replace("\n", "\r\n") + "rule,,,\"gte 5\",,\"a, b\"\r\n" + "default,,,,,\"say \"\"hi\"\"\"\r\n";
        byte[] with = ("﻿" + body).getBytes(StandardCharsets.UTF_8);
        DecisionCsv.Parsed parsed = DecisionCsv.parse(with);
        assertThat(parsed.ok()).as(parsed.problems().toString()).isTrue();
        assertThat(parsed.table().path("rows").get(0).path("then").path("route").asString()).isEqualTo("a, b");
        assertThat(parsed.table().path("default").path("route").asString()).isEqualTo("say \"hi\"");
        // No final line break is fine too.
        assertThat(parse(HEAD + "rule,,,gte 5,,x\ndefault,,,,,y").ok()).isTrue();
    }

    @Test
    void reportsLineAndColumnForEveryKindOfFault() {
        var p = first(HEAD + "rule,,,gte abc,,x\ndefault,,,,,y\n");
        assertThat(p.line()).isEqualTo(2);
        assertThat(p.column()).isEqualTo(4);
        assertThat(p.code()).isEqualTo("number");

        p = first(HEAD + "rule,,,gte 5,,x\nrule,,,between 1 and 2,,x\ndefault,,,,,y\n");
        assertThat(p.line()).isEqualTo(3);
        assertThat(p.code()).isEqualTo("operator");
        assertThat(p.message()).contains("eq, ne, gte, gt, lt or lte");

        p = first(HEAD + "rule,,,gte 5,,x\ndefault,,,,,\n");
        assertThat(p.code()).isEqualTo("emptyResult");
        assertThat(p.line()).isEqualTo(3);
        assertThat(p.column()).isEqualTo(6);

        p = first(HEAD + "rule,,,,,x\ndefault,,,,,y\n");
        assertThat(p.code()).isEqualTo("noCondition");
        assertThat(p.line()).isEqualTo(2);

        p = first(HEAD + "rule,2026-13-1,,gte 5,,x\ndefault,,,,,y\n");
        assertThat(p.code()).isEqualTo("date");
        assertThat(p.column()).isEqualTo(2);

        p = first(HEAD + "rule,2026-05-01,2026-04-01,gte 5,,x\ndefault,,,,,y\n");
        assertThat(p.message()).contains("before");

        p = first(HEAD + "rule,,,gte 5,,x,extra\ndefault,,,,,y\n");
        assertThat(p.code()).isEqualTo("cellCount");
        assertThat(p.line()).isEqualTo(2);

        p = first(HEAD + "maybe,,,gte 5,,x\ndefault,,,,,y\n");
        assertThat(p.code()).isEqualTo("rowKind");

        p = first(HEAD + "rule,,,gte 5,,x\ndefault,,,gte 1,,y\n");
        assertThat(p.code()).isEqualTo("defaultCell");
        assertThat(p.column()).isEqualTo(4);

        p = first(HEAD + "rule,,,gte 5,,x\n");
        assertThat(p.code()).isEqualTo("noDefault");
        p = first(HEAD + "default,,,,,y\n");
        assertThat(p.code()).isEqualTo("noRules");
        p = first(HEAD + "rule,,,gte 5,,x\ndefault,,,,,y\nrule,,,gte 6,,x\n");
        assertThat(p.code()).isEqualTo("afterDefault");
        assertThat(p.line()).isEqualTo(4);
    }

    @Test
    void neverCoercesAmbiguousCells() {
        // Thousands separators, currency, exponent, plus sign, leading zero, comma decimal: all refused.
        for (String bad : new String[] {"1,000", "$5", "1e3", "+5", "007", "5,5", "5.", ".5", "0x10", "NaN", "5 000"}) {
            var p = first(HEAD + "rule,,,\"gte " + bad + "\",,x\ndefault,,,,,y\n");
            assertThat(p.code()).as(bad).isEqualTo("number");
            assertThat(p.line()).isEqualTo(2);
            assertThat(p.column()).isEqualTo(4);
        }
        // Booleans are lowercase true/false only, in a boolean column.
        String bool = "hitPolicy:UNIQUE,effectiveFrom,effectiveTo,in:boolean:/input/vip,out:number:rate\n";
        assertThat(parse(bool + "rule,,,eq true,5\ndefault,,,,0\n").ok()).isTrue();
        for (String bad : new String[] {"TRUE", "yes", "1", "True"}) {
            assertThat(first(bool + "rule,,,eq " + bad + ",5\ndefault,,,,0\n").code()).as(bad).isEqualTo("boolean");
        }
        // Space rules: one space exactly, none at the cell edges, no missing value.
        assertThat(first(HEAD + "rule,,,gte  5,,x\ndefault,,,,,y\n").code()).isEqualTo("spaces");
        assertThat(first(HEAD + "rule,,,\" gte 5\",,x\ndefault,,,,,y\n").code()).isEqualTo("spaces");
        assertThat(first(HEAD + "rule,,,gte,,x\ndefault,,,,,y\n").code()).isEqualTo("condition");
        assertThat(first(HEAD + "rule,,,GTE 5,,x\ndefault,,,,,y\n").code()).isEqualTo("operator");
        // A stray apostrophe is not silently dropped.
        assertThat(first(HEAD + "rule,,,gte 5,,'x\ndefault,,,,,y\n").code()).isEqualTo("apostrophe");
    }

    @Test
    void refusesStructuralAndEncodingFaults() {
        assertThat(first("").code()).isEqualTo("empty");
        assertThat(first("hitPolicy:SOME,effectiveFrom,effectiveTo,in:number:/input/a,out:text:r\n").code()).isEqualTo("header");
        assertThat(first("hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:/input/a\n").message()).contains("out:");
        assertThat(first("hitPolicy:FIRST,effectiveFrom,effectiveTo,out:text:r\n").message()).contains("in:");
        // Outputs before inputs, duplicate columns, unknown types and bad names.
        assertThat(first("hitPolicy:FIRST,effectiveFrom,effectiveTo,out:text:r,in:number:/input/a\n").message())
                .contains("before output");
        assertThat(first("hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:/input/a,in:number:/input/a,out:text:r\n").message())
                .contains("twice");
        assertThat(first("hitPolicy:FIRST,effectiveFrom,effectiveTo,in:date:/input/a,out:text:r\n").code()).isEqualTo("header");
        assertThat(first("hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:amount,out:text:r\n").message()).contains("field reference");
        assertThat(first("hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:/input/a,out:text:Bad\n").message()).contains("result name");
        // Semicolon-delimited (a common spreadsheet locale) is refused rather than guessed.
        var semi = first("hitPolicy:FIRST;effectiveFrom;effectiveTo;in:number:/input/a;out:text:r\n");
        assertThat(semi.line()).isEqualTo(1);

        // Not UTF-8 (Latin-1 e acute) with the line it is on.
        byte[] latin = (HEAD + "rule,,,gte 5,,caf").getBytes(StandardCharsets.ISO_8859_1);
        byte[] bytes = java.util.Arrays.copyOf(latin, latin.length + 1);
        bytes[latin.length] = (byte) 0xE9;
        var enc = DecisionCsv.parse(bytes).problems().get(0);
        assertThat(enc.code()).isEqualTo("encoding");
        assertThat(enc.line()).isEqualTo(2);

        // CSV-level faults.
        assertThat(first(HEAD + "rule,,,gte 5,,\"unterminated\n").code()).isEqualTo("lineBreakInCell");
        assertThat(first(HEAD + "rule,,,gte 5,,\"unterminated").code()).isEqualTo("quote");
        assertThat(first(HEAD + "rule,,,gte 5,,ab\"c\ndefault,,,,,y\n").code()).isEqualTo("quote");
        assertThat(first(HEAD + "rule,,,gte 5,,\"ab\"c\ndefault,,,,,y\n").code()).isEqualTo("quote");
        assertThat(first(HEAD + "rule,,,gte 5,,x\n\ndefault,,,,,y\n").code()).isEqualTo("emptyLine");
        assertThat(first(HEAD + "rule,,,gte 5,,x\rdefault,,,,,y\n").code()).isEqualTo("lineBreak");
        assertThat(first(HEAD + "rule,,,gte 5,,a\tb\ndefault,,,,,y\n").code()).isEqualTo("controlCharacter");
        assertThat(first(HEAD + "rule,,,gte 5,,a\u0000b\ndefault,,,,,y\n").code()).isEqualTo("controlCharacter");
    }

    @Test
    void enforcesSizeRowAndCellLimits() {
        byte[] big = new byte[DecisionCsv.MAX_BYTES + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        assertThat(DecisionCsv.parse(big).problems().get(0).code()).isEqualTo("tooLarge");

        StringBuilder rules = new StringBuilder(HEAD);
        for (int i = 0; i < DecisionCsv.MAX_RULES; i++) {
            rules.append("rule,,,gte ").append(i).append(",,r").append(i).append('\n');
        }
        assertThat(parse(rules + "default,,,,,d\n").ok()).isTrue();
        var p = first(rules + "rule,,,gte 99,,extra\ndefault,,,,,d\n");
        assertThat(p.code()).isEqualTo("tooManyRules");
        assertThat(p.line()).isEqualTo(DecisionCsv.MAX_RULES + 2);

        StringBuilder many = new StringBuilder(HEAD);
        for (int i = 0; i < DecisionCsv.MAX_RECORDS + 5; i++) {
            many.append("rule,,,gte 1,,r\n");
        }
        assertThat(first(many.toString()).code()).isEqualTo("tooManyLines");
        assertThat(first(HEAD + "rule,,,gte 5,,x" + "y".repeat(DecisionCsv.MAX_CELL_CHARS) + "\ndefault,,,,,d\n").code())
                .isEqualTo("cellTooLong");
    }

    @Test
    void reportsAtMostOneHundredProblems() {
        StringBuilder text = new StringBuilder(HEAD);
        for (int i = 0; i < 50; i++) {
            text.append("rule,,,gte x,,\n");
        }
        text.append("default,,,,,d\n");
        assertThat(parse(text.toString()).problems().size()).isLessThanOrEqualTo(DecisionCsv.MAX_PROBLEMS).isGreaterThan(10);
    }

    // ---------------------------------------------------------------- export

    private static final String TABLE = """
            {"key":"routing","version":3,"hitPolicy":"FIRST","rows":[
              {"when":{"fieldRef":"/input/amount","operator":"gte","value":50000},"then":{"route":"exec","rate":-1.5,"vip":true},
               "effective":{"from":"2026-01-01"}},
              {"when":{"all":[{"fieldRef":"/input/amount","operator":"gte","value":5000.25},
                              {"fieldRef":"/input/costCenter","operator":"eq","value":"IT, Ops"}]},
               "then":{"route":"finance","rate":2,"vip":false},"effective":{"from":"2026-02-01","to":"2026-12-31"}},
              {"when":{"fieldRef":"/input/costCenter","operator":"ne","value":"-x"},"then":{"route":"- dash","rate":0,"vip":false}}
            ],"default":{"route":"standard","rate":0,"vip":false}}
            """;

    @Test
    void exportsAndReadsBackTheIdenticalTable() throws Exception {
        JsonNode source = table(TABLE);
        String csv = DecisionCsv.export(source);
        assertThat(csv.lines().findFirst().orElseThrow()).isEqualTo(
                "hitPolicy:FIRST,effectiveFrom,effectiveTo,in:number:/input/amount,in:text:/input/costCenter,"
                        + "out:text:route,out:number:rate,out:boolean:vip");
        DecisionCsv.Parsed back = parse(csv);
        assertThat(back.ok()).as(back.problems().toString()).isTrue();
        assertThat(DecisionCsv.same(source.path("rows"), back.table().path("rows"))).isTrue();
        assertThat(DecisionCsv.same(source.path("default"), back.table().path("default"))).isTrue();
        assertThat(back.table().path("hitPolicy").asString()).isEqualTo("FIRST");
        // Writing the re-read table again gives the very same bytes.
        var rewritten = JSON.createObjectNode();
        rewritten.set("hitPolicy", back.table().get("hitPolicy"));
        rewritten.set("rows", back.table().get("rows"));
        rewritten.set("default", back.table().get("default"));
        assertThat(DecisionCsv.export(rewritten)).isEqualTo(csv);
    }

    @Test
    void neutralisesSpreadsheetFormulasOnExportAndRestoresThemOnImport() throws Exception {
        JsonNode source = table(TABLE);
        String csv = DecisionCsv.export(source);
        // The negative result and the text starting with a dash are written with the apostrophe, never bare.
        assertThat(csv).contains(",'-1.5,").contains(",'- dash,");
        for (String line : csv.split("\n")) {
            for (String cell : line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                assertThat(cell).as(line).doesNotStartWith("=").doesNotStartWith("+").doesNotStartWith("-")
                        .doesNotStartWith("@");
            }
        }
        DecisionCsv.Parsed back = parse(csv);
        assertThat(back.table().path("rows").get(0).path("then").path("rate").decimalValue()).isEqualByComparingTo("-1.5");
        assertThat(back.table().path("rows").get(2).path("then").path("route").asString()).isEqualTo("- dash");

        // Every risky first character, and the apostrophe itself, survives a round trip (text results).
        for (String risky : new String[] {"=SUM(A1)", "+1", "-1", "@cmd", "'quoted"}) {
            String json = "{\"hitPolicy\":\"UNIQUE\",\"rows\":[{\"when\":{\"fieldRef\":\"/input/a\",\"operator\":\"eq\",\"value\":\"k\"},"
                    + "\"then\":{\"r\":\"" + risky + "\"}}],\"default\":{\"r\":\"d\"}}";
            String out = DecisionCsv.export(table(json));
            assertThat(out).as(risky).contains("'" + risky);
            assertThat(parse(out).table().path("rows").get(0).path("then").path("r").asString()).as(risky).isEqualTo(risky);
        }
        // A plain "=" or "@" typed by hand into a cell is data, not a formula; it is just parsed as text, never run.
        assertThat(parse("hitPolicy:UNIQUE,effectiveFrom,effectiveTo,in:text:/input/a,out:text:r\n"
                + "rule,,,eq k,=cmd|' /C calc'!A0\ndefault,,,,d\n").table().path("rows").get(0).path("then").path("r").asString())
                .isEqualTo("=cmd|' /C calc'!A0");
    }

    @Test
    void refusesTablesCsvCannotCarryExactly() {
        String any = "{\"hitPolicy\":\"FIRST\",\"rows\":[{\"when\":{\"any\":[{\"fieldRef\":\"/input/a\",\"operator\":\"lt\",\"value\":1},"
                + "{\"fieldRef\":\"/input/a\",\"operator\":\"gt\",\"value\":9}]},\"then\":{\"r\":\"x\"}}],\"default\":{\"r\":\"d\"}}";
        assertThatThrownBy(() -> DecisionCsv.export(table(any))).isInstanceOf(DecisionCsv.NotRepresentable.class)
                .hasMessageContaining("OR row");
        String emptyText = "{\"hitPolicy\":\"FIRST\",\"rows\":[{\"when\":{\"fieldRef\":\"/input/a\",\"operator\":\"lt\",\"value\":1},"
                + "\"then\":{\"r\":\"x\"}}],\"default\":{\"r\":\"\"}}";
        assertThatThrownBy(() -> DecisionCsv.export(table(emptyText))).isInstanceOf(DecisionCsv.NotRepresentable.class);
        String mixed = "{\"hitPolicy\":\"FIRST\",\"rows\":[{\"when\":{\"fieldRef\":\"/input/a\",\"operator\":\"lt\",\"value\":1},\"then\":{\"r\":\"x\"}},"
                + "{\"when\":{\"fieldRef\":\"/input/a\",\"operator\":\"eq\",\"value\":\"two\"},\"then\":{\"r\":\"y\"}}],\"default\":{\"r\":\"d\"}}";
        assertThatThrownBy(() -> DecisionCsv.export(table(mixed))).isInstanceOf(DecisionCsv.NotRepresentable.class)
                .hasMessageContaining("another row uses");
        String spaced = "{\"hitPolicy\":\"FIRST\",\"rows\":[{\"when\":{\"fieldRef\":\"/input/a\",\"operator\":\"eq\",\"value\":\"x \"},"
                + "\"then\":{\"r\":\"x\"}}],\"default\":{\"r\":\"d\"}}";
        assertThatThrownBy(() -> DecisionCsv.export(table(spaced))).isInstanceOf(DecisionCsv.NotRepresentable.class);
        String huge = "{\"hitPolicy\":\"FIRST\",\"rows\":[{\"when\":{\"fieldRef\":\"/input/a\",\"operator\":\"lt\",\"value\":12345678901234567890123},"
                + "\"then\":{\"r\":\"x\"}}],\"default\":{\"r\":\"d\"}}";
        assertThatThrownBy(() -> DecisionCsv.export(table(huge))).isInstanceOf(DecisionCsv.NotRepresentable.class);
    }

    @Test
    void templateImportsAsIsAndShowsTheColumns() throws Exception {
        String template = DecisionCsv.template(table(TABLE));
        List<String> lines = template.lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).startsWith("rule,,,gte 0,,");
        assertThat(lines.get(2)).startsWith("default,,,,,");
        assertThat(parse(template).ok()).isTrue();
        // Even a table with an OR row yields a usable template.
        String any = "{\"hitPolicy\":\"FIRST\",\"rows\":[{\"when\":{\"any\":[{\"fieldRef\":\"/input/a\",\"operator\":\"lt\",\"value\":1},"
                + "{\"fieldRef\":\"/input/a\",\"operator\":\"gt\",\"value\":9}]},\"then\":{\"r\":\"x\"}}],\"default\":{\"r\":\"d\"}}";
        assertThat(parse(DecisionCsv.template(table(any))).ok()).isTrue();
    }

    @Test
    void locatesTableFindingsAtTheirCell() {
        DecisionCsv.Parsed parsed = parse(HEAD
                + "rule,2026-01-01,,gte 50000,,exec\n"
                + "rule,,,gte 5000,eq IT,finance\n"
                + "default,,,,,standard\n");
        assertThat(parsed.locator().locate("/rows/0/when/value")).containsExactly(2, 4);
        assertThat(parsed.locator().locate("/rows/1/when/all/1/fieldRef")).containsExactly(3, 5);
        assertThat(parsed.locator().locate("/rows/1/when")).containsExactly(3, 4);
        assertThat(parsed.locator().locate("/rows/1/then/route")).containsExactly(3, 6);
        assertThat(parsed.locator().locate("/rows/0/effective/from")).containsExactly(2, 2);
        assertThat(parsed.locator().locate("/default/route")).containsExactly(4, 6);
        assertThat(parsed.locator().locate("/hitPolicy")).containsExactly(1, 1);
    }
}
