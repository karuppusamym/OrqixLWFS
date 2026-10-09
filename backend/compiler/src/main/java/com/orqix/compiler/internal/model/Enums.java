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
        NOTIFICATION("notification", "Notify someone"),
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
        NEXT, APPROVED, REJECTED, CONDITION, DEFAULT;

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Route fromJson(String text) {
            return valueOf(text.toUpperCase(Locale.ROOT));
        }
    }

    /** Comparison operators. */
    public enum Operator {
        EQ("is"), GTE("is at least"), LT("is less than");

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
        STRING("text"), NUMBER("a number"), INTEGER("a whole number"), BOOLEAN("true or false");

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
