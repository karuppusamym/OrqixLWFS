package com.orqix.migration.camunda;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Parses untrusted XML into an {@link XElement} tree (S11 section 1): size, element-count and depth limits, DTDs and
 * external entities disabled, no external resources of any kind. Any DOCTYPE or ENTITY declaration is rejected
 * outright, so entity-expansion and external-entity (XXE) payloads never reach the parser.
 */
public final class SafeXml {

    private SafeXml() {
    }

    public static XElement parse(String content, Limits limits) {
        if (content == null || content.isBlank()) {
            throw SourceException.invalid("The file is empty.");
        }
        String text = content.charAt(0) == '﻿' ? content.substring(1) : content;
        if (text.getBytes(StandardCharsets.UTF_8).length > limits.maxBytes()) {
            throw new SourceException("The file is larger than " + limits.maxBytes() / 1024 + " KiB.", true);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("<!doctype") || lower.contains("<!entity")) {
            throw SourceException.invalid("The file contains a DOCTYPE or ENTITY declaration. Document type definitions "
                    + "and external entities are not allowed.");
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
            Deque<XElement> stack = new ArrayDeque<>();
            XElement root = null;
            int count = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        count++;
                        if (count > limits.maxElements()) {
                            throw SourceException.invalid("The file has more than " + limits.maxElements() + " elements.");
                        }
                        if (stack.size() >= limits.maxDepth()) {
                            throw SourceException.invalid("Elements are nested deeper than " + limits.maxDepth() + " levels.");
                        }
                        XElement element = new XElement(stack.peek(), reader.getNamespaceURI(), reader.getLocalName(), count);
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            element.addAttribute(reader.getAttributeNamespace(i), reader.getAttributeLocalName(i),
                                    reader.getAttributeValue(i));
                        }
                        if (stack.isEmpty()) {
                            root = element;
                        } else {
                            stack.peek().children().add(element);
                        }
                        stack.push(element);
                    }
                    case XMLStreamConstants.END_ELEMENT -> stack.pop();
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        if (!stack.isEmpty()) {
                            stack.peek().appendText(reader.getText());
                        }
                    }
                    case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
                        throw SourceException.invalid("The file contains a DOCTYPE or entity reference, which is not allowed.");
                    default -> {
                        // comments, processing instructions and the XML declaration carry no model content
                    }
                }
            }
            if (root == null) {
                throw SourceException.invalid("The file has no root element.");
            }
            return root;
        } catch (XMLStreamException e) {
            String where = e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNumber() + ", column "
                    + e.getLocation().getColumnNumber() + ")";
            throw SourceException.invalid("The file is not well-formed XML" + where + ".");
        } catch (RuntimeException e) {
            if (e instanceof SourceException se) {
                throw se;
            }
            throw SourceException.invalid("The file could not be read as XML.");
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
            // implementation without the JAXP 1.5 property; DTDs are disabled and rejected anyway
        }
    }
}
