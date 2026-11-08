package com.orqix.compiler;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * Document-type templates of extract skills that read a request file (doc 29 section 7.11, CG-A06,
 * docs/build/S146-parts/B-document-extraction.md): the built-in {@code invoice}, {@code idDocument} and
 * {@code bankStatement} templates bring typed fields, required flags, validation hints and cross-field checks; a
 * {@code custom} document uses the skill's own fields (with their own hints). Pure Java: the skill contract, the
 * compiler (step outputs) and the runtime (validation of extracted and corrected values) read templates the same way.
 */
public final class DocumentTemplates {

    /** Document types a skill can declare. */
    public static final Set<String> TYPES = Set.of("invoice", "idDocument", "bankStatement", "custom");

    /** A field of a template or skill. {@code hint} is null or one of iban, currency, country, pastDate, futureDate. */
    public record Field(String name, String type, boolean required, String description, String hint) {
    }

    /**
     * A cross-field check. {@code sum}: {@code target = plus... - minus...} within {@code tolerance}; {@code after}:
     * the date {@code target} is not before the date {@code other}; {@code lineItems}: the sum of the line amounts the
     * OCR provider read equals {@code target} (falling back to {@code other} when the target is absent).
     */
    public record Check(String kind, String target, List<String> plus, List<String> minus, String other,
            BigDecimal tolerance) {
    }

    /** A built-in template. */
    public record Template(String type, String label, List<Field> fields, List<Check> checks) {
    }

    /** A validation problem of one field: a stable code and a sentence for the verifier. */
    public record Problem(String field, String code, String message) {
    }

    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final Set<String> CURRENCIES = Set.of("AED", "ARS", "AUD", "BGN", "BRL", "CAD", "CHF", "CLP", "CNY",
            "COP", "CZK", "DKK", "EGP", "EUR", "GBP", "HKD", "HUF", "IDR", "ILS", "INR", "ISK", "JPY", "KES", "KRW", "MAD",
            "MXN", "MYR", "NGN", "NOK", "NZD", "PEN", "PHP", "PKR", "PLN", "QAR", "RON", "RSD", "RUB", "SAR", "SEK", "SGD",
            "THB", "TRY", "TWD", "UAH", "USD", "VND", "ZAR");
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    private static final Set<String> COUNTRIES3 = alpha3();

    private static final Map<String, Template> BUILT_IN = definitions();

    private DocumentTemplates() {
    }

    /** The built-in template of a type; empty for {@code custom} and unknown types. */
    public static Optional<Template> builtIn(String type) {
        return Optional.ofNullable(type == null ? null : BUILT_IN.get(type));
    }

    /** All built-in templates (for Studio and the API). */
    public static List<Template> builtIns() {
        return List.copyOf(BUILT_IN.values());
    }

    /** @return the skill's document type, or null when the skill does not read a file */
    public static String documentType(JsonNode skill) {
        JsonNode document = skill.path("document");
        return document.isObject() ? document.path("type").asString(null) : null;
    }

    /** @return the input carrying the file, or null when the skill does not read a file */
    public static String attachmentInput(JsonNode skill) {
        JsonNode document = skill.path("document");
        return document.isObject() ? document.path("attachmentInput").asString(null) : null;
    }

    /**
     * The fields a skill extracts: its own {@code fields} when it declares them (a field named like a template field
     * takes the template's hint unless it sets its own), otherwise the fields of its built-in template.
     */
    public static List<Field> effectiveFields(JsonNode skill) {
        Template template = builtIn(documentType(skill)).orElse(null);
        Map<String, Field> fromTemplate = new LinkedHashMap<>();
        if (template != null) {
            template.fields().forEach(f -> fromTemplate.put(f.name(), f));
        }
        List<Field> fields = new ArrayList<>();
        if (skill.path("fields").isArray() && !skill.path("fields").isEmpty()) {
            for (JsonNode f : skill.path("fields")) {
                String name = f.path("name").asString();
                Field known = fromTemplate.get(name);
                String hint = f.path("hint").asString(known == null ? null : known.hint());
                fields.add(new Field(name, f.path("type").asString("string"), f.path("required").asBoolean(false),
                        f.path("description").asString(known == null ? null : known.description()), hint));
            }
            return fields;
        }
        return template == null ? List.of() : template.fields();
    }

    /** The template's cross-field checks whose fields the skill extracts. */
    public static List<Check> checks(JsonNode skill) {
        Template template = builtIn(documentType(skill)).orElse(null);
        if (template == null) {
            return List.of();
        }
        Set<String> names = new java.util.HashSet<>();
        effectiveFields(skill).forEach(f -> names.add(f.name()));
        List<Check> checks = new ArrayList<>();
        for (Check c : template.checks()) {
            List<String> used = new ArrayList<>(c.plus());
            used.addAll(c.minus());
            if ("after".equals(c.kind())) {
                used.add(c.other());
            }
            used.add(c.target());
            if (names.containsAll(used.stream().filter(n -> n != null).toList())) {
                checks.add(c);
            }
        }
        return checks;
    }

    /**
     * Validation of the values of one document: types are assumed checked; this applies the hints and the
     * cross-field checks. A check is skipped while one of its values is missing.
     *
     * @param values         field values by name (absent or null when not extracted)
     * @param lineItemAmounts amounts of the line items the OCR provider read, or empty
     * @param today          the date hints compare with
     */
    public static List<Problem> validate(List<Field> fields, List<Check> checks, Map<String, JsonNode> values,
            List<BigDecimal> lineItemAmounts, LocalDate today) {
        List<Problem> problems = new ArrayList<>();
        for (Field f : fields) {
            JsonNode value = values.get(f.name());
            if (value == null || value.isNull() || value.isMissingNode()) {
                continue;
            }
            String hint = f.hint();
            if (hint == null) {
                continue;
            }
            String text = value.asString(value.toString()).strip();
            switch (hint) {
                case "iban" -> {
                    if (!ibanValid(text)) {
                        problems.add(new Problem(f.name(), "IBAN_CHECKSUM", "The IBAN is not valid (its check digits do"
                                + " not match)."));
                    }
                }
                case "currency" -> {
                    if (!CURRENCIES.contains(text.toUpperCase(Locale.ROOT)) || text.length() != 3) {
                        problems.add(new Problem(f.name(), "CURRENCY_UNKNOWN", "The currency is not an ISO 4217 code."));
                    }
                }
                case "country" -> {
                    String upper = text.toUpperCase(Locale.ROOT);
                    if (!(upper.length() == 2 && COUNTRIES.contains(upper))
                            && !(upper.length() == 3 && COUNTRIES3.contains(upper))) {
                        problems.add(new Problem(f.name(), "COUNTRY_UNKNOWN", "The country is not an ISO 3166 code."));
                    }
                }
                case "pastDate", "futureDate" -> {
                    LocalDate date = date(value);
                    if (date == null) {
                        problems.add(new Problem(f.name(), "DATE_FORMAT", "The date is not written YYYY-MM-DD."));
                    } else if ("pastDate".equals(hint) && date.isAfter(today)) {
                        problems.add(new Problem(f.name(), "DATE_IN_FUTURE", "The date lies in the future."));
                    } else if ("futureDate".equals(hint) && date.isBefore(today)) {
                        problems.add(new Problem(f.name(), "DATE_IN_PAST", "The date has already passed."));
                    }
                }
                default -> {
                    // unknown hints are refused by the contract
                }
            }
        }
        for (Check c : checks) {
            switch (c.kind()) {
                case "sum" -> {
                    BigDecimal target = number(values.get(c.target()));
                    BigDecimal sum = BigDecimal.ZERO;
                    boolean complete = target != null;
                    for (String p : c.plus()) {
                        BigDecimal n = number(values.get(p));
                        complete &= n != null;
                        sum = n == null ? sum : sum.add(n);
                    }
                    for (String m : c.minus()) {
                        BigDecimal n = number(values.get(m));
                        complete &= n != null;
                        sum = n == null ? sum : sum.subtract(n);
                    }
                    if (complete && target.subtract(sum).abs().compareTo(c.tolerance()) > 0) {
                        problems.add(new Problem(c.target(), "SUM_MISMATCH", "The value does not equal "
                                + equation(c) + " (difference " + target.subtract(sum).toPlainString() + ")."));
                    }
                }
                case "after" -> {
                    LocalDate later = date(values.get(c.target()));
                    LocalDate earlier = date(values.get(c.other()));
                    if (later != null && earlier != null && later.isBefore(earlier)) {
                        problems.add(new Problem(c.target(), "DATE_ORDER", "The date is before " + c.other() + "."));
                    }
                }
                case "lineItems" -> {
                    if (lineItemAmounts.isEmpty()) {
                        continue;
                    }
                    String field = values.get(c.target()) != null && !values.get(c.target()).isNull() ? c.target()
                            : c.other();
                    BigDecimal target = number(values.get(field));
                    BigDecimal sum = lineItemAmounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (target != null && target.subtract(sum).abs().compareTo(c.tolerance()) > 0) {
                        problems.add(new Problem(field, "LINE_ITEMS_MISMATCH", "The value does not equal the sum of"
                                + " the " + lineItemAmounts.size() + " line items (" + sum.toPlainString() + ")."));
                    }
                }
                default -> {
                }
            }
        }
        return problems;
    }

    /** ISO 13616 mod-97 check of an IBAN (spaces ignored). */
    public static boolean ibanValid(String text) {
        String iban = text.replace(" ", "").toUpperCase(Locale.ROOT);
        if (!iban.matches("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$")) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    private static String equation(Check c) {
        StringBuilder s = new StringBuilder(String.join(" + ", c.plus()));
        c.minus().forEach(m -> s.append(" - ").append(m));
        return s.toString();
    }

    private static BigDecimal number(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asString("").strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(JsonNode value) {
        if (value == null || !value.isString()) {
            return null;
        }
        try {
            return value.asString().matches("^\\d{4}-\\d{2}-\\d{2}$") ? LocalDate.parse(value.asString()) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Set<String> alpha3() {
        Set<String> codes = new java.util.HashSet<>();
        for (String c : Locale.getISOCountries()) {
            try {
                codes.add(Locale.of("", c).getISO3Country());
            } catch (java.util.MissingResourceException ignored) {
                // a country without an alpha-3 code
            }
        }
        return Set.copyOf(codes);
    }

    private static Field f(String name, String type, boolean required, String description, String hint) {
        return new Field(name, type, required, description, hint);
    }

    private static Check sum(String target, List<String> plus, List<String> minus) {
        return new Check("sum", target, plus, minus, null, CENT);
    }

    private static Check after(String later, String earlier) {
        return new Check("after", later, List.of(), List.of(), earlier, BigDecimal.ZERO);
    }

    private static Map<String, Template> definitions() {
        Map<String, Template> t = new LinkedHashMap<>();
        t.put("invoice", new Template("invoice", "Invoice", List.of(
                f("invoiceNumber", "string", true, "The invoice number as printed", null),
                f("invoiceDate", "date", true, "The invoice date", "pastDate"),
                f("dueDate", "date", false, "The payment due date", null),
                f("supplierName", "string", true, "The name of the company that issued the invoice", null),
                f("supplierTaxId", "string", false, "The supplier's VAT or tax number", null),
                f("customerName", "string", false, "The name of the customer the invoice is addressed to", null),
                f("purchaseOrderNumber", "string", false, "The purchase order number the invoice refers to", null),
                f("currency", "string", false, "The ISO 4217 currency code", "currency"),
                f("subtotalAmount", "number", false, "The total before tax", null),
                f("taxAmount", "number", false, "The total tax", null),
                f("totalAmount", "number", true, "The amount to pay, tax included", null),
                f("iban", "string", false, "The IBAN to pay to", "iban")),
                List.of(sum("totalAmount", List.of("subtotalAmount", "taxAmount"), List.of()),
                        new Check("lineItems", "subtotalAmount", List.of(), List.of(), "totalAmount", CENT),
                        after("dueDate", "invoiceDate"))));
        t.put("idDocument", new Template("idDocument", "ID document", List.of(
                f("documentKind", "string", false, "passport, identityCard or drivingLicence", null),
                f("documentNumber", "string", true, "The document number", null),
                f("surname", "string", true, "The holder's surname", null),
                f("givenNames", "string", true, "The holder's given names", null),
                f("dateOfBirth", "date", true, "The holder's date of birth", "pastDate"),
                f("issueDate", "date", false, "The date the document was issued", "pastDate"),
                f("expiryDate", "date", true, "The date the document expires", "futureDate"),
                f("issuingCountry", "string", false, "The issuing country (ISO 3166 code)", "country"),
                f("nationality", "string", false, "The holder's nationality (ISO 3166 code)", "country")),
                List.of(after("expiryDate", "issueDate"), after("issueDate", "dateOfBirth"))));
        t.put("bankStatement", new Template("bankStatement", "Bank statement", List.of(
                f("bankName", "string", false, "The bank's name", null),
                f("accountHolder", "string", true, "The account holder's name", null),
                f("iban", "string", true, "The account's IBAN", "iban"),
                f("currency", "string", false, "The account currency (ISO 4217 code)", "currency"),
                f("statementStartDate", "date", true, "The first day the statement covers", "pastDate"),
                f("statementEndDate", "date", true, "The last day the statement covers", null),
                f("openingBalance", "number", true, "The balance at the start of the period", null),
                f("closingBalance", "number", true, "The balance at the end of the period", null),
                f("totalCredits", "number", false, "The sum of all credits in the period", null),
                f("totalDebits", "number", false, "The sum of all debits in the period (a positive number)", null)),
                List.of(after("statementEndDate", "statementStartDate"),
                        sum("closingBalance", List.of("openingBalance", "totalCredits"), List.of("totalDebits")))));
        return t;
    }
}
