package com.orqix.ai.ocr;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.orqix.integration.OcrDocument;
import com.orqix.integration.OcrException;
import com.orqix.integration.OcrProvider;

import tools.jackson.databind.JsonNode;

/**
 * Azure AI Document Intelligence (REST, no SDK): {@code POST {endpoint}/documentintelligence/documentModels/{model}:analyze}
 * with the file as {@code base64Source}, then polling the {@code Operation-Location} address (same host as the endpoint,
 * egress-checked) until the analysis succeeds or fails. Models: {@code prebuilt-invoice} for invoices,
 * {@code prebuilt-idDocument} for ID documents, {@code prebuilt-read} otherwise. Lines and words with their polygons
 * become normalized boxes; the prebuilt models' fields become key-value pairs mapped to the template fields.
 */
final class AzureDocumentIntelligenceOcr implements OcrProvider {

    static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Map<String, String> INVOICE_FIELDS = Map.ofEntries(Map.entry("InvoiceId", "invoiceNumber"),
            Map.entry("InvoiceDate", "invoiceDate"), Map.entry("DueDate", "dueDate"),
            Map.entry("VendorName", "supplierName"), Map.entry("VendorTaxId", "supplierTaxId"),
            Map.entry("CustomerName", "customerName"), Map.entry("PurchaseOrder", "purchaseOrderNumber"),
            Map.entry("SubTotal", "subtotalAmount"), Map.entry("TotalTax", "taxAmount"),
            Map.entry("InvoiceTotal", "totalAmount"));
    private static final Map<String, String> ID_FIELDS = Map.ofEntries(Map.entry("DocumentNumber", "documentNumber"),
            Map.entry("FirstName", "givenNames"), Map.entry("LastName", "surname"),
            Map.entry("DateOfBirth", "dateOfBirth"), Map.entry("DateOfExpiration", "expiryDate"),
            Map.entry("DateOfIssue", "issueDate"), Map.entry("CountryRegion", "issuingCountry"),
            Map.entry("Nationality", "nationality"));

    private final String id;
    private final OcrProperties.Provider config;
    private final String key;
    private final OcrHttp http;

    AzureDocumentIntelligenceOcr(String id, OcrProperties.Provider config, String key, OcrHttp http) {
        this.id = id;
        this.config = config;
        this.key = key;
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

    static String model(String documentType) {
        return switch (documentType == null ? "" : documentType) {
            case "invoice" -> "prebuilt-invoice";
            case "idDocument" -> "prebuilt-idDocument";
            default -> "prebuilt-read";
        };
    }

    @Override
    public OcrDocument analyze(byte[] content, String contentType, String documentType, Duration timeout) {
        if (content.length > MAX_BYTES) {
            throw new OcrException(OcrException.Kind.TOO_LARGE, 0, id + ": file too large");
        }
        if (!List.of("application/pdf", "image/png", "image/jpeg", "image/tiff", "image/bmp").contains(contentType)) {
            throw new OcrException(OcrException.Kind.UNSUPPORTED, 0, id + ": unsupported file type");
        }
        if (config.baseUrl() == null) {
            throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, id + ": no endpoint configured");
        }
        Instant deadline = Instant.now().plus(timeout.compareTo(config.timeout()) < 0 ? timeout : config.timeout());
        String model = model(documentType);
        URI base = config.baseUrl();
        String root = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1)
                : base.toString();
        URI analyze = URI.create(root + "/documentintelligence/documentModels/" + model + ":analyze?api-version="
                + config.apiVersion());
        byte[] body = ("{\"base64Source\":\"" + Base64.getEncoder().encodeToString(content) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = Map.of("Ocp-Apim-Subscription-Key", key, "Content-Type", "application/json");
        OcrHttp.Answer started = http.send(id + " analyze", "POST", analyze, headers, body, left(deadline));
        String location = started.header("Operation-Location").orElseThrow(() ->
                new OcrException(OcrException.Kind.UNAVAILABLE, started.status(), id + ": no operation address"));
        URI operation;
        try {
            operation = URI.create(location);
        } catch (IllegalArgumentException e) {
            throw new OcrException(OcrException.Kind.UNAVAILABLE, started.status(), id + ": bad operation address");
        }
        if (operation.getHost() == null || !operation.getHost().equalsIgnoreCase(base.getHost())
                || operation.getPort() != base.getPort() || !operation.getScheme().equalsIgnoreCase(base.getScheme())) {
            throw new OcrException(OcrException.Kind.UNAVAILABLE, started.status(), id + ": operation on another host");
        }
        Map<String, String> pollHeaders = Map.of("Ocp-Apim-Subscription-Key", key);
        while (true) {
            OcrHttp.Answer polled = http.send(id + " poll", "GET", operation, pollHeaders, null, left(deadline));
            String status = polled.body().path("status").asString("");
            switch (status) {
                case "succeeded" -> {
                    return parse(id, model, documentType, polled.body().path("analyzeResult"));
                }
                case "failed", "canceled" -> throw new OcrException(OcrException.Kind.REJECTED, polled.status(),
                        id + ": analysis " + status);
                case "notStarted", "running" -> sleep(polled, deadline);
                default -> throw new OcrException(OcrException.Kind.UNAVAILABLE, polled.status(),
                        id + ": unexpected status");
            }
        }
    }

    private void sleep(OcrHttp.Answer polled, Instant deadline) {
        long wait = config.pollInterval().toMillis();
        try {
            wait = polled.header("Retry-After").map(v -> Long.parseLong(v.trim()) * 1000).orElse(wait);
        } catch (NumberFormatException ignored) {
            // keep the configured interval
        }
        wait = Math.max(50, Math.min(wait, 10_000));
        if (Instant.now().plusMillis(wait).isAfter(deadline)) {
            throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, id + ": timeout");
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException(OcrException.Kind.UNAVAILABLE, 0, id + ": interrupted");
        }
    }

    private static Duration left(Instant deadline) {
        return Duration.between(Instant.now(), deadline);
    }

    /** Parses an {@code analyzeResult} (visible for tests). */
    static OcrDocument parse(String provider, String model, String documentType, JsonNode result) {
        List<OcrDocument.Page> pages = new ArrayList<>();
        int lineCount = 0;
        for (JsonNode p : result.path("pages")) {
            if (pages.size() >= OcrDocument.MAX_PAGES) {
                break;
            }
            double width = p.path("width").asDouble(0);
            double height = p.path("height").asDouble(0);
            List<JsonNode> words = new ArrayList<>();
            p.path("words").forEach(words::add);
            List<OcrDocument.Line> lines = new ArrayList<>();
            for (JsonNode l : p.path("lines")) {
                if (lineCount++ >= OcrDocument.MAX_LINES) {
                    break;
                }
                int start = l.path("spans").path(0).path("offset").asInt(-1);
                int end = start + l.path("spans").path(0).path("length").asInt(0);
                List<OcrDocument.Word> inLine = new ArrayList<>();
                double sum = 0;
                for (JsonNode w : words) {
                    int at = w.path("span").path("offset").asInt(-2);
                    if (at >= start && at < end) {
                        double c = w.path("confidence").asDouble(1);
                        sum += c;
                        inLine.add(new OcrDocument.Word(w.path("content").asString(""), box(w.path("polygon"), width,
                                height), c));
                    }
                }
                lines.add(new OcrDocument.Line(l.path("content").asString(""), box(l.path("polygon"), width, height),
                        inLine.isEmpty() ? null : sum / inLine.size(), inLine));
            }
            pages.add(new OcrDocument.Page(p.path("pageNumber").asInt(pages.size() + 1), width, height,
                    p.path("unit").asString("pixel"), lines));
        }
        List<OcrDocument.KeyValue> keyValues = new ArrayList<>();
        List<BigDecimal> items = new ArrayList<>();
        JsonNode document = result.path("documents").path(0);
        Map<String, String> mapping = "invoice".equals(documentType) ? INVOICE_FIELDS
                : "idDocument".equals(documentType) ? ID_FIELDS : Map.of();
        for (var e : document.path("fields").properties()) {
            JsonNode f = e.getValue();
            if ("Items".equals(e.getKey())) {
                for (JsonNode item : f.path("valueArray")) {
                    JsonNode amount = item.path("valueObject").path("Amount");
                    BigDecimal value = amount.path("valueCurrency").path("amount").isNumber()
                            ? amount.path("valueCurrency").path("amount").decimalValue()
                            : amount.path("valueNumber").isNumber() ? amount.path("valueNumber").decimalValue() : null;
                    if (value != null) {
                        items.add(value);
                    }
                }
                continue;
            }
            String field = mapping.get(e.getKey());
            String value = value(f);
            if (value == null) {
                continue;
            }
            JsonNode region = f.path("boundingRegions").path(0);
            int page = region.path("pageNumber").asInt(1);
            OcrDocument.Page sized = pages.stream().filter(pg -> pg.number() == page).findFirst().orElse(null);
            OcrDocument.Box box = sized == null ? null : box(region.path("polygon"), sized.width(), sized.height());
            Double confidence = f.path("confidence").isNumber() ? f.path("confidence").asDouble() : null;
            keyValues.add(new OcrDocument.KeyValue(field, e.getKey(), value, confidence, page, box));
            if ("InvoiceTotal".equals(e.getKey()) && f.path("valueCurrency").has("currencyCode")) {
                keyValues.add(new OcrDocument.KeyValue("currency", "InvoiceTotal.currencyCode",
                        f.path("valueCurrency").path("currencyCode").asString(), confidence, page, box));
            }
        }
        String docType = document.path("docType").asString("");
        if ("idDocument".equals(documentType) && docType.startsWith("idDocument.")) {
            String kind = switch (docType.substring("idDocument.".length())) {
                case "passport" -> "passport";
                case "driverLicense" -> "drivingLicence";
                default -> "identityCard";
            };
            keyValues.add(new OcrDocument.KeyValue("documentKind", "docType", kind,
                    document.path("confidence").isNumber() ? document.path("confidence").asDouble() : null, 1, null));
        }
        return new OcrDocument(provider, model, pages, keyValues, items);
    }

    private static String value(JsonNode f) {
        if (f.path("valueString").isString()) {
            return f.path("valueString").asString();
        }
        if (f.path("valueDate").isString()) {
            return f.path("valueDate").asString();
        }
        if (f.path("valueCurrency").path("amount").isNumber()) {
            return f.path("valueCurrency").path("amount").decimalValue().toPlainString();
        }
        if (f.path("valueNumber").isNumber()) {
            return f.path("valueNumber").decimalValue().toPlainString();
        }
        if (f.path("valueCountryRegion").isString()) {
            return f.path("valueCountryRegion").asString();
        }
        return f.path("content").isString() ? f.path("content").asString() : null;
    }

    /** A polygon (x1,y1,...,x4,y4 in the page unit) as a box normalized to the page. */
    static OcrDocument.Box box(JsonNode polygon, double width, double height) {
        if (!polygon.isArray() || polygon.size() < 8 || width <= 0 || height <= 0) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i + 1 < polygon.size(); i += 2) {
            double x = polygon.get(i).asDouble();
            double y = polygon.get(i + 1).asDouble();
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return OcrDocument.Box.of(minX / width, minY / height, (maxX - minX) / width, (maxY - minY) / height);
    }
}
