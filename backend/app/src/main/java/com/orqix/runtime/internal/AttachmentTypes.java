package com.orqix.runtime.internal;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The allowed attachment types and the checks that decide a file's type (docs/build/S17 section 2).
 *
 * <p>A file is accepted only when its name's extension, the content type the browser declared (when it declared a
 * useful one) and the bytes agree on one allowed type. The bytes are checked, never trusted: the first bytes must be
 * the signature of the type, Office files must really be OOXML packages of the right kind without macros, and text
 * files must be clean UTF-8 text that is not HTML, SVG, XML or a script. Everything else - executables, archives,
 * HTML and SVG, legacy Office files (which can carry macros), mail files (msg, eml), images other than PNG and JPEG -
 * is refused. The stored content type is always the canonical one of the detected type, never the declared one.
 */
public final class AttachmentTypes {

    /** An allowed type: the key used by the workflow schema ({@code acceptTypes}), its canonical content type, label. */
    public enum Type {
        PDF("pdf", "application/pdf", "PDF"),
        PNG("png", "image/png", "PNG image"),
        JPEG("jpeg", "image/jpeg", "JPEG image"),
        TXT("txt", "text/plain", "text file"),
        CSV("csv", "text/csv", "CSV file"),
        DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word document"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel workbook"),
        PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "PowerPoint presentation");

        private final String key;
        private final String contentType;
        private final String label;

        Type(String key, String contentType, String label) {
            this.key = key;
            this.contentType = contentType;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String contentType() {
            return contentType;
        }

        public String label() {
            return label;
        }

        public static Optional<Type> ofKey(String key) {
            for (Type t : values()) {
                if (t.key.equals(key)) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }

    /** Why a file was refused; {@code message} is business wording for the person who chose the file. */
    public record Refusal(String message) {
    }

    /** Outcome of {@link #detect}: the type, or the refusal. */
    public record Detection(Type type, Refusal refusal) {
        public boolean accepted() {
            return type != null;
        }
    }

    /** Names of the allowed types for messages, in a stable order. */
    public static String allowedList() {
        return "PDF, PNG, JPEG, text, CSV, Word (docx), Excel (xlsx) and PowerPoint (pptx) files";
    }

    private static final int MAX_FILE_NAME = 120;
    private static final int MAX_ZIP_ENTRIES = 2000;

    private AttachmentTypes() {
    }

    // ---------------------------------------------------------------- names

    /**
     * A display name that is safe to show and to put in a Content-Disposition header: no directory part, no control or
     * bidirectional characters, only letters, digits, spaces and a few punctuation marks, at most 120 characters with
     * the extension kept. The name is a label only; it is never used to build a path.
     */
    public static String sanitizeName(String original) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder();
        name.codePoints().forEach(cp -> {
            boolean ok = Character.isLetterOrDigit(cp) || " ._()+,&'-".indexOf(cp) >= 0 || cp == '[' || cp == ']';
            sb.appendCodePoint(ok ? cp : '_');
        });
        String clean = sb.toString().strip();
        while (clean.startsWith(".")) {
            clean = clean.substring(1);
        }
        clean = clean.replaceAll("\\s{2,}", " ").replaceAll("_{2,}", "_");
        if (clean.length() > MAX_FILE_NAME) {
            String extension = extension(clean);
            String suffix = extension.isEmpty() || extension.length() > 10 ? "" : "." + extension;
            clean = clean.substring(0, MAX_FILE_NAME - suffix.length()).stripTrailing() + suffix;
        }
        return clean.isBlank() ? "file" : clean;
    }

    /** Lower-case extension without the dot, or an empty string. */
    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- detection

    /**
     * Decides the type of a file.
     *
     * @param sanitizedName the sanitized file name (its extension names the intended type)
     * @param declaredType  the content type the client sent for the file part; may be null or {@code application/octet-stream}
     * @param content       the whole file
     */
    public static Detection detect(String sanitizedName, String declaredType, byte[] content) {
        Type byName = byExtension(extension(sanitizedName));
        if (byName == null) {
            return refuse("This kind of file is not allowed. Allowed: " + allowedList() + ".");
        }
        if (!declaredAgrees(byName, declaredType)) {
            return refuse("The file says it is one kind of file but its name says another. Choose the original file.");
        }
        Type sniffed = sniff(content);
        if (sniffed == null) {
            return refuse("The content of this file is not a valid " + byName.label() + ". Allowed: " + allowedList()
                    + ".");
        }
        boolean agree = sniffed == byName
                || (sniffed == Type.TXT && byName == Type.CSV); // plain text content is as valid for csv as for txt
        if (!agree) {
            return refuse("The content of this file is not a " + byName.label() + ", so it was not accepted.");
        }
        return new Detection(byName, null);
    }

    private static Detection refuse(String message) {
        return new Detection(null, new Refusal(message));
    }

    private static Type byExtension(String extension) {
        return switch (extension) {
            case "pdf" -> Type.PDF;
            case "png" -> Type.PNG;
            case "jpg", "jpeg" -> Type.JPEG;
            case "txt" -> Type.TXT;
            case "csv" -> Type.CSV;
            case "docx" -> Type.DOCX;
            case "xlsx" -> Type.XLSX;
            case "pptx" -> Type.PPTX;
            default -> null;
        };
    }

    /** A browser may send application/octet-stream or nothing; any other declared type must match the extension's type. */
    private static boolean declaredAgrees(Type type, String declared) {
        if (declared == null || declared.isBlank()) {
            return true;
        }
        String base = declared.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (base.equals("application/octet-stream") || base.equals("binary/octet-stream")) {
            return true;
        }
        return switch (type) {
            case JPEG -> base.equals("image/jpeg") || base.equals("image/jpg") || base.equals("image/pjpeg");
            case CSV -> base.equals("text/csv") || base.equals("application/csv")
                    || base.equals("application/vnd.ms-excel") || base.equals("text/plain");
            case TXT -> base.equals("text/plain");
            default -> base.equals(type.contentType());
        };
    }

    /** The type the bytes really are, or null when they are none of the allowed types. */
    static Type sniff(byte[] c) {
        if (c.length == 0) {
            return null;
        }
        if (startsWith(c, 0x25, 0x50, 0x44, 0x46, 0x2D)) { // %PDF-
            return Type.PDF;
        }
        if (startsWith(c, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Type.PNG;
        }
        if (startsWith(c, 0xFF, 0xD8, 0xFF)) {
            return Type.JPEG;
        }
        if (startsWith(c, 0x50, 0x4B, 0x03, 0x04)) { // PK: an OOXML package or some other ZIP archive
            return ooxml(c);
        }
        return text(c) ? Type.TXT : null;
    }

    private static boolean startsWith(byte[] c, int... prefix) {
        if (c.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((c[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- OOXML packages

    /**
     * Reads the central directory of the ZIP package (names only, nothing is decompressed, so a zip bomb costs
     * nothing) and decides docx, xlsx or pptx. A package without {@code [Content_Types].xml}, with a macro project, with
     * path tricks, or of none of the three kinds is refused. Other ZIP archives are therefore refused.
     */
    private static Type ooxml(byte[] c) {
        int eocd = -1;
        for (int i = c.length - 22; i >= Math.max(0, c.length - 22 - 65535); i--) {
            if (u32(c, i) == 0x06054b50L) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            return null;
        }
        int entries = u16(c, eocd + 10);
        long directory = u32(c, eocd + 16);
        if (entries == 0 || entries > MAX_ZIP_ENTRIES || directory >= c.length) {
            return null;
        }
        Set<String> names = new HashSet<>();
        int p = (int) directory;
        for (int i = 0; i < entries; i++) {
            if (p + 46 > c.length || u32(c, p) != 0x02014b50L) {
                return null;
            }
            int nameLength = u16(c, p + 28);
            int extraLength = u16(c, p + 30);
            int commentLength = u16(c, p + 32);
            if (p + 46 + nameLength > c.length) {
                return null;
            }
            String name = new String(c, p + 46, nameLength, StandardCharsets.UTF_8);
            if (name.startsWith("/") || name.contains("..") || name.contains("\\")) {
                return null;
            }
            names.add(name.toLowerCase(Locale.ROOT));
            p += 46 + nameLength + extraLength + commentLength;
        }
        if (!names.contains("[content_types].xml") || names.stream().anyMatch(n -> n.endsWith("vbaproject.bin"))) {
            return null;
        }
        if (names.contains("word/document.xml")) {
            return Type.DOCX;
        }
        if (names.contains("xl/workbook.xml")) {
            return Type.XLSX;
        }
        if (names.contains("ppt/presentation.xml")) {
            return Type.PPTX;
        }
        return null;
    }

    private static int u16(byte[] c, int at) {
        return (c[at] & 0xFF) | (c[at + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] c, int at) {
        if (at < 0 || at + 4 > c.length) {
            return -1;
        }
        return (c[at] & 0xFFL) | (c[at + 1] & 0xFFL) << 8 | (c[at + 2] & 0xFFL) << 16 | (c[at + 3] & 0xFFL) << 24;
    }

    // ---------------------------------------------------------------- text

    private static final List<String> MARKUP_STARTS = List.of("<!doctype", "<html", "<svg", "<?xml", "<script", "<body",
            "<iframe", "<head", "<meta", "<object", "<embed", "<link", "<style", "<img", "<a ", "<form", "<math");

    /** Clean UTF-8 text: no NUL or other control characters (except tab, line breaks, form feed), not markup, not a script. */
    private static boolean text(byte[] c) {
        int offset = startsWith(c, 0xEF, 0xBB, 0xBF) ? 3 : 0;
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(c, offset, c.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' && ch != '\f' || ch == 0x7F) {
                return false;
            }
        }
        String head = s.substring(0, Math.min(s.length(), 4096)).stripLeading().toLowerCase(Locale.ROOT);
        if (head.startsWith("#!")) {
            return false;
        }
        for (String markup : MARKUP_STARTS) {
            if (head.startsWith(markup)) {
                return false;
            }
        }
        return true;
    }
}
