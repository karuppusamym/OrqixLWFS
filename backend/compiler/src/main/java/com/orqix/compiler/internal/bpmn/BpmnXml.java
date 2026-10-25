package com.orqix.compiler.internal.bpmn;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Hardened reading of BPMN text supplied by a person (UI03 BPMN source, docs/build/S60). Same protections as the
 * Camunda importer's {@code SafeXml} (S11 section 1), which lives in the application's migration module and cannot be
 * used from the compiler: size, element-count and depth limits, DTDs and external entities disabled and any DOCTYPE or
 * ENTITY declaration refused. Unlike the importer, comments and processing instructions are <em>reported</em> (the
 * workflow document cannot keep them), never skipped silently.
 */
public final class BpmnXml {

    /** At most 1 MiB of text (the canonical document itself is limited to 256 KiB). */
    public static final int MAX_CHARS = 1_048_576;
    public static final int MAX_ELEMENTS = 10_000;
    public static final int MAX_DEPTH = 32;

    /** The text cannot be read; {@link #getMessage()} says why (with line and column when known). */
    public static final class Unreadable extends RuntimeException {
        public Unreadable(String message) {
            super(message, null, false, false);
        }
    }

    /** One element: namespace URI, local name, attributes keyed {@code local} or {@code {ns}local}, children, text. */
    public static final class El {
        private final El parent;
        private final String ns;
        private final String local;
        private final int line;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final List<El> children = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        El(El parent, String ns, String local, int line) {
            this.parent = parent;
            this.ns = ns == null ? "" : ns;
            this.local = local;
            this.line = line;
        }

        public El parent() {
            return parent;
        }

        public String ns() {
            return ns;
        }

        public String local() {
            return local;
        }

        public int line() {
            return line;
        }

        public Map<String, String> attributes() {
            return attributes;
        }

        public List<El> children() {
            return children;
        }

        public String text() {
            return text.toString().trim();
        }

        public boolean is(String namespace, String name) {
            return ns.equals(namespace) && local.equals(name);
        }

        public String attr(String name) {
            return attributes.get(name);
        }

        public String attr(String namespace, String name) {
            return attributes.get("{" + namespace + "}" + name);
        }

        public El child(String namespace, String name) {
            for (El c : children) {
                if (c.is(namespace, name)) {
                    return c;
                }
            }
            return null;
        }

        public List<El> all(String namespace, String name) {
            List<El> found = new ArrayList<>();
            for (El c : children) {
                if (c.is(namespace, name)) {
                    found.add(c);
                }
            }
            return found;
        }

        /** Short name with the usual prefix ({@code bpmn:userTask}, {@code orqix:approval}). */
        public String label() {
            return BpmnXml.label(ns, local);
        }
    }

    /** Parsed document plus what XML carried that a tree of elements cannot: comments and processing instructions. */
    public record Parsed(El root, List<String> ignorable) {
    }

    private BpmnXml() {
    }

    public static Parsed parse(String content) {
        if (content == null || content.isBlank()) {
            throw new Unreadable("The BPMN text is empty.");
        }
        String text = content.charAt(0) == '﻿' ? content.substring(1) : content;
        if (text.length() > MAX_CHARS) {
            throw new Unreadable("The BPMN text is longer than " + MAX_CHARS + " characters.");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("<!doctype") || lower.contains("<!entity")) {
            throw new Unreadable("The BPMN text contains a DOCTYPE or ENTITY declaration. Document type definitions and "
                    + "external entities are not allowed.");
        }
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
        setQuietly(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setQuietly(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External resources are not allowed.");
        });
        XMLStreamReader reader = null;
        List<String> ignorable = new ArrayList<>();
        try {
            reader = factory.createXMLStreamReader(new StringReader(text));
            Deque<El> stack = new ArrayDeque<>();
            El root = null;
            int count = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                int line = reader.getLocation() == null ? 0 : reader.getLocation().getLineNumber();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        if (++count > MAX_ELEMENTS) {
                            throw new Unreadable("The BPMN text has more than " + MAX_ELEMENTS + " elements.");
                        }
                        if (stack.size() >= MAX_DEPTH) {
                            throw new Unreadable("Elements are nested deeper than " + MAX_DEPTH + " levels.");
                        }
                        El el = new El(stack.peek(), reader.getNamespaceURI(), reader.getLocalName(), line);
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            String attrNs = reader.getAttributeNamespace(i);
                            String key = attrNs == null || attrNs.isEmpty() ? reader.getAttributeLocalName(i)
                                    : "{" + attrNs + "}" + reader.getAttributeLocalName(i);
                            el.attributes.put(key, reader.getAttributeValue(i));
                        }
                        if (stack.isEmpty()) {
                            root = el;
                        } else {
                            stack.peek().children.add(el);
                        }
                        stack.push(el);
                    }
                    case XMLStreamConstants.END_ELEMENT -> stack.pop();
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        if (!stack.isEmpty() && stack.peek().text.length() < 100_000) {
                            stack.peek().text.append(reader.getText());
                        }
                    }
                    case XMLStreamConstants.COMMENT -> ignorable.add("XML comment on line " + line);
                    case XMLStreamConstants.PROCESSING_INSTRUCTION ->
                        ignorable.add("XML processing instruction <?" + reader.getPITarget() + "?> on line " + line);
                    case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
                        throw new Unreadable("The BPMN text contains a DOCTYPE or entity reference, which is not allowed.");
                    default -> {
                        // the XML declaration and document end carry nothing
                    }
                }
            }
            if (root == null) {
                throw new Unreadable("The BPMN text has no root element.");
            }
            return new Parsed(root, List.copyOf(ignorable));
        } catch (XMLStreamException e) {
            String where = e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNumber() + ", column "
                    + e.getLocation().getColumnNumber() + ")";
            throw new Unreadable("The BPMN text is not well-formed XML" + where + ".");
        } catch (Unreadable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Unreadable("The BPMN text could not be read as XML.");
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // nothing to release
                }
            }
        }
    }

    static String label(String ns, String local) {
        String prefix = switch (ns) {
            case BpmnGenerator.BPMN_NS -> "bpmn";
            case BpmnGenerator.FLOWABLE_NS -> "flowable";
            case BpmnGenerator.ORQIX_NS -> "orqix";
            case BpmnGenerator.BPMNDI_NS -> "bpmndi";
            case BpmnGenerator.DC_NS -> "dc";
            case BpmnGenerator.DI_NS -> "di";
            case BpmnGenerator.XSI_NS -> "xsi";
            case "" -> "";
            default -> "{" + ns + "}";
        };
        return prefix.isEmpty() ? local : prefix.startsWith("{") ? prefix + local : prefix + ":" + local;
    }

    /** Label of an attribute key ({@code local} or {@code {ns}local}). */
    static String attributeLabel(String key) {
        if (!key.startsWith("{")) {
            return key;
        }
        int end = key.indexOf('}');
        return label(key.substring(1, end), key.substring(end + 1));
    }

    private static void setQuietly(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // implementation without the JAXP 1.5 property; DTDs are disabled and rejected anyway
        }
    }
}
