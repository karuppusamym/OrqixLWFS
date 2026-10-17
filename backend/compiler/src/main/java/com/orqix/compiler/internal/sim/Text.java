package com.orqix.compiler.internal.sim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Plain-language formatting of a dry run. Every function has a twin in the studio mock layer
 * (frontend/src/mocks/simulator.ts); the golden traces in examples/fixtures/simulation keep both identical.
 */
final class Text {

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
        "Nov", "Dec"};
    private static final Pattern TRAILING_PAREN = Pattern.compile("\\s*\\([^)]*\\)\\s*$");

    private Text() {
    }

    /** @return the number with thousands separators, without trailing zeros: 5000 -> "5,000", 1800.50 -> "1,800.5" */
    static String number(BigDecimal value) {
        return group(normalize(value).toPlainString());
    }

    /** @return "USD 5,000" or "USD 1,800.50" (two decimals when the amount has cents) */
    static String money(BigDecimal value) {
        BigDecimal v = normalize(value);
        return "USD " + group(v.scale() > 0 ? v.setScale(2, RoundingMode.HALF_UP).toPlainString() : v.toPlainString());
    }

    private static String group(String plain) {
        boolean negative = plain.startsWith("-");
        if (negative) {
            plain = plain.substring(1);
        }
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                grouped.append(',');
            }
            grouped.append(whole.charAt(i));
        }
        return (negative ? "-" : "") + grouped + fraction;
    }

    /** Strips trailing zeros and negative scale (5E+3 becomes 5000). */
    static BigDecimal normalize(BigDecimal value) {
        BigDecimal v = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    /** @return the value for a person: numbers grouped, yes/no, text as is */
    static String value(Object value, boolean money) {
        if (value instanceof BigDecimal d) {
            return money ? money(d) : number(d);
        }
        if (value instanceof Boolean b) {
            return b ? "yes" : "no";
        }
        if (value instanceof List<?> list) {
            return list.size() + (list.size() == 1 ? " item" : " items");
        }
        return value == null ? "nothing" : String.valueOf(value);
    }

    /** "Amount (USD)" becomes "Amount" when the value is shown as money, so the unit is not said twice. */
    static String fieldLabel(String title, boolean money) {
        return money ? TRAILING_PAREN.matcher(title).replaceFirst("") : title;
    }

    /** @return 14 Oct 2026 */
    static String date(LocalDate d) {
        return d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1] + " " + d.getYear();
    }

    /** @return "1 minute" or "3 attempts" */
    static String count(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    static String quote(String text) {
        return "\"" + text + "\"";
    }
}
