package com.orqix.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WireMock stand-in for Azure AI Document Intelligence under {@code /ocr/azure/} (CG-A06 flow tests; never a real
 * service). The "image" is a PNG signature followed by UTF-8 text: each text line becomes a recognized line on a 1000 x
 * 2000 pixel page, 100 pixels apart, with word confidence 0.95, or 0.45 on a line that says "smudged". A line
 * "Invoice number: X" also becomes the prebuilt-invoice field InvoiceId (confidence 0.97). Analyses answer through the
 * Operation-Location protocol: the first poll says running, the second succeeded.
 */
public final class OcrStub {

    public static final String KEY = "ocr-stub-key-not-real";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, JsonNode> RESULTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> POLLS = new ConcurrentHashMap<>();
    private static final AtomicInteger ANALYSES = new AtomicInteger();
    private static final Pattern RESULT = Pattern.compile("^/ocr/azure/documentintelligence/documentModels/([a-zA-Z-]+)"
            + "/analyzeResults/([a-z0-9-]+).*");

    private OcrStub() {
    }

    /** Analyses started since the JVM started (a retried AI task must not start another). */
    public static int analyses() {
        return ANALYSES.get();
    }

    static ResponseDefinition handle(Request request) {
        String path = request.getUrl();
        if (!KEY.equals(request.getHeader("Ocp-Apim-Subscription-Key"))) {
            return json(401, "{\"error\":{\"code\":\"401\",\"message\":\"Access denied\"}}");
        }
        if ("POST".equals(request.getMethod().getName()) && path.contains(":analyze")) {
            String model = path.substring(path.indexOf("documentModels/") + 15, path.indexOf(":analyze"));
            byte[] content = Base64.getDecoder().decode(JSON.readTree(request.getBodyAsString()).path("base64Source")
                    .asString(""));
            String id = "op-" + ANALYSES.incrementAndGet();
            RESULTS.put(id, result(model, content));
            POLLS.put(id, new AtomicInteger());
            String base = request.getAbsoluteUrl().substring(0, request.getAbsoluteUrl().indexOf("/ocr/azure/"));
            return new ResponseDefinitionBuilder().withStatus(202).withHeader("Operation-Location", base
                    + "/ocr/azure/documentintelligence/documentModels/" + model + "/analyzeResults/" + id
                    + "?api-version=2024-11-30").build();
        }
        Matcher m = RESULT.matcher(path);
        if ("GET".equals(request.getMethod().getName()) && m.matches() && RESULTS.containsKey(m.group(2))) {
            if (POLLS.get(m.group(2)).getAndIncrement() == 0) {
                return new ResponseDefinitionBuilder().withStatus(200).withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "0").withBody("{\"status\":\"running\"}").build();
            }
            ObjectNode done = JSON.createObjectNode();
            done.put("status", "succeeded");
            done.set("analyzeResult", RESULTS.get(m.group(2)));
            return json(200, done.toString());
        }
        return json(404, "{\"error\":{\"code\":\"NotFound\"}}");
    }

    private static JsonNode result(String model, byte[] content) {
        String text = new String(content, 8, Math.max(0, content.length - 8), StandardCharsets.UTF_8);
        ObjectNode result = JSON.createObjectNode();
        result.put("apiVersion", "2024-11-30").put("modelId", model).put("content", text);
        ObjectNode page = result.putArray("pages").addObject();
        page.put("pageNumber", 1).put("width", 1000).put("height", 2000).put("unit", "pixel");
        ArrayNode words = page.putArray("words");
        ArrayNode lines = page.putArray("lines");
        ObjectNode fields = JSON.createObjectNode();
        int offset = 0;
        int y = 100;
        List<String> rows = new ArrayList<>(List.of(text.split("\n")));
        for (String row : rows) {
            String line = row.strip();
            if (line.isEmpty()) {
                offset += row.length() + 1;
                continue;
            }
            double confidence = line.contains("smudged") ? 0.45 : 0.95;
            ObjectNode l = lines.addObject();
            l.put("content", line);
            polygon(l, 100, y, 100 + line.length() * 20, y + 40);
            l.putArray("spans").addObject().put("offset", offset).put("length", line.length());
            int at = 0;
            for (String word : line.split(" ")) {
                ObjectNode w = words.addObject();
                w.put("content", word).put("confidence", confidence);
                polygon(w, 100 + at * 20, y, 100 + (at + word.length()) * 20, y + 40);
                w.putObject("span").put("offset", offset + at).put("length", word.length());
                at += word.length() + 1;
            }
            if (line.startsWith("Invoice number: ")) {
                ObjectNode f = fields.putObject("InvoiceId");
                f.put("type", "string").put("valueString", line.substring(16)).put("content", line.substring(16))
                        .put("confidence", 0.97);
                ObjectNode region = f.putArray("boundingRegions").addObject();
                region.put("pageNumber", 1);
                ArrayNode polygon = region.putArray("polygon");
                int x1 = 100 + 16 * 20;
                int x2 = 100 + line.length() * 20;
                for (int v : new int[] {x1, y, x2, y, x2, y + 40, x1, y + 40}) {
                    polygon.add(v);
                }
            }
            offset += row.length() + 1;
            y += 100;
        }
        if ("prebuilt-invoice".equals(model)) {
            ObjectNode doc = result.putArray("documents").addObject();
            doc.put("docType", "invoice").put("confidence", 0.9);
            doc.set("fields", fields);
        }
        return result;
    }

    private static void polygon(ObjectNode node, int x1, int y1, int x2, int y2) {
        ArrayNode p = node.putArray("polygon");
        for (int v : new int[] {x1, y1, x2, y1, x2, y2, x1, y2}) {
            p.add(v);
        }
    }

    private static ResponseDefinition json(int status, String body) {
        return new ResponseDefinitionBuilder().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody(body).build();
    }
}
