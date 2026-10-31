package com.orqix.migration.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import com.orqix.migration.camunda.Expressions;

class XlsxTest {
    @Test
    void columnsEscapingAndReproducibleBytes() throws Exception {
        assertThat(Xlsx.column(0)).isEqualTo("A");
        assertThat(Xlsx.column(25)).isEqualTo("Z");
        assertThat(Xlsx.column(26)).isEqualTo("AA");
        assertThat(Xlsx.column(701)).isEqualTo("ZZ");
        assertThat(Xlsx.escape("a<b>&\"c\"\u0001\u0007d")).isEqualTo("a&lt;b&gt;&amp;&quot;c&quot;d");
        List<List<String>> rows = List.of(List.of("name", "value"), List.of("=HYPERLINK(\"x\")", "ok"));
        byte[] one = new Xlsx().sheet("Elements", rows).sheet("Bad:/name?", rows).bytes();
        byte[] two = new Xlsx().sheet("Elements", rows).sheet("Bad:/name?", rows).bytes();
        assertThat(one).isEqualTo(two);
        Map<String, String> parts = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(one))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) parts.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(parts.keySet()).containsExactly("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml");
        // A formula-like label stays text: an inline string, never a formula element.
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains("t=\"inlineStr\"").contains("=HYPERLINK(&quot;x&quot;)").doesNotContain("<f>");
        assertThat(parts.get("xl/workbook.xml")).contains("name=\"Bad  name \"");
    }

    @Test
    void rewrittenConditionsAreRecognised() {
        assertThat(Expressions.rewritten("= not(score > 100)")).isTrue();
        assertThat(Expressions.rewritten("${!(amount >= 5000)}")).isTrue();
        assertThat(Expressions.rewritten("= not(a > 1 and b < 2)")).isTrue();
        assertThat(Expressions.rewritten("= score between 1 and 5")).isTrue();
        assertThat(Expressions.rewritten("= not(approved)")).as("bare yes/no negation is transcribed").isFalse();
        assertThat(Expressions.rewritten("${!approved}")).isFalse();
        assertThat(Expressions.rewritten("= amount >= 5000")).isFalse();
        assertThat(Expressions.rewritten("${amount != 5000}")).isFalse();
        assertThat(Expressions.rewritten(null)).isFalse();
    }
}
