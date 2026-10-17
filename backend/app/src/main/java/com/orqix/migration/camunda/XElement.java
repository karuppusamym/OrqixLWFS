package com.orqix.migration.camunda;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal immutable-after-parse XML element: namespace, local name, attributes keyed {@code local} or
 * {@code {namespace}local}, children in document order and the trimmed text content.
 */
public final class XElement {

    private final String ns;
    private final String name;
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final List<XElement> children = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private final XElement parent;
    private final int ordinal;

    XElement(XElement parent, String ns, String name, int ordinal) {
        this.parent = parent;
        this.ns = ns == null ? "" : ns;
        this.name = name;
        this.ordinal = ordinal;
    }

    public String ns() {
        return ns;
    }

    public String name() {
        return name;
    }

    public XElement parent() {
        return parent;
    }

    /** Position in document order (1-based). */
    public int ordinal() {
        return ordinal;
    }

    public List<XElement> children() {
        return children;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    void addAttribute(String attrNs, String local, String value) {
        attributes.put(attrNs == null || attrNs.isEmpty() ? local : "{" + attrNs + "}" + local, value);
    }

    void appendText(CharSequence chars) {
        if (text.length() < 20_000) {
            text.append(chars);
        }
    }

    public String text() {
        return text.toString().trim();
    }

    public boolean is(String namespace, String local) {
        return ns.equals(namespace) && name.equals(local);
    }

    public String attr(String local) {
        return attributes.get(local);
    }

    public String attr(String namespace, String local) {
        return attributes.get("{" + namespace + "}" + local);
    }

    public List<XElement> children(String namespace, String local) {
        List<XElement> found = new ArrayList<>();
        for (XElement child : children) {
            if (child.is(namespace, local)) {
                found.add(child);
            }
        }
        return found;
    }

    public XElement child(String namespace, String local) {
        for (XElement child : children) {
            if (child.is(namespace, local)) {
                return child;
            }
        }
        return null;
    }

    /** {@code ns:local} style label for messages, with the well-known prefixes. */
    public String label() {
        return Namespaces.prefixed(ns, name);
    }
}
