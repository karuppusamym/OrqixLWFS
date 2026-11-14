package com.orqix.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * docs/build/S140: time and date-time fields, unique lists with the checklist and tag widgets (CG-F01), lookup fields
 * (CG-F02) and guest response forms on message waits (CG-F05), through the public compiler and form validator.
 */
class LookupGuestFieldTypesTest {

    private static final FormValidator VALIDATOR = Compilers.formValidator();

    private static CompileContext context() {
        CompileContext base = TestSupport.defaultContext();
        List<CompileContext.ConnectionVersion> versions = new ArrayList<>(base.connectionVersions());
        String users = TestSupport.read("examples/connections/servicenow-users.connection.json");
        versions.add(new CompileContext.ConnectionVersion("servicenow-users", 1, users, CanonicalJson.sha256Hex(users)));
        var enabled = new HashSet<>(base.enabledConnectionBindings());
        enabled.add("servicenow-users");
        return new CompileContext(base.selfApprovalAllowed(), base.groups(), versions, enabled);
    }

    private static List<String> issues(ObjectNode d) {
        return Compilers.workflowCompiler().validate(d.toString(), context()).issues().stream()
                .map(i -> (i.severity() == Severity.BLOCKER ? "B " : "W ") + i.code() + " " + i.path()).toList();
    }

    private static List<String> blockers(ObjectNode d) {
        return issues(d).stream().filter(i -> i.startsWith("B ")).toList();
    }

    private static ObjectNode withField(String key, String json) {
        ObjectNode d = TestSupport.purchase();
        ((ObjectNode) d.path("inputSchema").path("properties")).set(key, StrictJson.parse(json));
        return d;
    }

    private static List<String> errors(String schemaField, String value) {
        String schema = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"f\":" + schemaField + "}}";
        return VALIDATOR.validate(schema, "{\"f\":" + value + "}").stream().map(e -> e.code() + " " + e.pointer())
                .toList();
    }

    // ---------------------------------------------------------------- CG-F01

    @Test
    void timesOfDayAreHhMmWithinTheirBounds() {
        String field = "{\"type\":\"string\",\"title\":\"Start\",\"format\":\"time\",\"formatMinimum\":\"08:00\","
                + "\"formatMaximum\":\"17:30\"}";
        assertThat(errors(field, "\"09:15\"")).isEmpty();
        assertThat(errors(field, "\"09:15:30\"")).isEmpty();
        assertThat(errors(field, "\"9:15\"")).containsExactly("format /f");
        assertThat(errors(field, "\"24:00\"")).containsExactly("format /f");
        assertThat(errors(field, "\"07:59\"")).containsExactly("formatMinimum /f");
        assertThat(errors(field, "\"17:31\"")).containsExactly("formatMaximum /f");
    }

    @Test
    void dateTimesNeedAnOffsetAndBoundsCompareInstants() {
        String field = "{\"type\":\"string\",\"title\":\"Delivery\",\"format\":\"date-time\","
                + "\"formatMinimum\":\"2026-11-06T09:00:00Z\",\"timeZone\":\"Europe/Berlin\"}";
        assertThat(errors(field, "\"2026-11-06T10:00:00+01:00\"")).isEmpty();
        assertThat(errors(field, "\"2026-11-06T10:30Z\"")).isEmpty();
        assertThat(errors(field, "\"2026-11-06T09:59:00+01:00\"")).containsExactly("formatMinimum /f");
        assertThat(errors(field, "\"2026-11-06T10:00:00\"")).containsExactly("format /f");
        assertThat(errors(field, "\"2026-02-30T10:00:00Z\"")).containsExactly("format /f");
        assertThat(errors(field, "\"2026-11-06\"")).containsExactly("format /f");
    }

    @Test
    void checklistsAndTagListsHoldEachValueOnce() {
        String checklist = "{\"type\":\"array\",\"title\":\"Checks\",\"minItems\":1,\"maxItems\":3,\"uniqueItems\":true,"
                + "\"items\":{\"type\":\"string\",\"title\":\"Check\",\"enum\":[\"id\",\"address\",\"bank\"]}}";
        assertThat(errors(checklist, "[\"id\",\"bank\"]")).isEmpty();
        assertThat(errors(checklist, "[\"id\",\"id\"]")).containsExactly("uniqueItems /f/1");
        assertThat(errors(checklist, "[\"id\",\"other\"]")).containsExactly("enum /f/1");
        assertThat(errors(checklist, "[]")).containsExactly("minItems /f");
    }

    @Test
    void publishesNewTypesAndWidgetsAndRefusesWrongBoundsZonesAndConditions() throws Exception {
        ObjectNode d = withField("startAt", "{\"type\":\"string\",\"title\":\"Start\",\"format\":\"time\"}");
        ((ObjectNode) d.path("inputSchema").path("properties")).set("deliverAt", StrictJson.parse(
                "{\"type\":\"string\",\"title\":\"Delivery\",\"format\":\"date-time\",\"timeZone\":\"America/New_York\"}"));
        ((ObjectNode) d.path("inputSchema").path("properties")).set("checks", StrictJson.parse(
                "{\"type\":\"array\",\"title\":\"Checks\",\"minItems\":1,\"maxItems\":3,\"uniqueItems\":true,"
                        + "\"items\":{\"type\":\"string\",\"title\":\"Check\",\"enum\":[\"id\",\"bank\"]}}"));
        ((ObjectNode) d.path("inputSchema").path("properties")).set("tags", StrictJson.parse(
                "{\"type\":\"array\",\"title\":\"Tags\",\"minItems\":1,\"maxItems\":10,\"uniqueItems\":true,"
                        + "\"items\":{\"type\":\"string\",\"title\":\"Tag\",\"maxLength\":30}}"));
        ObjectNode widgets = (ObjectNode) d.path("inputUi").path("widgets");
        widgets.put("checks", "checklist").put("tags", "tags");
        assertThat(issues(d)).noneMatch(i -> i.contains("/inputSchema") || i.contains("WF_UI_WIDGET_MISMATCH"));
        assertThat(Compilers.workflowCompiler().compile(d.toString(), context()).bpmnXml()).isNotBlank();

        widgets.put("checks", "tags").put("tags", "checklist");
        assertThat(issues(d)).contains("W WF_UI_WIDGET_MISMATCH /inputUi/widgets/checks",
                "W WF_UI_WIDGET_MISMATCH /inputUi/widgets/tags");

        assertThat(blockers(withField("t", "{\"type\":\"string\",\"title\":\"T\",\"format\":\"time\",\"formatMinimum\":\"2026-01-01\"}")))
                .contains("B WF_FORM_INVALID /inputSchema/properties/t/formatMinimum");
        assertThat(blockers(withField("t", "{\"type\":\"string\",\"title\":\"T\",\"format\":\"time\",\"formatMinimum\":\"18:00\",\"formatMaximum\":\"08:00\"}")))
                .contains("B WF_FORM_INVALID /inputSchema/properties/t/formatMinimum");
        assertThat(blockers(withField("t", "{\"type\":\"string\",\"title\":\"T\",\"format\":\"date-time\",\"timeZone\":\"Mars/Olympus\"}")))
                .contains("B WF_FORM_INVALID /inputSchema/properties/t/timeZone");
        assertThat(blockers(withField("t", "{\"type\":\"string\",\"title\":\"T\",\"format\":\"time\",\"timeZone\":\"Europe/Berlin\"}")))
                .contains("B WF_FORM_INVALID /inputSchema/properties/t/timeZone");
        assertThat(blockers(withField("rows", "{\"type\":\"array\",\"title\":\"Rows\",\"minItems\":1,\"maxItems\":2,\"uniqueItems\":true,"
                + "\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"a\":{\"type\":\"string\",\"title\":\"A\"}}}}")))
                .anyMatch(i -> i.contains("/inputSchema/properties/rows"));

        // A time cannot choose a path (yet): the comparison would need a time zone rule.
        ObjectNode routed = withField("startAt", "{\"type\":\"string\",\"title\":\"Start\",\"format\":\"time\"}");
        ((ArrayNode) routed.path("inputSchema").path("required")).add("startAt");
        for (var t : routed.path("transitions")) {
            if (t.has("condition")) {
                ((ObjectNode) t).set("condition", StrictJson.parse(
                        "{\"fieldRef\":\"/input/startAt\",\"operator\":\"eq\",\"value\":\"09:00\"}"));
                break;
            }
        }
        assertThat(blockers(routed)).anyMatch(i -> i.startsWith("B WF_OPERATOR_TYPE"));
    }

    // ---------------------------------------------------------------- CG-F02

    private static ObjectNode lookup(String lookupJson) {
        ObjectNode d = withField("buyer", "{\"type\":\"string\",\"title\":\"Buyer\",\"maxLength\":254,\"lookup\":" + lookupJson + "}");
        ((ArrayNode) d.path("dependencies").path("connections")).add(StrictJson.parse(
                "{\"key\":\"servicenow-users\",\"definitionVersion\":1}"));
        return d;
    }

    private static final String LOOKUP = "{\"connection\":\"servicenow-users\",\"operation\":\"searchUsers\","
            + "\"searchInput\":\"text\",\"rows\":\"/result/[*]\",\"valueColumn\":\"email\",\"labelColumn\":\"name\"}";

    @Test
    void aLookupNeedsAPinnedReadOnlyOperationWithTextInputAndTextColumns() {
        ObjectNode ok = lookup(LOOKUP);
        assertThat(blockers(ok)).isEmpty();
        assertThat(issues(ok)).noneMatch(i -> i.contains("WF_DEPENDENCY_UNUSED") && i.contains("/1"));

        assertThat(blockers(lookup(LOOKUP.replace("\"text\"", "\"query\""))))
                .contains("B WF_LOOKUP_INVALID /inputSchema/properties/buyer/lookup/searchInput");
        assertThat(blockers(lookup(LOOKUP.replace("\"email\"", "\"phone\""))))
                .contains("B WF_LOOKUP_INVALID /inputSchema/properties/buyer/lookup/valueColumn");
        assertThat(blockers(lookup(LOOKUP.replace("/result/[*]", "/result/0/email"))))
                .contains("B WF_LOOKUP_INVALID /inputSchema/properties/buyer/lookup/rows");
        assertThat(blockers(lookup(LOOKUP.replace("searchUsers", "deleteUsers"))))
                .contains("B WF_LOOKUP_INVALID /inputSchema/properties/buyer/lookup/operation");

        ObjectNode undeclared = withField("buyer", "{\"type\":\"string\",\"title\":\"Buyer\",\"lookup\":" + LOOKUP + "}");
        assertThat(blockers(undeclared)).contains("B WF_CONNECTION_UNKNOWN /inputSchema/properties/buyer/lookup/connection");

        ObjectNode choices = lookup(LOOKUP);
        ((ObjectNode) choices.path("inputSchema").path("properties").path("buyer")).set("enum",
                StrictJson.parse("[\"a\",\"b\"]"));
        assertThat(blockers(choices)).contains("B WF_LOOKUP_INVALID /inputSchema/properties/buyer/lookup");

        // A write operation of the purchase connection may not serve a lookup.
        ObjectNode write = withField("buyer", "{\"type\":\"string\",\"title\":\"Buyer\",\"lookup\":"
                + LOOKUP.replace("servicenow-users", "procurement").replace("searchUsers", "createOrder") + "}");
        assertThat(blockers(write)).anyMatch(i -> i.startsWith("B WF_LOOKUP_INVALID"));
    }

    // ---------------------------------------------------------------- CG-F05

    private static ObjectNode guestWait(String guestJson) {
        ObjectNode d = (ObjectNode) TestSupport.json("examples/order-payment-wait.json");
        ((ObjectNode) d.path("nodes").get(1).path("config")).set("guestResponse", StrictJson.parse(guestJson));
        ((ObjectNode) d.path("inputSchema").path("properties")).set("vendorEmail", StrictJson.parse(
                "{\"type\":\"string\",\"title\":\"Vendor email\",\"maxLength\":254}"));
        return d;
    }

    @Test
    void guestFormsHaveABoundedLinkPlainTextAndATextRecipientAndRoundTripThroughBpmn() throws Exception {
        String guest = "{\"linkValidFor\":\"P3D\",\"title\":\"Confirm the payment\",\"instructions\":\"Attach the advice.\","
                + "\"recipientFrom\":\"/input/vendorEmail\",\"attachments\":{\"maxFiles\":2,\"acceptTypes\":[\"pdf\"]}}";
        ObjectNode ok = guestWait(guest);
        assertThat(blockers(ok)).isEmpty();
        var compiled = Compilers.workflowCompiler().compile(ok.toString(), context());
        assertThat(compiled.bpmnXml()).contains("guestResponseBase64=");
        BpmnSource.Check read = BpmnSource.check(compiled.bpmnXml(), ok.toString(), Compilers.workflowCompiler(),
                json -> context());
        assertThat(read.problems()).isEmpty();
        assertThat(read.definitionJson()).contains("\"guestResponse\"", "Confirm the payment");

        assertThat(blockers(guestWait(guest.replace("P3D", "PT30M"))))
                .contains("B WF_GUEST_LINK_VALIDITY /nodes/1/config/guestResponse/linkValidFor");
        assertThat(blockers(guestWait(guest.replace("P3D", "P31D"))))
                .contains("B WF_GUEST_LINK_VALIDITY /nodes/1/config/guestResponse/linkValidFor");
        assertThat(blockers(guestWait(guest.replace("Attach the advice.", "<b>Attach</b>"))))
                .contains("B WF_GUEST_FORM_INVALID /nodes/1/config/guestResponse/instructions");
        assertThat(blockers(guestWait(guest.replace("/input/vendorEmail", "/input/unknownField"))))
                .contains("B WF_GUEST_RECIPIENT /nodes/1/config/guestResponse/recipientFrom");
        assertThat(blockers(guestWait(guest.replace("/input/vendorEmail", "/steps/waitPayment/amountPaid"))))
                .contains("B WF_GUEST_RECIPIENT /nodes/1/config/guestResponse/recipientFrom");
    }

    @Test
    void aWaitWithoutTheOptionIsUnchanged() throws Exception {
        ObjectNode d = (ObjectNode) TestSupport.json("examples/order-payment-wait.json");
        assertThat(Compilers.workflowCompiler().compile(d.toString(), context()).bpmnXml())
                .doesNotContain("guestResponseBase64");
    }
}
