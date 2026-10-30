package com.orqix.catalog.internal;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
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
 * Parses an untrusted DMN file into a small element tree (docs/build/S121), with the same protections as the
 * migration importer's parser: any DOCTYPE or ENTITY declaration is refused before parsing, DTDs, external entities
 * and every external resource are disabled, and size, element count and depth are bounded. Kept inside the catalog
 * module so it does not depend on the migration module.
 */
final class DmnXml {

    /** The file cannot be parsed; the message is safe to show. */
    static final class Unreadable extends RuntimeException {
        Unreadable(String message) {
            super(message);
        }
    }

    /** An element: namespace, local name, unprefixed attributes, children in document order and trimmed text. */
    static final class Element {
        private final String ns;
        private final String name;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final List<Element> children = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        Element(String ns, String name) {
            this.ns = ns == null ? "" : ns;
            this.name = name;
        }

        String ns() {
            return ns;
        }

        String name() {
            return name;
        }

        List<Element> children() {
            return children;
        }

        String attr(String local) {
            return attributes.get(local);
        }

        String text() {
            return text.toString().trim();
        }

        List<Element> children(String namespace, String local) {
            List<Element> found = new ArrayList<>();
            for (Element child : children) {
                if (child.ns.equals(namespace) && child.name.equals(local)) {
                    found.add(child);
                }
            }
            return found;
        }

        Element child(String namespace, String local) {
            List<Element> found = children(namespace, local);
            return found.isEmpty() ? null : found.get(0);
        }
    }

    private DmnXml() {
    }

    static Element parse(String content, int maxBytes, int maxElements, int maxDepth) {
        if (content == null || content.isBlank()) {
            throw new Unreadable("The file is empty.");
        }
        String text = content.charAt(0) == '﻿' ? content.substring(1) : content;
        if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new Unreadable("The file is larger than " + maxBytes / 1024 + " KiB.");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("<!doctype") || lower.contains("<!entity")) {
            throw new Unreadable("The file contains a DOCTYPE or ENTITY declaration. Document type definitions and"
                    + " external entities are not allowed.");
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
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
        try {
            reader = factory.createXMLStreamReader(new StringReader(text));
            Deque<Element> stack = new ArrayDeque<>();
            Element root = null;
            int count = 0;
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        if (++count > maxElements) {
                            throw new Unreadable("The file has more than " + maxElements + " elements.");
                        }
                        if (stack.size() >= maxDepth) {
                            throw new Unreadable("Elements are nested deeper than " + maxDepth + " levels.");
                        }
                        Element element = new Element(reader.getNamespaceURI(), reader.getLocalName());
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            String attrNs = reader.getAttributeNamespace(i);
                            if (attrNs == null || attrNs.isEmpty()) {
                                element.attributes.put(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                            }
                        }
                        if (stack.isEmpty()) {
                            root = element;
                        } else {
                            stack.peek().children.add(element);
                        }
                        stack.push(element);
                    }
                    case XMLStreamConstants.END_ELEMENT -> stack.pop();
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        if (!stack.isEmpty() && stack.peek().text.length() < 20_000) {
                            stack.peek().text.append(reader.getText());
                        }
                    }
                    case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
                        throw new Unreadable("The file contains a DOCTYPE or entity reference, which is not allowed.");
                    default -> {
                        // comments, processing instructions and the XML declaration carry no content
                    }
                }
            }
            if (root == null) {
                throw new Unreadable("The file has no root element.");
            }
            return root;
        } catch (XMLStreamException e) {
            String where = e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNumber() + ", column "
                    + e.getLocation().getColumnNumber() + ")";
            throw new Unreadable("The file is not well-formed XML" + where + ".");
        } catch (Unreadable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Unreadable("The file could not be read as XML.");
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

    private static void setQuietly(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // implementation without the JAXP 1.5 property; DTDs are disabled and refused anyway
        }
    }
}
