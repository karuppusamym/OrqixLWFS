package com.orqix.compiler.internal.bpmn;

import java.io.StringWriter;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Deterministic XML formatting over the JDK's built-in StAX writer: UTF-8 declaration, LF line ends, two-space indent,
 * attributes in call order, trailing newline. {@link XMLOutputFactory#newDefaultFactory()} is used so that another
 * StAX implementation on the application classpath (for example Woodstox) cannot change the bytes.
 * <p>
 * Text is made XML-safe before writing: tab, CR and LF become spaces (an XML parser would normalize them in attribute
 * values anyway) and characters that XML 1.0 forbids become U+FFFD.
 */
public final class XmlOut {

    private final StringWriter buffer = new StringWriter();
    private final XMLStreamWriter w;
    private int depth;

    public XmlOut() {
        try {
            this.w = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(buffer);
            w.writeStartDocument("UTF-8", "1.0");
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Starts an element with children on following lines; attributes may be added until the first child. */
    public XmlOut open(String prefix, String local, String ns) {
        try {
            newline();
            w.writeStartElement(prefix, local, ns);
            depth++;
            return this;
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes an empty element; attributes may be added next. */
    public XmlOut leaf(String prefix, String local, String ns) {
        try {
            newline();
            w.writeEmptyElement(prefix, local, ns);
            return this;
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    public XmlOut defaultNamespace(String uri) {
        try {
            w.writeDefaultNamespace(uri);
            return this;
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    public XmlOut namespace(String prefix, String uri) {
        try {
            w.writeNamespace(prefix, uri);
            return this;
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    public XmlOut attr(String name, String value) {
        try {
            w.writeAttribute(name, clean(value));
            return this;
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    public XmlOut attr(String prefix, String ns, String name, String value) {
        try {
            w.writeAttribute(prefix, ns, name, clean(value));
            return this;
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ends the innermost element opened with {@link #open}, on its own line. */
    public XmlOut close() {
        try {
            depth--;
            newline();
            w.writeEndElement();
            return this;
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes {@code <outer attrs...><inner>text</inner></outer>} on one line (DMN entries), or
     * {@code <outer attrs...>text</outer>} when {@code inner} is null.
     *
     * @param attributes alternating names and values (unprefixed)
     */
    public XmlOut inline(String prefix, String outer, String ns, String inner, String text, boolean cdata,
                         String... attributes) {
        try {
            newline();
            w.writeStartElement(prefix, outer, ns);
            for (int i = 0; i < attributes.length; i += 2) {
                attr(attributes[i], attributes[i + 1]);
            }
            if (inner != null) {
                w.writeStartElement(prefix, inner, ns);
            }
            if (cdata) {
                w.writeCData(clean(text));
            } else {
                w.writeCharacters(clean(text));
            }
            if (inner != null) {
                w.writeEndElement();
            }
            w.writeEndElement();
            return this;
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Opens an element whose content is written by the caller through {@link #rawCData}/{@link #closeInline}. */
    public XmlOut openInline(String prefix, String local, String ns) {
        try {
            newline();
            w.writeStartElement(prefix, local, ns);
            return this;
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    public XmlOut cdata(String text) {
        try {
            w.writeCData(clean(text));
            return this;
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    public XmlOut closeInline() {
        try {
            w.writeEndElement();
            return this;
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the document, ending with a newline */
    public String finish() {
        try {
            w.writeCharacters("\n");
            w.writeEndDocument();
            w.flush();
            w.close();
            return buffer.toString();
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    private void newline() throws XMLStreamException {
        w.writeCharacters("\n" + "  ".repeat(depth));
    }

    /** @return the text with tab/CR/LF as spaces and XML-forbidden characters replaced by U+FFFD */
    static String clean(String text) {
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char replacement = c;
            if (c == '\t' || c == '\n' || c == '\r') {
                replacement = ' ';
            } else if (c < 0x20 || c == 0xFFFE || c == 0xFFFF) {
                replacement = '�';
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    if (sb != null) {
                        sb.append(c).append(text.charAt(i + 1));
                    }
                    i++;
                    continue;
                }
                replacement = '�';
            } else if (Character.isLowSurrogate(c)) {
                replacement = '�';
            }
            if (replacement != c && sb == null) {
                sb = new StringBuilder(text.length());
                sb.append(text, 0, i);
            }
            if (sb != null) {
                sb.append(replacement);
            }
        }
        return sb == null ? text : sb.toString();
    }
}
