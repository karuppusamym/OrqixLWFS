package com.orqix.shared.junit;

import java.util.List;

/**
 * Test reports in the JUnit XML format that CI systems read (S148: workflow test runs and connector package contract
 * tests). One {@code testsuites} element with one {@code testsuite}; a case is passed, failed (an expectation did not
 * hold) or an error (it could not run). Times are 0: dry runs and replays take no meaningful time. No time stamp, so a
 * report depends only on its results (and never on the time zone).
 */
public final class JUnitXml {

    /** One test case. {@code failure} or {@code error} is the message (null when passed); {@code detail} the body. */
    public record Case(String className, String name, String failure, String error, String detail) {

        public static Case passed(String className, String name) {
            return new Case(className, name, null, null, null);
        }

        public static Case failed(String className, String name, String message, String detail) {
            return new Case(className, name, message, null, detail);
        }

        public static Case error(String className, String name, String message, String detail) {
            return new Case(className, name, null, message, detail);
        }
    }

    private JUnitXml() {
    }

    public static String write(String suiteName, List<String[]> properties, List<Case> cases) {
        long failures = cases.stream().filter(c -> c.failure() != null).count();
        long errors = cases.stream().filter(c -> c.failure() == null && c.error() != null).count();
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<testsuites name=\"orqix\" tests=\"").append(cases.size()).append("\" failures=\"").append(failures)
                .append("\" errors=\"").append(errors).append("\" time=\"0\">\n");
        x.append("  <testsuite name=\"").append(attr(suiteName)).append("\" tests=\"").append(cases.size())
                .append("\" failures=\"").append(failures).append("\" errors=\"").append(errors)
                .append("\" skipped=\"0\" time=\"0\">\n");
        if (!properties.isEmpty()) {
            x.append("    <properties>\n");
            for (String[] p : properties) {
                x.append("      <property name=\"").append(attr(p[0])).append("\" value=\"").append(attr(p[1])).append("\"/>\n");
            }
            x.append("    </properties>\n");
        }
        for (Case c : cases) {
            x.append("    <testcase classname=\"").append(attr(c.className())).append("\" name=\"").append(attr(c.name()))
                    .append("\" time=\"0\"");
            if (c.failure() == null && c.error() == null) {
                x.append("/>\n");
                continue;
            }
            x.append(">\n");
            String element = c.failure() != null ? "failure" : "error";
            String message = c.failure() != null ? c.failure() : c.error();
            x.append("      <").append(element).append(" message=\"").append(attr(message)).append("\" type=\"")
                    .append(c.failure() != null ? "AssertionFailure" : "CaseError").append('"');
            if (c.detail() == null || c.detail().isEmpty()) {
                x.append("/>\n");
            } else {
                x.append('>').append(text(c.detail())).append("</").append(element).append(">\n");
            }
            x.append("    </testcase>\n");
        }
        x.append("  </testsuite>\n</testsuites>\n");
        return x.toString();
    }

    static String attr(String value) {
        return text(value).replace("\"", "&quot;").replace("'", "&apos;").replace("\n", "&#10;").replace("\t", "&#9;");
    }

    /** Escapes markup and drops characters XML 1.0 cannot carry. */
    static String text(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            switch (cp) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> {
                    boolean allowed = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                            || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
                    if (allowed) {
                        out.appendCodePoint(cp);
                    }
                }
            }
        });
        return out.toString();
    }
}
