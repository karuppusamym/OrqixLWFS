package com.orqix.ai.ocr;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.orqix.ai.provider.SigV4;
import com.orqix.integration.EgressPolicy;
import com.orqix.integration.OcrDocument;
import com.orqix.integration.OcrException;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;

import tools.jackson.databind.json.JsonMapper;

/**
 * CG-A06 OCR adapters against WireMock stand-ins only (never a real service, never a real key): Azure Document
 * Intelligence (analyze, operation polling, model per document type, polygons to boxes, prebuilt fields to template
 * fields, an operation address on another host refused), Amazon Textract (Signature V4 with the Bedrock signer and a
 * session token, the operation per document type, blocks, forms, expense and identity fields), failures classified
 * without bodies, the egress allowlist, and the local Tesseract engine (TSV parsing; a fake executable).
 */
class OcrProvidersTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
    private final Json json = new Json(JsonMapper.builder().build());
    private WireMockServer server;
    private OcrHttp http;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        String host = "localhost:" + server.port();
        http = new OcrHttp(json, new EgressPolicy(new OrqixProperties(null, null, null,
                new OrqixProperties.Egress(List.of(host), List.of(host), List.of(host)), null, null, null, null, null)));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private OcrProperties.Provider provider(String kind, String region) {
        return new OcrProperties.Provider(kind, URI.create(server.baseUrl()), "ref", region, null, List.of("eu"),
                "Test", Duration.ofSeconds(10), Duration.ofMillis(50));
    }

    @Test
    void azureAnalyzesPollsAndMapsThePrebuiltInvoiceFields() {
        server.stubFor(post(urlPathEqualTo("/documentintelligence/documentModels/prebuilt-invoice:analyze"))
                .withHeader("Ocp-Apim-Subscription-Key", equalTo("azure-test-key-not-real"))
                .withQueryParam("api-version", equalTo("2024-11-30"))
                .withRequestBody(equalToJson("{\"base64Source\":\"" + Base64.getEncoder().encodeToString(PNG) + "\"}"))
                .willReturn(aResponse().withStatus(202).withHeader("Operation-Location",
                        server.baseUrl() + "/documentintelligence/documentModels/prebuilt-invoice/analyzeResults/op-1"
                                + "?api-version=2024-11-30")));
        server.stubFor(get(urlPathEqualTo("/documentintelligence/documentModels/prebuilt-invoice/analyzeResults/op-1"))
                .inScenario("poll").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(okJson("{\"status\":\"running\"}").withHeader("Retry-After", "0"))
                .willSetStateTo("done"));
        server.stubFor(get(urlPathEqualTo("/documentintelligence/documentModels/prebuilt-invoice/analyzeResults/op-1"))
                .inScenario("poll").whenScenarioStateIs("done").willReturn(okJson("""
                        {"status":"succeeded","analyzeResult":{"content":"ACME GmbH\\nInvoice INV-7\\nTotal 119.00",
                         "pages":[{"pageNumber":1,"width":1000,"height":2000,"unit":"pixel",
                           "words":[{"content":"ACME","polygon":[100,100,200,100,200,140,100,140],"confidence":0.99,"span":{"offset":0,"length":4}},
                                    {"content":"GmbH","polygon":[210,100,300,100,300,140,210,140],"confidence":0.97,"span":{"offset":5,"length":4}},
                                    {"content":"Total","polygon":[100,500,200,500,200,540,100,540],"confidence":0.9,"span":{"offset":26,"length":5}},
                                    {"content":"119.00","polygon":[210,500,320,500,320,540,210,540],"confidence":0.7,"span":{"offset":32,"length":6}}],
                           "lines":[{"content":"ACME GmbH","polygon":[100,100,300,100,300,140,100,140],"spans":[{"offset":0,"length":9}]},
                                    {"content":"Invoice INV-7","polygon":[100,300,400,300,400,340,100,340],"spans":[{"offset":10,"length":13}]},
                                    {"content":"Total 119.00","polygon":[100,500,320,500,320,540,100,540],"spans":[{"offset":26,"length":12}]}]}],
                         "documents":[{"docType":"invoice","fields":{
                           "InvoiceId":{"type":"string","valueString":"INV-7","confidence":0.96,
                             "boundingRegions":[{"pageNumber":1,"polygon":[300,300,400,300,400,340,300,340]}]},
                           "InvoiceTotal":{"type":"currency","valueCurrency":{"amount":119.0,"currencyCode":"EUR"},"confidence":0.93},
                           "Items":{"type":"array","valueArray":[
                             {"type":"object","valueObject":{"Amount":{"type":"currency","valueCurrency":{"amount":60}}}},
                             {"type":"object","valueObject":{"Amount":{"type":"currency","valueCurrency":{"amount":40}}}}]}}}]}}
                        """)));
        var azure = new AzureDocumentIntelligenceOcr("azure-eu", provider("azureDocumentIntelligence", ""),
                "azure-test-key-not-real", http);
        OcrDocument document = azure.analyze(PNG, "image/png", "invoice", Duration.ofSeconds(10));
        assertThat(document.model()).isEqualTo("prebuilt-invoice");
        assertThat(document.pages()).hasSize(1);
        OcrDocument.Line first = document.pages().get(0).lines().get(0);
        assertThat(first.text()).isEqualTo("ACME GmbH");
        assertThat(first.box()).isEqualTo(new OcrDocument.Box(0.1, 0.05, 0.2, 0.02));
        assertThat(first.confidence()).isCloseTo(0.98, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(first.words()).hasSize(2);
        assertThat(document.text()).isEqualTo("ACME GmbH\nInvoice INV-7\nTotal 119.00");
        assertThat(document.keyValues()).extracting(OcrDocument.KeyValue::field, OcrDocument.KeyValue::value)
                .contains(org.assertj.core.groups.Tuple.tuple("invoiceNumber", "INV-7"),
                        org.assertj.core.groups.Tuple.tuple("currency", "EUR"));
        assertThat(document.keyValues()).anySatisfy(kv -> {
            assertThat(kv.field()).isEqualTo("totalAmount");
            assertThat(new BigDecimal(kv.value())).isEqualByComparingTo("119");
        });
        assertThat(document.keyValues().get(0).box()).isEqualTo(new OcrDocument.Box(0.3, 0.15, 0.1, 0.02));
        assertThat(document.lineItemAmounts()).containsExactly(new BigDecimal("60"), new BigDecimal("40"));
        server.verify(2, getRequestedFor(urlPathEqualTo(
                "/documentintelligence/documentModels/prebuilt-invoice/analyzeResults/op-1")));
        assertThat(AzureDocumentIntelligenceOcr.model("idDocument")).isEqualTo("prebuilt-idDocument");
        assertThat(AzureDocumentIntelligenceOcr.model("bankStatement")).isEqualTo("prebuilt-read");
    }

    @Test
    void azureRefusesForeignOperationAddressesAndClassifiesFailuresWithoutBodies() {
        server.stubFor(post(urlPathEqualTo("/documentintelligence/documentModels/prebuilt-read:analyze"))
                .willReturn(aResponse().withStatus(202).withHeader("Operation-Location",
                        "https://attacker.example/steal")));
        var azure = new AzureDocumentIntelligenceOcr("azure-eu", provider("azureDocumentIntelligence", ""), "k", http);
        assertThatThrownBy(() -> azure.analyze(PNG, "image/png", "custom", Duration.ofSeconds(5)))
                .isInstanceOf(OcrException.class).hasMessageContaining("operation on another host");
        server.stubFor(post(urlPathEqualTo("/documentintelligence/documentModels/prebuilt-idDocument:analyze"))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":{\"message\":\"secret detail 4111\"}}")));
        assertThatThrownBy(() -> azure.analyze(PNG, "image/png", "idDocument", Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(OcrException.class, e -> {
                    assertThat(e.kind()).isEqualTo(OcrException.Kind.CREDENTIALS_REJECTED);
                    assertThat(e.getMessage()).doesNotContain("secret detail", "4111");
                });
        server.stubFor(post(urlPathEqualTo("/documentintelligence/documentModels/prebuilt-invoice:analyze"))
                .willReturn(aResponse().withStatus(503).withBody("overloaded")));
        assertThatThrownBy(() -> azure.analyze(PNG, "image/png", "invoice", Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(OcrException.class, e -> assertThat(e.kind())
                        .isEqualTo(OcrException.Kind.UNAVAILABLE));
        // Unsupported types never leave; hosts outside the egress allowlist are never called.
        assertThatThrownBy(() -> azure.analyze("x".getBytes(StandardCharsets.UTF_8), "application/zip", "invoice",
                Duration.ofSeconds(5))).isInstanceOfSatisfying(OcrException.class, e -> assertThat(e.kind())
                        .isEqualTo(OcrException.Kind.UNSUPPORTED));
        var outside = new AzureDocumentIntelligenceOcr("azure-us", new OcrProperties.Provider("azureDocumentIntelligence",
                URI.create("https://ocr.not-allowed.example"), "ref", "", null, List.of(), null, null, null), "k", http);
        assertThatThrownBy(() -> outside.analyze(PNG, "image/png", "invoice", Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(OcrException.class, e -> assertThat(e.kind())
                        .isEqualTo(OcrException.Kind.EGRESS_DENIED));
    }

    @Test
    void textractSignsWithSigV4AndParsesExpenseIdentityAndFormAnswers() throws Exception {
        server.stubFor(post(urlEqualTo("/")).withHeader("X-Amz-Target", equalTo("Textract.AnalyzeExpense"))
                .willReturn(okJson("""
                        {"ExpenseDocuments":[{"SummaryFields":[
                          {"Type":{"Text":"INVOICE_RECEIPT_ID"},"ValueDetection":{"Text":"INV-9","Confidence":97.5,
                            "Geometry":{"BoundingBox":{"Left":0.5,"Top":0.1,"Width":0.2,"Height":0.03}}},"PageNumber":1},
                          {"Type":{"Text":"TOTAL"},"ValueDetection":{"Text":"€1.234,50","Confidence":88},
                           "Currency":{"Code":"EUR","Confidence":90},"PageNumber":1}],
                         "LineItemGroups":[{"LineItems":[{"LineItemExpenseFields":[
                           {"Type":{"Text":"PRICE"},"ValueDetection":{"Text":"1.000,00"}}]},
                          {"LineItemExpenseFields":[{"Type":{"Text":"PRICE"},"ValueDetection":{"Text":"234,50"}}]}]}],
                         "Blocks":[{"BlockType":"PAGE","Id":"p1","Page":1},
                           {"BlockType":"LINE","Id":"l1","Page":1,"Text":"Invoice INV-9","Confidence":99.1,
                            "Geometry":{"BoundingBox":{"Left":0.1,"Top":0.1,"Width":0.6,"Height":0.03}},
                            "Relationships":[{"Type":"CHILD","Ids":["w1","w2"]}]},
                           {"BlockType":"WORD","Id":"w1","Page":1,"Text":"Invoice","Confidence":99.5},
                           {"BlockType":"WORD","Id":"w2","Page":1,"Text":"INV-9","Confidence":98.7}]}]}
                        """)));
        var credentials = new SigV4.Credentials("AKIDTESTONLY", "test-secret-not-real", "session-token-test");
        var textract = new TextractOcr("textract-eu", provider("textract", "eu-central-1"), credentials, http);
        OcrDocument expense = textract.analyze(PNG, "image/png", "invoice", Duration.ofSeconds(5));
        assertThat(expense.model()).isEqualTo("AnalyzeExpense");
        assertThat(expense.pages().get(0).lines()).singleElement().satisfies(l -> {
            assertThat(l.text()).isEqualTo("Invoice INV-9");
            assertThat(l.confidence()).isCloseTo(0.991, org.assertj.core.api.Assertions.within(1e-9));
            assertThat(l.words()).extracting(OcrDocument.Word::text).containsExactly("Invoice", "INV-9");
        });
        assertThat(expense.keyValues()).extracting(OcrDocument.KeyValue::field).contains("invoiceNumber",
                "totalAmount", "currency");
        assertThat(expense.lineItemAmounts()).containsExactly(new BigDecimal("1000.00"), new BigDecimal("234.50"));

        LoggedRequest sent = server.findAll(postRequestedFor(urlEqualTo("/"))).get(0);
        assertThat(sent.getHeader("Content-Type")).isEqualTo("application/x-amz-json-1.1");
        assertThat(sent.getHeader("X-Amz-Security-Token")).isEqualTo("session-token-test");
        assertThat(json.parse(sent.getBodyAsString()).path("Document").path("Bytes").asString())
                .isEqualTo(Base64.getEncoder().encodeToString(PNG));
        String amzDate = sent.getHeader("X-Amz-Date");
        Map<String, String> signed = new TreeMap<>();
        signed.put("content-type", "application/x-amz-json-1.1");
        signed.put("host", "localhost:" + server.port());
        signed.put("x-amz-date", amzDate);
        signed.put("x-amz-target", "Textract.AnalyzeExpense");
        signed.put("x-amz-security-token", "session-token-test");
        Instant at = java.time.LocalDateTime.parse(amzDate, java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(java.time.ZoneOffset.UTC);
        String expected = SigV4.authorization(credentials, "eu-central-1", "textract", "POST", "/", "", signed,
                sent.getBody(), at);
        assertThat(sent.getHeader("Authorization")).isEqualTo(expected)
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIDTESTONLY/" + amzDate.substring(0, 8)
                        + "/eu-central-1/textract/aws4_request, SignedHeaders=content-type;host;x-amz-date;"
                        + "x-amz-security-token;x-amz-target, Signature=");

        server.stubFor(post(urlEqualTo("/")).withHeader("X-Amz-Target", equalTo("Textract.AnalyzeID"))
                .willReturn(okJson("""
                        {"IdentityDocuments":[{"IdentityDocumentFields":[
                          {"Type":{"Text":"FIRST_NAME"},"ValueDetection":{"Text":"ERIKA","Confidence":98}},
                          {"Type":{"Text":"DATE_OF_BIRTH"},"ValueDetection":{"Text":"12.08.1964",
                            "NormalizedValue":{"Value":"1964-08-12T00:00:00","ValueType":"Date"},"Confidence":95}},
                          {"Type":{"Text":"ID_TYPE"},"ValueDetection":{"Text":"PASSPORT","Confidence":99}}],"Blocks":[]}]}
                        """)));
        OcrDocument id = textract.analyze(PNG, "image/jpeg", "idDocument", Duration.ofSeconds(5));
        assertThat(id.keyValues()).extracting(OcrDocument.KeyValue::field, OcrDocument.KeyValue::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("givenNames", "ERIKA"),
                        org.assertj.core.groups.Tuple.tuple("dateOfBirth", "1964-08-12"),
                        org.assertj.core.groups.Tuple.tuple("documentKind", "passport"));
        assertThat(json.parse(server.findAll(postRequestedFor(urlEqualTo("/"))).get(1).getBodyAsString())
                .path("DocumentPages").get(0).has("Bytes")).isTrue();

        server.stubFor(post(urlEqualTo("/")).withHeader("X-Amz-Target", equalTo("Textract.AnalyzeDocument"))
                .willReturn(okJson("""
                        {"Blocks":[{"BlockType":"PAGE","Id":"p1","Page":1},
                          {"BlockType":"KEY_VALUE_SET","Id":"k1","Page":1,"EntityTypes":["KEY"],"Confidence":91,
                           "Relationships":[{"Type":"CHILD","Ids":["kw"]},{"Type":"VALUE","Ids":["v1"]}]},
                          {"BlockType":"KEY_VALUE_SET","Id":"v1","Page":1,"EntityTypes":["VALUE"],"Confidence":89,
                           "Geometry":{"BoundingBox":{"Left":0.4,"Top":0.2,"Width":0.3,"Height":0.02}},
                           "Relationships":[{"Type":"CHILD","Ids":["vw"]}]},
                          {"BlockType":"WORD","Id":"kw","Page":1,"Text":"IBAN:"},
                          {"BlockType":"WORD","Id":"vw","Page":1,"Text":"DE89370400440532013000"}]}
                        """)));
        OcrDocument form = textract.analyze(PNG, "application/pdf", "bankStatement", Duration.ofSeconds(5));
        assertThat(form.keyValues()).singleElement().satisfies(kv -> {
            assertThat(kv.field()).isEqualTo("iban");
            assertThat(kv.value()).isEqualTo("DE89370400440532013000");
            assertThat(kv.confidence()).isEqualTo(0.89);
            assertThat(kv.box()).isEqualTo(new OcrDocument.Box(0.4, 0.2, 0.3, 0.02));
        });
        assertThat(json.parse(server.findAll(postRequestedFor(urlEqualTo("/"))).get(2).getBodyAsString())
                .path("FeatureTypes").get(0).asString()).isEqualTo("FORMS");

        server.stubFor(post(urlEqualTo("/")).withHeader("X-Amz-Target", equalTo("Textract.AnalyzeDocument"))
                .willReturn(aResponse().withStatus(400).withBody("{\"__type\":\"InvalidParameterException\",\"Message\":\"secret 4111\"}")));
        assertThatThrownBy(() -> textract.analyze(PNG, "image/png", "custom", Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(OcrException.class, e -> {
                    assertThat(e.kind()).isEqualTo(OcrException.Kind.REJECTED);
                    assertThat(e.getMessage()).doesNotContain("secret", "4111");
                });
        assertThatThrownBy(() -> textract.analyze(new byte[TextractOcr.MAX_BYTES + 1], "image/png", "custom",
                Duration.ofSeconds(5))).isInstanceOfSatisfying(OcrException.class, e -> assertThat(e.kind())
                        .isEqualTo(OcrException.Kind.TOO_LARGE));
    }

    static final String TSV = String.join("\n",
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext",
            "1\t1\t0\t0\t0\t0\t0\t0\t1000\t2000\t-1\t",
            "2\t1\t1\t0\t0\t0\t100\t100\t500\t100\t-1\t",
            "4\t1\t1\t1\t1\t0\t100\t100\t300\t40\t-1\t",
            "5\t1\t1\t1\t1\t1\t100\t100\t100\t40\t96.5\tInvoice",
            "5\t1\t1\t1\t1\t2\t210\t100\t190\t40\t91.5\tINV-2026-001",
            "4\t1\t1\t1\t2\t0\t100\t500\t300\t40\t-1\t",
            "5\t1\t1\t1\t2\t1\t100\t500\t100\t40\t88\tTotal:",
            "5\t1\t1\t1\t2\t2\t220\t500\t100\t40\t42\t119.00",
            "5\t1\t1\t1\t2\t3\t330\t500\t10\t40\t-1\t ");

    @Test
    void tesseractTsvBecomesLinesWithBoxesAndConfidences() {
        OcrDocument document = TesseractOcr.parseTsv("local", TSV);
        assertThat(document.pages()).singleElement().satisfies(p -> {
            assertThat(p.width()).isEqualTo(1000);
            assertThat(p.height()).isEqualTo(2000);
            assertThat(p.lines()).hasSize(2);
            OcrDocument.Line total = p.lines().get(1);
            assertThat(total.text()).isEqualTo("Total: 119.00");
            assertThat(total.confidence()).isCloseTo(0.65, org.assertj.core.api.Assertions.within(1e-9));
            assertThat(total.box()).isEqualTo(new OcrDocument.Box(0.1, 0.25, 0.22, 0.02));
            assertThat(total.words()).extracting(OcrDocument.Word::confidence).containsExactly(0.88, 0.42);
        });
        assertThat(document.text()).isEqualTo("Invoice INV-2026-001\nTotal: 119.00");
        assertThat(document.lines().get(1).start()).isEqualTo(21);
        assertThatThrownBy(() -> TesseractOcr.parseTsv("local", "not tsv")).isInstanceOf(OcrException.class);
        assertThatThrownBy(() -> TesseractOcr.parseTsv("local", TSV + "\n5\t1\tx\t1\t1\t1\t1\t1\t1\t1\t9\tword"))
                .isInstanceOf(OcrException.class);
    }

    @Test
    void theLocalEngineRunsTheConfiguredBinaryInABoundedProcessAndCleansUp(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("sample.tsv"), TSV, StandardCharsets.UTF_8);
        boolean windows = System.getProperty("os.name").toLowerCase().contains("windows");
        Path fake;
        if (windows) {
            fake = dir.resolve("fake-tesseract.cmd");
            Files.writeString(fake, "@copy /Y \"%~dp0sample.tsv\" \"%~2.tsv\" >NUL\r\n", StandardCharsets.US_ASCII);
        } else {
            fake = dir.resolve("fake-tesseract.sh");
            Files.writeString(fake, "#!/bin/sh\ncp \"$(dirname \"$0\")/sample.tsv\" \"$2.tsv\"\n",
                    StandardCharsets.US_ASCII);
            fake.toFile().setExecutable(true);
        }
        TesseractOcr engine = new TesseractOcr(new OcrProperties.Tesseract(fake.toString(), "eng+deu",
                Duration.ofSeconds(20), null));
        assertThat(engine.command(Path.of("in.img"), Path.of("out"))).containsExactly(fake.toString(), "in.img", "out",
                "-l", "eng+deu", "tsv");
        long before = tempDirectories();
        OcrDocument document = engine.read("local", PNG, Duration.ofSeconds(20));
        assertThat(document.text()).isEqualTo("Invoice INV-2026-001\nTotal: 119.00");
        assertThat(tempDirectories()).isLessThanOrEqualTo(before);
        // A failing engine is classified, and without an engine images are unsupported.
        Path failing = dir.resolve(windows ? "failing.cmd" : "failing.sh");
        Files.writeString(failing, windows ? "@exit /b 3\r\n" : "#!/bin/sh\nexit 3\n", StandardCharsets.US_ASCII);
        failing.toFile().setExecutable(true);
        assertThatThrownBy(() -> new TesseractOcr(new OcrProperties.Tesseract(failing.toString(), null, null, null))
                .read("local", PNG, Duration.ofSeconds(20))).isInstanceOfSatisfying(OcrException.class,
                        e -> assertThat(e.kind()).isEqualTo(OcrException.Kind.REJECTED));
        assertThatThrownBy(() -> new TesseractOcr(new OcrProperties.Tesseract(null, null, null, null))
                .read("local", PNG, Duration.ofSeconds(5))).isInstanceOfSatisfying(OcrException.class,
                        e -> assertThat(e.kind()).isEqualTo(OcrException.Kind.UNSUPPORTED));
    }

    private static long tempDirectories() throws Exception {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.getFileName().toString().startsWith("orqix-ocr-")).count();
        }
    }
}
