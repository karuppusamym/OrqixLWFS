package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.Jcs;

/** RFC 8785 (JCS) behaviour, including the RFC's own sample and Appendix B number vectors. */
class CanonicalJsonTest {

    @Test
    void rfc8785MainSample() {
        // RFC 8785 section 3.2.4 (JSON escapes kept literal with double backslashes in Java source).
        String input = """
                {
                  "numbers": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001],
                  "string": "\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\"\\/",
                  "literals": [null, true, false]
                }
                """;
        String expected = "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                + "\"string\":\"\u20ac$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}";
        assertThat(CanonicalJson.canonicalize(input)).isEqualTo(expected);
    }

    @Test
    void rfc8785PropertySorting() {
        // RFC 8785 section 3.2.3 plus the "weird" reference vector: UTF-16 code unit order.
        String input = """
                {"\\u20ac":"Euro Sign","\\r":"Carriage Return","\\u000a":"Newline","1":"One",
                 "\\u0080":"Control\\u007f","\\ud83d\\ude02":"Smiley","\\u00f6":"Latin Small Letter O With Diaeresis",
                 "\\ufb33":"Hebrew Letter Dalet With Dagesh","</script>":"Browser Challenge"}
                """;
        String expected = "{\"\\n\":\"Newline\",\"\\r\":\"Carriage Return\",\"1\":\"One\",\"</script>\":"
                + "\"Browser Challenge\",\"\u0080\":\"Control\u007f\",\"\u00f6\":\"Latin Small Letter O With"
                + " Diaeresis\",\"\u20ac\":\"Euro Sign\",\"\ud83d\ude02\":\"Smiley\",\"\ufb33\":\"Hebrew Letter"
                + " Dalet With Dagesh\"}";
        assertThat(CanonicalJson.canonicalize(input)).isEqualTo(expected);
    }

    @Test
    void referenceStructuresAndArrays() {
        assertThat(CanonicalJson.canonicalize("[56,{\"d\":true,\"10\":null,\"1\":[]}]"))
                .isEqualTo("[56,{\"1\":[],\"10\":null,\"d\":true}]");
        assertThat(CanonicalJson.canonicalize("""
                {"1":{"f":{"f":"hi","F":5},"\\n":56.0},"10":{},"":"empty","a":{},"111":[{"e":"yes","E":"no"}],"A":{}}
                """)).isEqualTo("{\"\":\"empty\",\"1\":{\"\\n\":56,\"f\":{\"F\":5,\"f\":\"hi\"}},\"10\":{},"
                + "\"111\":[{\"E\":\"no\",\"e\":\"yes\"}],\"A\":{},\"a\":{}}");
        assertThat(CanonicalJson.canonicalize("{\"Unnormalized Unicode\":\"A\\u030a\"}"))
                .isEqualTo("{\"Unnormalized Unicode\":\"A\u030a\"}");
        assertThat(CanonicalJson.canonicalize("\"\\u001f\\b\\f\\t\"")).isEqualTo("\"\\u001f\\b\\f\\t\"");
        assertThat(CanonicalJson.canonicalize(" true ")).isEqualTo("true");
        assertThat(CanonicalJson.canonicalize("null")).isEqualTo("null");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "0000000000000000, 0",
            "8000000000000000, 0",
            "0000000000000001, 5e-324",
            "8000000000000001, -5e-324",
            "7fefffffffffffff, 1.7976931348623157e+308",
            "ffefffffffffffff, -1.7976931348623157e+308",
            "4340000000000000, 9007199254740992",
            "c340000000000000, -9007199254740992",
            "4430000000000000, 295147905179352830000",
            "44b52d02c7e14af5, 9.999999999999997e+22",
            "44b52d02c7e14af6, 1e+23",
            "44b52d02c7e14af7, 1.0000000000000001e+23",
            "444b1ae4d6e2ef4e, 999999999999999700000",
            "444b1ae4d6e2ef4f, 999999999999999900000",
            "444b1ae4d6e2ef50, 1e+21",
            "3eb0c6f7a0b5ed8c, 9.999999999999997e-7",
            "3eb0c6f7a0b5ed8d, 0.000001",
            "41b3de4355555553, 333333333.3333332",
            "41b3de4355555554, 333333333.33333325",
            "41b3de4355555555, 333333333.3333333",
            "41b3de4355555556, 333333333.3333334",
            "41b3de4355555557, 333333333.33333343",
            "becbf647612f3696, -0.0000033333333333333333",
            "43143ff3c1cb0959, 1424953923781206.2"
    })
    void rfc8785AppendixBNumbers(String ieeeHex, String expected) {
        double value = Double.longBitsToDouble(Long.parseUnsignedLong(ieeeHex, 16));
        String json = "[" + (value == 0 && 1 / value < 0 ? "-0.0" : Double.toString(value)) + "]";
        assertThat(CanonicalJson.canonicalize(json)).isEqualTo("[" + expected + "]");
    }

    @Test
    void numbersCompareByValueNotByText() {
        assertThat(CanonicalJson.canonicalize("{\"a\":5000.00,\"b\":5E3,\"c\":-0,\"d\":1800.071}"))
                .isEqualTo("{\"a\":5000,\"b\":5000,\"c\":0,\"d\":1800.071}");
        assertThat(CanonicalJson.sha256Hex("{\"amount\":5000}"))
                .isEqualTo(CanonicalJson.sha256Hex("{ \"amount\" : 5000.0 }"));
        assertThat(CanonicalJson.canonicalize("12345678901234567890")).isEqualTo("12345678901234567000");
    }

    @Test
    void hashesUtf8OfTheCanonicalForm() {
        assertThat(CanonicalJson.sha256Hex("{ }"))
                .isEqualTo("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
        assertThat(CanonicalJson.sha256Hex("{\"b\":[true,null],\"a\":1.0}"))
                .isEqualTo("1cc69c7fa23616ca2ec3ee70d24390a6225c8832db8a4c814c7e0e7f942f8668");
        assertThat(Jcs.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void rejectsWhatRfc8785Cannot() {
        assertThatThrownBy(() -> CanonicalJson.canonicalize("{\"a\":1,\"a\":2}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> CanonicalJson.canonicalize("[1e400]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.canonicalize("[1,2,]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.canonicalize("{\"a\":01}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.canonicalize("{} {}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.canonicalize("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.canonicalize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.canonicalize("[NaN]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Jcs.number("1e999")).isInstanceOf(IllegalArgumentException.class);
    }
}
