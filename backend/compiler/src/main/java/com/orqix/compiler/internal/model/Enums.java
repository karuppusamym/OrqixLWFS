package com.orqix.compiler.internal.model;

import java.util.Locale;

/** Small closed vocabularies of the canonical model. */
public final class Enums {

    private Enums() {
    }

    /** Node kinds with their business labels (S4 section 2). */
    public enum NodeKind {
        START("start", "Request form"),
        APPROVAL("approval", "Ask for approval"),
        DECISION("decision", "Make a decision"),
        BRANCH("branch", "Choose a path"),
        HTTP("http", "Call a system"),
        GENERATE_DOCUMENT("generateDocument", "Generate a document"),
        NOTIFICATION("notification", "Notify someone"),
        PARALLEL_SPLIT("parallelSplit", "Run in parallel"),
        PARALLEL_JOIN("parallelJoin", "Wait for all"),
        WAIT("wait", "Wait"),
        START_WORKFLOW("startWorkflow", "Start another workflow"),
        WAIT_FOR_MESSAGE("waitForMessage", "Wait for a message"),
        EXTERNAL_TASK("externalTask", "Work by an external worker"),
        /** Raise a business error caught by a section's error route or an error event subprocess (S59). */
        RAISE_ERROR("raiseError", "Raise an error"),
        /** Send a signal within the same request (S59). */
        SEND_SIGNAL("sendSignal", "Send a signal"),
        /** Wait until a signal is sent in the same request (S59). */
        WAIT_FOR_SIGNAL("waitForSignal", "Wait for a signal"),
        /** Run the automatic undo calls of the calls that completed (S59). */
        COMPENSATE("compensate", "Undo completed calls"),
        /** Send through a configured adapter (email, Teams, Kafka produce or database read; S73). */
        SEND_ADAPTER("sendAdapter", "Send through an adapter"),
        END("end", "Finish");

        private final String json;
        private final String businessLabel;

        NodeKind(String json, String businessLabel) {
            this.json = json;
            this.businessLabel = businessLabel;
        }

        public String json() {
            return json;
        }

        public String businessLabel() {
            return businessLabel;
        }

        /** @return the kind, or null when the text is not a known kind */
        public static NodeKind fromJson(String text) {
            for (NodeKind k : values()) {
                if (k.json.equals(text)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** Transition {@code on} values. */
    public enum Route {
        NEXT("next"), APPROVED("approved"), REJECTED("rejected"), TIMED_OUT("timedOut"), FAILED("failed"), CONDITION("condition"),
        DEFAULT("default");

        private final String json;

        Route(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }

        public static Route fromJson(String text) {
            for (Route r : values()) {
                if (r.json.equals(text)) {
                    return r;
                }
            }
            throw new IllegalArgumentException("Unknown route " + text);
        }
    }

    /**
     * Comparison operators. {@code IN} (a list of values) and {@code BETWEEN} (a number range) are set operators and
     * are accepted in decision table rows only (docs/build/S121).
     */
    public enum Operator {
        EQ("is"), NE("is not"), GTE("is at least"), GT("is greater than"), LT("is less than"), LTE("is at most"),
        IN("is one of"), BETWEEN("is between");

        /** @return true for the decision-only operators whose value is a list or a range */
        public boolean set() {
            return this == IN || this == BETWEEN;
        }

        private final String phrase;

        Operator(String phrase) {
            this.phrase = phrase;
        }

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** @return business wording, e.g. "is at least" */
        public String phrase() {
            return phrase;
        }

        /** @return the operator, or null when unknown */
        public static Operator fromJson(String text) {
            if (text == null) {
                return null;
            }
            for (Operator o : values()) {
                if (o.json().equals(text)) {
                    return o;
                }
            }
            return null;
        }
    }

    /** Scalar field types of forms, request/response schemas and step outputs. */
    public enum FieldKind {
        STRING("text"), NUMBER("a number"), INTEGER("a whole number"), BOOLEAN("true or false"), ARRAY("a list"),
        /** A calendar date: JSON {@code type: string, format: date}, written YYYY-MM-DD (S12 section 10). */
        DATE("a date"),
        /** A file: JSON {@code type: string, format: attachment}; the value is the ID of an uploaded file (S17). */
        ATTACHMENT("a file"),
        /** One row of a list of rows (S35); only as the item type of an array. */
        OBJECT("a row");

        private final String description;

        FieldKind(String description) {
            this.description = description;
        }

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** @return business wording such as "a number" */
        public String description() {
            return description;
        }

        public boolean numeric() {
            return this == NUMBER || this == INTEGER;
        }

        /** @return DATE for a string with {@code format: date}, otherwise as {@link #fromJson(String)} */
        public static FieldKind fromJson(String type, String format) {
            if ("string".equals(type) && "date".equals(format)) {
                return DATE;
            }
            return "string".equals(type) && "attachment".equals(format) ? ATTACHMENT : fromJson(type);
        }

        /** @return the kind, or null when the text is not a scalar type */
        public static FieldKind fromJson(String text) {
            for (FieldKind k : values()) {
                if (k.json().equals(text)) {
                    return k;
                }
            }
            return null;
        }
    }
}
