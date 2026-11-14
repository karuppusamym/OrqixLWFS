package com.orqix.ai.ocr;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.orqix.ai.provider.SigV4;
import com.orqix.integration.OcrDocument;
import com.orqix.integration.OcrException;
import com.orqix.integration.OcrProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Amazon Textract (JSON protocol, no SDK), signed with Signature V4 (the Bedrock signer, service {@code textract}):
 * {@code AnalyzeExpense} for invoices (summary fields and line items), {@code AnalyzeID} for ID documents and
 * {@code AnalyzeDocument} with forms for everything else. Synchronous operations only: images and single-page PDFs up
 * to 10 MiB. Blocks become pages, lines and words (Textract boxes are already normalized); form keys, expense summary
 * fields and identity fields become key-value pairs mapped to the template fields.
 */
final class TextractOcr implements OcrProvider {

    static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Map<String, String> EXPENSE_FIELDS = Map.ofEntries(
            Map.entry("INVOICE_RECEIPT_ID", "invoiceNumber"), Map.entry("INVOICE_RECEIPT_DATE", "invoiceDate"),
            Map.entry("DUE_DATE", "dueDate"), Map.entry("VENDOR_NAME", "supplierName"),
            Map.entry("TAX_PAYER_ID", "supplierTaxId"), Map.entry("VENDOR_VAT_NUMBER", "supplierTaxId"),
            Map.entry("RECEIVER_NAME", "customerName"), Map.entry("PO_NUMBER", "purchaseOrderNumber"),
            Map.entry("SUBTOTAL", "subtotalAmount"), Map.entry("TAX", "taxAmount"), Map.entry("TOTAL", "totalAmount"));
    private static final Map<String, String> ID_FIELDS = Map.ofEntries(Map.entry("DOCUMENT_NUMBER", "documentNumber"),
            Map.entry("FIRST_NAME", "givenNames"), Map.entry("LAST_NAME", "surname"),
            Map.entry("DATE_OF_BIRTH", "dateOfBirth"), Map.entry("EXPIRATION_DATE", "expiryDate"),
            Map.entry("DATE_OF_ISSUE", "issueDate"), Map.entry("ID_TYPE", "documentKind"));
    /** Form keys (lower case, letters and digits only) that name template fields. */
    private static final Map<String, String> FORM_KEYS = Map.ofEntries(Map.entry("iban", "iban"),
            Map.entry("accountholder", "accountHolder"), Map.entry("openingbalance", "openingBalance"),
            Map.entry("closingbalance", "closingBalance"), Map.entry("currency", "currency"),
            Map.entry("invoicenumber", "invoiceNumber"), Map.entry("invoiceno", "invoiceNumber"),
            Map.entry("total", "totalAmount"));

    private final String id;
    private final OcrProperties.Provider config;
    private final SigV4.Credentials credentials;
    private final OcrHttp http;

    TextractOcr(String id, OcrProperties.Provider config, SigV4.Credentials credentials, OcrHttp http) {
        this.id = id;
        this.config = config;
        this.credentials = credentials;
        this.http = http;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Result extract(byte[] content, String contentType) {
        return Result.of(analyze(content, contentType, "custom", config.timeout()).text());
    }

    static String operation(String documentType) {
        return switch (documentType == null ? "" : documentType) {
            case "invoice" -> "AnalyzeExpense";
            case "idDocument" -> "AnalyzeID";
            default -> "AnalyzeDocument";
        };
    }

    @Override
    public OcrDocument analyze(byte[] content, String contentType, String documentType, Duration timeout) {
        if (content.length > MAX_BYTES) {
            throw new OcrException(OcrException.Kind.TOO_LARGE, 0, id + ": file too large");
        }
        if (!List.of("application/pdf", "image/png", "image/jpeg", "image/tiff").contains(contentType)) {
            throw new OcrException(OcrException.Kind.UNSUPPORTED, 0, id + ": unsupported file type");
        }
        if (config.region().isEmpty()) {
            throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, id + ": no region configured");
        }
        String operation = operation(documentType);
        ObjectNode request = http.json().object();
        String bytes = Base64.getEncoder().encodeToString(content);
        switch (operation) {
            case "AnalyzeID" -> request.putArray("DocumentPages").addObject().put("Bytes", bytes);
            case "AnalyzeExpense" -> request.putObject("Document").put("Bytes", bytes);
            default -> {
                request.putObject("Document").put("Bytes", bytes);
                request.putArray("FeatureTypes").add("FORMS");
            }
        }
        byte[] body = http.json().write(request).getBytes(StandardCharsets.UTF_8);
        URI uri = config.baseUrl() != null ? config.baseUrl()
                : URI.create("https://textract." + config.region() + ".amazonaws.com/");
        Instant now = Instant.now();
        String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        Map<String, String> signed = new TreeMap<>();
        signed.put("content-type", "application/x-amz-json-1.1");
        signed.put("host", host);
        signed.put("x-amz-date", SigV4.AMZ_DATE.format(now));
        signed.put("x-amz-target", "Textract." + operation);
        if (credentials.sessionToken() != null && !credentials.sessionToken().isBlank()) {
            signed.put("x-amz-security-token", credentials.sessionToken());
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        Map<String, String> headers = new LinkedHashMap<>();
        signed.forEach((k, v) -> {
            if (!"host".equals(k)) {
                headers.put(k, v);
            }
        });
        headers.put("Authorization", SigV4.authorization(credentials, config.region(), "textract", "POST", path, "",
                signed, body, now));
        Duration budget = timeout.compareTo(config.timeout()) < 0 ? timeout : config.timeout();
        OcrHttp.Answer answer = http.send(id + " " + operation, "POST", uri.getRawPath() == null
                || uri.getRawPath().isEmpty() ? URI.create(uri + "/") : uri, headers, body, budget);
        return parse(id, operation, documentType, answer.body());
    }

    /** Parses a Textract answer of {@code operation} (visible for tests). */
    static OcrDocument parse(String provider, String operation, String documentType, JsonNode answer) {
        List<OcrDocument.KeyValue> keyValues = new ArrayList<>();
        List<BigDecimal> items = new ArrayList<>();
        List<JsonNode> blocks = new ArrayList<>();
        switch (operation) {
            case "AnalyzeExpense" -> {
                for (JsonNode doc : answer.path("ExpenseDocuments")) {
                    doc.path("Blocks").forEach(blocks::add);
                    for (JsonNode f : doc.path("SummaryFields")) {
                        String type = f.path("Type").path("Text").asString("");
                        String field = EXPENSE_FIELDS.get(type);
                        JsonNode value = f.path("ValueDetection");
                        if (!value.path("Text").isString()) {
                            continue;
                        }
                        keyValues.add(new OcrDocument.KeyValue(field, type, value.path("Text").asString(),
                                percent(value.path("Confidence")), f.path("PageNumber").asInt(1),
                                box(value.path("Geometry"))));
                        if ("TOTAL".equals(type) && f.path("Currency").path("Code").isString()) {
                            keyValues.add(new OcrDocument.KeyValue("currency", "TOTAL.Currency",
                                    f.path("Currency").path("Code").asString(), percent(f.path("Currency")
                                            .path("Confidence")), f.path("PageNumber").asInt(1), null));
                        }
                    }
                    for (JsonNode group : doc.path("LineItemGroups")) {
                        for (JsonNode item : group.path("LineItems")) {
                            for (JsonNode f : item.path("LineItemExpenseFields")) {
                                if ("PRICE".equals(f.path("Type").path("Text").asString(""))) {
                                    BigDecimal amount = amount(f.path("ValueDetection").path("Text").asString(""));
                                    if (amount != null) {
                                        items.add(amount);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            case "AnalyzeID" -> {
                for (JsonNode doc : answer.path("IdentityDocuments")) {
                    doc.path("Blocks").forEach(blocks::add);
                    for (JsonNode f : doc.path("IdentityDocumentFields")) {
                        String type = f.path("Type").path("Text").asString("");
                        JsonNode value = f.path("ValueDetection");
                        String text = value.path("NormalizedValue").path("Value").isString()
                                ? value.path("NormalizedValue").path("Value").asString() : value.path("Text").asString("");
                        if (text.isEmpty()) {
                            continue;
                        }
                        if ("Date".equals(value.path("NormalizedValue").path("ValueType").asString("")) && text.length() >= 10) {
                            text = text.substring(0, 10);
                        }
                        String field = ID_FIELDS.get(type);
                        if ("documentKind".equals(field)) {
                            String lower = text.toLowerCase(Locale.ROOT);
                            text = lower.contains("passport") ? "passport" : lower.contains("driver") ? "drivingLicence"
                                    : "identityCard";
                        }
                        keyValues.add(new OcrDocument.KeyValue(field, type, text, percent(value.path("Confidence")), 1,
                                null));
                    }
                }
            }
            default -> answer.path("Blocks").forEach(blocks::add);
        }
        Map<String, JsonNode> byId = new HashMap<>();
        blocks.forEach(b -> byId.put(b.path("Id").asString(""), b));
        Map<Integer, List<OcrDocument.Line>> lines = new TreeMap<>();
        int count = 0;
        for (JsonNode b : blocks) {
            String type = b.path("BlockType").asString("");
            int page = b.path("Page").asInt(1);
            if ("PAGE".equals(type)) {
                lines.computeIfAbsent(page, k -> new ArrayList<>());
            } else if ("LINE".equals(type) && count++ < OcrDocument.MAX_LINES && page <= OcrDocument.MAX_PAGES) {
                List<OcrDocument.Word> words = new ArrayList<>();
                for (String child : children(b, "CHILD")) {
                    JsonNode w = byId.get(child);
                    if (w != null && "WORD".equals(w.path("BlockType").asString(""))) {
                        words.add(new OcrDocument.Word(w.path("Text").asString(""), box(w.path("Geometry")),
                                percent(w.path("Confidence"))));
                    }
                }
                lines.computeIfAbsent(page, k -> new ArrayList<>()).add(new OcrDocument.Line(b.path("Text").asString(""),
                        box(b.path("Geometry")), percent(b.path("Confidence")), words));
            } else if ("KEY_VALUE_SET".equals(type) && entity(b, "KEY")) {
                String key = text(b, byId);
                StringBuilder value = new StringBuilder();
                OcrDocument.Box valueBox = null;
                Double confidence = percent(b.path("Confidence"));
                for (String v : children(b, "VALUE")) {
                    JsonNode vb = byId.get(v);
                    if (vb != null) {
                        value.append(text(vb, byId));
                        valueBox = box(vb.path("Geometry"));
                        Double vc = percent(vb.path("Confidence"));
                        confidence = confidence == null ? vc : vc == null ? confidence : Math.min(confidence, vc);
                    }
                }
                if (!value.isEmpty()) {
                    String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                    keyValues.add(new OcrDocument.KeyValue(FORM_KEYS.get(normalized), key.strip(), value.toString()
                            .strip(), confidence, page, valueBox));
                }
            }
        }
        List<OcrDocument.Page> pages = new ArrayList<>();
        lines.forEach((page, l) -> pages.add(new OcrDocument.Page(page, 1, 1, "normalized", l)));
        return new OcrDocument(provider, operation, pages, keyValues, items);
    }

    private static boolean entity(JsonNode block, String type) {
        for (JsonNode t : block.path("EntityTypes")) {
            if (type.equals(t.asString(""))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> children(JsonNode block, String relation) {
        List<String> ids = new ArrayList<>();
        for (JsonNode r : block.path("Relationships")) {
            if (relation.equals(r.path("Type").asString(""))) {
                r.path("Ids").forEach(i -> ids.add(i.asString("")));
            }
        }
        return ids;
    }

    private static String text(JsonNode block, Map<String, JsonNode> byId) {
        StringBuilder s = new StringBuilder();
        for (String child : children(block, "CHILD")) {
            JsonNode w = byId.get(child);
            if (w != null && w.path("Text").isString()) {
                if (!s.isEmpty()) {
                    s.append(' ');
                }
                s.append(w.path("Text").asString());
            }
        }
        return s.toString();
    }

    private static Double percent(JsonNode confidence) {
        return confidence.isNumber() ? Math.max(0, Math.min(1, confidence.asDouble() / 100)) : null;
    }

    static OcrDocument.Box box(JsonNode geometry) {
        JsonNode b = geometry.path("BoundingBox");
        if (!b.isObject()) {
            return null;
        }
        return OcrDocument.Box.of(b.path("Left").asDouble(), b.path("Top").asDouble(), b.path("Width").asDouble(),
                b.path("Height").asDouble());
    }

    private static BigDecimal amount(String text) {
        String digits = text.replaceAll("[^0-9.,-]", "");
        if (digits.isEmpty()) {
            return null;
        }
        if (digits.contains(",") && digits.lastIndexOf(',') > digits.lastIndexOf('.')) {
            digits = digits.replace(".", "").replace(',', '.');
        } else {
            digits = digits.replace(",", "");
        }
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
