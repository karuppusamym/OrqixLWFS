package com.orqix.catalog.export;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/** RFC 4180 CSV rows: CRLF line ends, fields quoted when they hold a comma, quote or line break; null is empty. */
final class Csv {

    private Csv() {
    }

    static void row(Writer out, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            field(out, values.get(i));
        }
        out.write("\r\n");
    }

    static void field(Writer out, String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }
        boolean quote = false;
        for (int i = 0; i < value.length() && !quote; i++) {
            char c = value.charAt(i);
            quote = c == ',' || c == '"' || c == '\n' || c == '\r';
        }
        if (!quote) {
            out.write(value);
            return;
        }
        out.write('"');
        out.write(value.replace("\"", "\"\""));
        out.write('"');
    }
}
