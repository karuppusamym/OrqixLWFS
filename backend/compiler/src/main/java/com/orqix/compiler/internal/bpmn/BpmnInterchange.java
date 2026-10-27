package com.orqix.compiler.internal.bpmn;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.orqix.compiler.internal.json.StrictJson;

import tools.jackson.databind.JsonNode;

/**
 * The downloadable {@code .bpmn} file (STU04, docs/build/S97-bpmn-export.md): the compiled BPMN with a <em>complete</em>
 * BPMN diagram interchange (BPMNDiagram, BPMNPlane, one BPMNShape per flow node and one BPMNEdge per sequence flow,
 * with Bounds and waypoints) and a header comment.
 * <p>
 * The deployed BPMN (and its hash and golden files) is not touched: the compiler writes the diagram only when every step
 * has a canvas position, and does not draw the generated helper elements of every kind. This class takes the compiled
 * text, drops whatever diagram it has and writes a new one for <em>every</em> element it finds, so the file opens laid
 * out in any standard modeller. Everything else in the text is kept byte for byte.
 *
 * <h3>Layout algorithm (deterministic; the same input gives the same bytes)</h3>
 * <ol>
 *   <li><b>Sizes</b> are {@link BpmnShapeSizes}: events 48 x 48, gateways 64 x 64, tasks and call activities 200 x 80. A
 *       sub-process (section or handler) is as large as its content plus padding.</li>
 *   <li><b>Saved positions.</b> When every step (every flow node whose id has no {@code __}) has a position in
 *       {@code uiLayout.positions}, those positions are the top-left corners of the step shapes and are kept exactly. If
 *       even one step has none, no saved position is used (mixing would let computed shapes land on top of saved ones)
 *       and the whole diagram is computed.</li>
 *   <li><b>Computed layout, left to right.</b> Per container (the process, then each sub-process on its own): the steps are
 *       the nodes; a depth-first search from the nodes without incoming flows (then from any node not yet reached), in
 *       document order, finds the back edges (loops), which are ignored for layering; every other node is put in the
 *       column {@code 1 + the largest column of its predecessors}. A column is as wide as its widest node (plus the room
 *       for its helpers), columns are 80 apart, the nodes of a column are stacked in document order 60 apart and the
 *       stack is centred on the tallest one. Everything starts 40 from the top-left corner.</li>
 *   <li><b>Generated helpers</b> (an approval's outcome gateway, a message wait's catch and timer events) are placed to the
 *       right of the step they belong to, 40 away, stacked and centred on the step. A boundary event is centred on the
 *       bottom edge of the shape it is attached to, evenly spread. A sub-process's start event is inside its left edge
 *       and its end events are in a column inside its right edge.</li>
 *   <li><b>Edges</b> run from the right edge of the source to the left edge of the target (a straight line, or an
 *       elbow with the bend halfway); when the target lies behind the source the line leaves the bottom of the source,
 *       runs 40 below both shapes and enters the bottom of the target; vertically stacked shapes are joined bottom to top.
 *       Waypoints are absolute diagram coordinates.</li>
 * </ol>
 */
public final class BpmnInterchange {

    private static final String BPMN_NS = BpmnGenerator.BPMN_NS;
    private static final String ORQIX_NS = BpmnGenerator.ORQIX_NS;
    private static final String FLOWABLE_NS = BpmnGenerator.FLOWABLE_NS;

    private static final Set<String> EVENTS = Set.of("startEvent", "endEvent", "intermediateCatchEvent",
            "intermediateThrowEvent", "boundaryEvent");
    private static final Set<String> GATEWAYS = Set.of("exclusiveGateway", "parallelGateway", "inclusiveGateway",
            "eventBasedGateway", "complexGateway");
    private static final Set<String> TASKS = Set.of("task", "userTask", "serviceTask", "scriptTask", "sendTask",
            "receiveTask", "manualTask", "businessRuleTask", "callActivity");
    private static final Set<String> CONTAINERS = Set.of("subProcess", "transaction", "adHocSubProcess");

    private static final double MARGIN = 40;
    private static final double COLUMN_GAP = 80;
    private static final double ROW_GAP = 60;
    private static final double HELPER_GAP = 40;
    private static final double HELPER_STACK_GAP = 24;
    private static final double PAD_SIDE = 100;
    private static final double PAD_TOP = 50;
    private static final double PAD_BOTTOM = 50;
    private static final double BACK_EDGE_DROP = 40;

    private record Rect(double x, double y, double w, double h) {
        double right() {
            return x + w;
        }

        double bottom() {
            return y + h;
        }

        double cx() {
            return x + w / 2;
        }

        double cy() {
            return y + h / 2;
        }

        Rect moved(double dx, double dy) {
            return new Rect(x + dx, y + dy, w, h);
        }
    }

    /** Placed shapes of one container (nested sub-processes included) and, for a sub-process, its own frame. */
    private record Placed(Map<String, Rect> rects, Rect frame) {
    }

    private final Document doc;
    private final JsonNode positions;
    private final Map<String, Element> flowNodes = new LinkedHashMap<>();
    private final boolean saved;

    private BpmnInterchange(Document doc, JsonNode positions) {
        this.doc = doc;
        this.positions = positions;
        collect(doc.getDocumentElement());
        boolean all = !flowNodes.isEmpty();
        for (Element e : flowNodes.values()) {
            String id = e.getAttribute("id");
            if (!id.contains("__") && !CONTAINERS.contains(e.getLocalName()) && !validPosition(id)) {
                all = false;
            }
        }
        this.saved = all;
    }

    /**
     * @param bpmnXml        the compiled BPMN ({@code process.bpmn20.xml})
     * @param definitionJson the canonical workflow document of the same draft revision or release (for the saved positions)
     * @param sourceLabel    what the file was made from, ids and numbers only (for example {@code draft 0190... revision 3})
     * @return the BPMN file: header comment, the unchanged process, a complete diagram
     */
    public static String export(String bpmnXml, String definitionJson, String sourceLabel) {
        Document doc = parse(bpmnXml);
        JsonNode definition = StrictJson.parse(definitionJson);
        BpmnInterchange x = new BpmnInterchange(doc, definition.path("uiLayout").path("positions"));
        return x.write(bpmnXml, sourceLabel);
    }

    /** Whether the saved canvas positions are used (every step has one); otherwise the layout is computed. */
    public static boolean usesSavedPositions(String bpmnXml, String definitionJson) {
        JsonNode definition = StrictJson.parse(definitionJson);
        return new BpmnInterchange(parse(bpmnXml), definition.path("uiLayout").path("positions")).saved;
    }

    // ------------------------------------------------------------------------------------------------- parsing

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("The BPMN text is not well-formed XML: " + e.getMessage(), e);
        }
    }

    private void collect(Element element) {
        for (Element child : children(element)) {
            if (!BPMN_NS.equals(child.getNamespaceURI())) {
                continue;
            }
            String name = child.getLocalName();
            if (isFlowNode(name) && !child.getAttribute("id").isEmpty()) {
                flowNodes.put(child.getAttribute("id"), child);
                if (CONTAINERS.contains(name)) {
                    collect(child);
                }
            } else if ("process".equals(name)) {
                collect(child);
            }
        }
    }

    private static boolean isFlowNode(String local) {
        return EVENTS.contains(local) || GATEWAYS.contains(local) || TASKS.contains(local) || CONTAINERS.contains(local);
    }

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }

    private boolean validPosition(String id) {
        JsonNode p = positions.path(id);
        return p.path("x").isNumber() && p.path("y").isNumber();
    }

    // ------------------------------------------------------------------------------------------------- layout

    private static Rect size(Element e, double x, double y) {
        String local = e.getLocalName();
        if (EVENTS.contains(local)) {
            return new Rect(x, y, BpmnShapeSizes.EVENT, BpmnShapeSizes.EVENT);
        }
        if (GATEWAYS.contains(local)) {
            return new Rect(x, y, BpmnShapeSizes.GATEWAY, BpmnShapeSizes.GATEWAY);
        }
        return new Rect(x, y, BpmnShapeSizes.TASK_WIDTH, BpmnShapeSizes.TASK_HEIGHT);
    }

    /** The id of the element a generated helper belongs to: the part before the first {@code __}, when it exists. */
    private String hostOf(String id) {
        int i = id.indexOf("__");
        if (i <= 0) {
            return null;
        }
        String host = id.substring(0, i);
        return flowNodes.containsKey(host) ? host : null;
    }

    private Placed layout(Element container, boolean isProcess) {
        String containerId = container.getAttribute("id");
        List<Element> members = new ArrayList<>();
        Map<String, Element> local = new LinkedHashMap<>();
        for (Element e : children(container)) {
            if (BPMN_NS.equals(e.getNamespaceURI()) && flowNodes.get(e.getAttribute("id")) == e) {
                members.add(e);
                local.put(e.getAttribute("id"), e);
            }
        }
        List<Element> primaries = new ArrayList<>();
        Map<String, List<Element>> boundaries = new LinkedHashMap<>();
        Map<String, List<Element>> helpers = new LinkedHashMap<>();
        List<Element> inner = new ArrayList<>();
        for (Element e : members) {
            String id = e.getAttribute("id");
            String attached = e.getAttribute("attachedToRef");
            String host = hostOf(id);
            if ("boundaryEvent".equals(e.getLocalName()) && local.containsKey(attached)) {
                boundaries.computeIfAbsent(attached, k -> new ArrayList<>()).add(e);
            } else if (!isProcess && containerId.equals(host)) {
                inner.add(e);
            } else if (host != null && local.containsKey(host)) {
                helpers.computeIfAbsent(host, k -> new ArrayList<>()).add(e);
            } else {
                primaries.add(e);
            }
        }

        Map<String, Rect> rects = new LinkedHashMap<>();
        Map<String, Placed> nested = new HashMap<>();
        Map<String, Rect> base = new LinkedHashMap<>();
        for (Element p : primaries) {
            String id = p.getAttribute("id");
            if (CONTAINERS.contains(p.getLocalName())) {
                Placed sub = layout(p, false);
                nested.put(id, sub);
                base.put(id, new Rect(0, 0, sub.frame().w(), sub.frame().h()));
            } else {
                base.put(id, size(p, 0, 0));
            }
        }

        Map<String, Rect> at = new LinkedHashMap<>();
        if (saved) {
            for (Element p : primaries) {
                String id = p.getAttribute("id");
                Rect r = base.get(id);
                if (nested.containsKey(id)) {
                    Rect f = nested.get(id).frame();
                    at.put(id, new Rect(f.x(), f.y(), f.w(), f.h()));
                } else {
                    JsonNode pos = positions.path(id);
                    at.put(id, new Rect(pos.path("x").asDouble(), pos.path("y").asDouble(), r.w(), r.h()));
                }
            }
        } else {
            at.putAll(layered(container, primaries, base, boundaries, helpers, local));
        }

        for (Element p : primaries) {
            String id = p.getAttribute("id");
            Rect r = at.get(id);
            rects.put(id, r);
            Placed sub = nested.get(id);
            if (sub != null) {
                double dx = r.x() - sub.frame().x();
                double dy = r.y() - sub.frame().y();
                sub.rects().forEach((k, v) -> rects.put(k, v.moved(dx, dy)));
            }
        }
        for (Element p : primaries) {
            placeAround(p.getAttribute("id"), rects, boundaries, helpers);
        }
        // A boundary event or helper attached to a shape that is itself a helper is placed relative to that shape.
        for (Map.Entry<String, List<Element>> en : helpers.entrySet()) {
            for (Element h : en.getValue()) {
                placeAround(h.getAttribute("id"), rects, boundaries, helpers);
            }
        }

        Rect frame = null;
        if (!isProcess) {
            double left = Double.MAX_VALUE, top = Double.MAX_VALUE, right = -Double.MAX_VALUE, bottom = -Double.MAX_VALUE;
            for (Rect r : rects.values()) {
                left = Math.min(left, r.x());
                top = Math.min(top, r.y());
                right = Math.max(right, r.right());
                bottom = Math.max(bottom, r.bottom());
            }
            if (rects.isEmpty()) {
                left = 0;
                top = 0;
                right = BpmnShapeSizes.TASK_WIDTH;
                bottom = BpmnShapeSizes.TASK_HEIGHT;
            }
            left -= PAD_SIDE;
            right += PAD_SIDE;
            top -= PAD_TOP;
            bottom += PAD_BOTTOM;
            List<Element> starts = new ArrayList<>();
            List<Element> ends = new ArrayList<>();
            for (Element e : inner) {
                (e.getAttribute("id").endsWith("__start") ? starts : ends).add(e);
            }
            bottom = Math.max(bottom, top + 30 + 64.0 * ends.size());
            frame = new Rect(left, top, right - left, bottom - top);
            double mid = frame.cy();
            for (Element s : starts) {
                rects.put(s.getAttribute("id"), size(s, left + 26, mid - BpmnShapeSizes.EVENT / 2.0));
            }
            double y = top + 30;
            for (Element e : ends) {
                rects.put(e.getAttribute("id"), size(e, right - 74, y));
                y += 64;
            }
            rects.put(containerId, frame);
        }
        return new Placed(rects, frame);
    }

    /** Places the right-hand helpers and boundary events of one already placed shape. */
    private void placeAround(String id, Map<String, Rect> rects, Map<String, List<Element>> boundaries,
            Map<String, List<Element>> helpers) {
        Rect host = rects.get(id);
        if (host == null) {
            return;
        }
        List<Element> bs = boundaries.getOrDefault(id, List.of());
        for (int k = 0; k < bs.size(); k++) {
            double cx = host.x() + host.w() * (k + 1) / (bs.size() + 1);
            rects.put(bs.get(k).getAttribute("id"),
                    size(bs.get(k), cx - BpmnShapeSizes.EVENT / 2.0, host.bottom() - BpmnShapeSizes.EVENT / 2.0));
        }
        List<Element> hs = helpers.getOrDefault(id, List.of());
        if (!hs.isEmpty()) {
            double total = 0;
            for (Element h : hs) {
                total += size(h, 0, 0).h();
            }
            total += HELPER_STACK_GAP * (hs.size() - 1);
            double y = host.cy() - total / 2;
            for (Element h : hs) {
                Rect r = size(h, host.right() + HELPER_GAP, y);
                rects.put(h.getAttribute("id"), r);
                y += r.h() + HELPER_STACK_GAP;
            }
        }
    }

    private Map<String, Rect> layered(Element container, List<Element> primaries, Map<String, Rect> base,
            Map<String, List<Element>> boundaries, Map<String, List<Element>> helpers, Map<String, Element> local) {
        List<String> order = new ArrayList<>();
        primaries.forEach(p -> order.add(p.getAttribute("id")));
        Map<String, String> representative = new HashMap<>();
        for (String id : order) {
            representative.put(id, id);
        }
        boundaries.forEach((host, list) -> list.forEach(b -> representative.put(b.getAttribute("id"), host)));
        helpers.forEach((host, list) -> list.forEach(h -> representative.put(h.getAttribute("id"), host)));
        Map<String, List<String>> next = new LinkedHashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        order.forEach(id -> {
            next.put(id, new ArrayList<>());
            indegree.put(id, 0);
        });
        for (Element flow : children(container)) {
            if (!BPMN_NS.equals(flow.getNamespaceURI()) || !"sequenceFlow".equals(flow.getLocalName())) {
                continue;
            }
            String from = representative.get(flow.getAttribute("sourceRef"));
            String to = representative.get(flow.getAttribute("targetRef"));
            if (from == null || to == null || from.equals(to) || next.get(from).contains(to)) {
                continue;
            }
            next.get(from).add(to);
            indegree.merge(to, 1, Integer::sum);
        }
        // Depth-first search from the sources (document order) to find the back edges; a node's column follows from
        // the remaining, acyclic edges.
        Map<String, Integer> state = new HashMap<>();
        List<String> finished = new ArrayList<>();
        Set<String> back = new HashSet<>();
        List<String> roots = new ArrayList<>();
        order.stream().filter(id -> indegree.get(id) == 0).forEach(roots::add);
        order.stream().filter(id -> indegree.get(id) != 0).forEach(roots::add);
        for (String root : roots) {
            depthFirst(root, next, state, finished, back);
        }
        Map<String, Integer> layer = new HashMap<>();
        order.forEach(id -> layer.put(id, 0));
        for (int i = finished.size() - 1; i >= 0; i--) {
            String from = finished.get(i);
            for (String to : next.get(from)) {
                if (!back.contains(from + "\u0000" + to)) {
                    layer.merge(to, layer.get(from) + 1, Math::max);
                }
            }
        }
        int layers = layer.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        List<List<String>> columns = new ArrayList<>();
        for (int i = 0; i < layers; i++) {
            columns.add(new ArrayList<>());
        }
        for (String id : order) {
            columns.get(layer.get(id)).add(id);
        }
        double[] widths = new double[layers];
        double[] heights = new double[layers];
        double tallest = 0;
        for (int i = 0; i < layers; i++) {
            List<String> col = columns.get(i);
            for (int k = 0; k < col.size(); k++) {
                String id = col.get(k);
                double w = base.get(id).w();
                List<Element> hs = helpers.getOrDefault(id, List.of());
                if (!hs.isEmpty()) {
                    w += HELPER_GAP + hs.stream().mapToDouble(h -> size(h, 0, 0).w()).max().orElse(0);
                }
                widths[i] = Math.max(widths[i], w);
                heights[i] += base.get(id).h() + (k > 0 ? ROW_GAP : 0);
            }
            tallest = Math.max(tallest, heights[i]);
        }
        Map<String, Rect> out = new LinkedHashMap<>();
        double x = MARGIN;
        for (int i = 0; i < layers; i++) {
            double y = MARGIN + (tallest - heights[i]) / 2;
            for (String id : columns.get(i)) {
                Rect b = base.get(id);
                out.put(id, new Rect(x, y, b.w(), b.h()));
                y += b.h() + ROW_GAP;
            }
            x += widths[i] + COLUMN_GAP;
        }
        return out;
    }

    private static void depthFirst(String node, Map<String, List<String>> next, Map<String, Integer> state,
            List<String> finished, Set<String> back) {
        if (state.containsKey(node)) {
            return;
        }
        state.put(node, 1);
        for (String to : next.get(node)) {
            Integer s = state.get(to);
            if (s == null) {
                depthFirst(to, next, state, finished, back);
            } else if (s == 1) {
                back.add(node + "\u0000" + to);
            }
        }
        state.put(node, 2);
        finished.add(node);
    }

    // ------------------------------------------------------------------------------------------------- writing

    private String write(String bpmnXml, String sourceLabel) {
        Element process = null;
        for (Element e : children(doc.getDocumentElement())) {
            if (BPMN_NS.equals(e.getNamespaceURI()) && "process".equals(e.getLocalName())) {
                process = e;
            }
        }
        if (process == null) {
            throw new IllegalArgumentException("The BPMN has no process.");
        }
        Placed placed = layout(process, true);
        Map<String, Rect> rects = placed.rects();

        StringBuilder di = new StringBuilder();
        String key = process.getAttribute("id");
        di.append("  <bpmndi:BPMNDiagram id=\"").append(esc(key)).append("__diagram\"");
        if (!bpmnXml.contains("xmlns:bpmndi=")) {
            di.append(" xmlns:bpmndi=\"").append(BpmnGenerator.BPMNDI_NS).append("\" xmlns:omgdc=\"")
                    .append(BpmnGenerator.DC_NS).append("\" xmlns:omgdi=\"").append(BpmnGenerator.DI_NS).append('"');
        }
        di.append(">\n    <bpmndi:BPMNPlane id=\"").append(esc(key)).append("__plane\" bpmnElement=\"").append(esc(key))
                .append("\">\n");
        for (Element e : flowNodes.values()) {
            String id = e.getAttribute("id");
            Rect r = rects.get(id);
            if (r == null) {
                throw new IllegalStateException("No diagram shape could be placed for " + id);
            }
            di.append("      <bpmndi:BPMNShape id=\"").append(esc(id)).append("__di\" bpmnElement=\"").append(esc(id)).append('"');
            if (CONTAINERS.contains(e.getLocalName())) {
                di.append(" isExpanded=\"true\"");
            } else if ("exclusiveGateway".equals(e.getLocalName())) {
                di.append(" isMarkerVisible=\"true\"");
            }
            di.append(">\n        <omgdc:Bounds x=\"").append(num(r.x())).append("\" y=\"").append(num(r.y()))
                    .append("\" width=\"").append(num(r.w())).append("\" height=\"").append(num(r.h()))
                    .append("\"/>\n      </bpmndi:BPMNShape>\n");
        }
        NodeList flows = doc.getElementsByTagNameNS(BPMN_NS, "sequenceFlow");
        int edges = 0;
        for (int i = 0; i < flows.getLength(); i++) {
            Element flow = (Element) flows.item(i);
            String id = flow.getAttribute("id");
            Rect from = rects.get(flow.getAttribute("sourceRef"));
            Rect to = rects.get(flow.getAttribute("targetRef"));
            if (from == null || to == null) {
                throw new IllegalStateException("No diagram edge could be drawn for " + id);
            }
            di.append("      <bpmndi:BPMNEdge id=\"").append(esc(id)).append("__di\" bpmnElement=\"").append(esc(id)).append("\">\n");
            for (double[] w : waypoints(from, to)) {
                di.append("        <omgdi:waypoint x=\"").append(num(w[0])).append("\" y=\"").append(num(w[1])).append("\"/>\n");
            }
            di.append("      </bpmndi:BPMNEdge>\n");
            edges++;
        }
        di.append("    </bpmndi:BPMNPlane>\n  </bpmndi:BPMNDiagram>\n");

        String text = removeDiagram(bpmnXml);
        int close = text.lastIndexOf("</");
        if (close < 0) {
            throw new IllegalArgumentException("The BPMN has no closing tag.");
        }
        String body = text.substring(0, close) + di + text.substring(close);
        return withHeader(body, sourceLabel, rects.size(), edges);
    }

    private static String removeDiagram(String text) {
        java.util.regex.Matcher start = java.util.regex.Pattern.compile("[ \\t]*<([A-Za-z0-9_.-]+:)?BPMNDiagram[\\s>/]").matcher(text);
        if (!start.find()) {
            return text;
        }
        java.util.regex.Matcher end = java.util.regex.Pattern.compile("</([A-Za-z0-9_.-]+:)?BPMNDiagram>[ \\t]*\\r?\\n?").matcher(text);
        int from = start.start();
        if (!end.find(from)) {
            return text;
        }
        return text.substring(0, from) + text.substring(end.end());
    }

    private static List<double[]> waypoints(Rect s, Rect t) {
        List<double[]> p = new ArrayList<>();
        if (t.x() >= s.right()) {
            p.add(new double[] {s.right(), s.cy()});
            if (Math.abs(s.cy() - t.cy()) > 0.0001) {
                double mx = (s.right() + t.x()) / 2;
                p.add(new double[] {mx, s.cy()});
                p.add(new double[] {mx, t.cy()});
            }
            p.add(new double[] {t.x(), t.cy()});
        } else if (t.right() <= s.x()) {
            double y = Math.max(s.bottom(), t.bottom()) + BACK_EDGE_DROP;
            p.add(new double[] {s.cx(), s.bottom()});
            p.add(new double[] {s.cx(), y});
            p.add(new double[] {t.cx(), y});
            p.add(new double[] {t.cx(), t.bottom()});
        } else if (t.y() >= s.bottom()) {
            double my = (s.bottom() + t.y()) / 2;
            p.add(new double[] {s.cx(), s.bottom()});
            p.add(new double[] {s.cx(), my});
            p.add(new double[] {t.cx(), my});
            p.add(new double[] {t.cx(), t.y()});
        } else if (t.bottom() <= s.y()) {
            double my = (s.y() + t.bottom()) / 2;
            p.add(new double[] {s.cx(), s.y()});
            p.add(new double[] {s.cx(), my});
            p.add(new double[] {t.cx(), my});
            p.add(new double[] {t.cx(), t.bottom()});
        } else {
            p.add(new double[] {s.cx(), s.cy()});
            p.add(new double[] {t.cx(), t.cy()});
        }
        return p;
    }

    // ------------------------------------------------------------------------------------------------- header

    private String withHeader(String body, String sourceLabel, int shapes, int edges) {
        String version = "";
        String hash = "";
        NodeList releases = doc.getElementsByTagNameNS(ORQIX_NS, "release");
        if (releases.getLength() > 0) {
            Element r = (Element) releases.item(0);
            version = r.getAttribute("compilerVersion");
            hash = r.getAttribute("semanticHash");
        }
        TreeSet<String> elements = new TreeSet<>();
        TreeSet<String> orqixAttributes = new TreeSet<>();
        TreeSet<String> flowableAttributes = new TreeSet<>();
        scan(doc.getDocumentElement(), elements, orqixAttributes, flowableAttributes);

        StringBuilder h = new StringBuilder("<!--\n");
        h.append("  Exported from Orqix by the BPMN export (STU04). Standard BPMN 2.0 with diagram interchange (BPMNDI).\n");
        h.append("  Orqix compiler version: ").append(safe(version)).append('\n');
        h.append("  Source: ").append(safe(sourceLabel)).append('\n');
        h.append("  Semantic hash (SHA-256 of the canonical workflow): ").append(safe(hash)).append('\n');
        h.append("  Diagram: ").append(shapes).append(" shapes and ").append(edges).append(" edges, one for every element; ")
                .append(saved ? "step positions are the saved canvas positions.\n"
                        : "no complete set of canvas positions was saved, so the left-to-right layout was computed.\n");
        h.append("  Orqix settings that BPMN cannot express are carried only as extensions, never in standard elements:\n");
        h.append("    - elements in the namespace ").append(ORQIX_NS).append(" (prefix orqix): ")
                .append(elements.isEmpty() ? "none" : String.join(", ", elements)).append('\n');
        h.append("    - attributes in the namespace ").append(ORQIX_NS).append(": ")
                .append(orqixAttributes.isEmpty() ? "none" : String.join(", ", orqixAttributes)).append('\n');
        h.append("    - attributes in the Flowable namespace ").append(FLOWABLE_NS).append(" (prefix flowable): ")
                .append(flowableAttributes.isEmpty() ? "none" : String.join(", ", flowableAttributes)).append('\n');
        h.append("  Other modellers ignore these; saving the file there can remove them. Not carried at all: form fields and\n");
        h.append("  layout of the form, access rules, triggers, decision tables (separate DMN files in a release export),\n");
        h.append("  connection settings and secrets.\n");
        h.append("-->\n");
        int declaration = body.indexOf("?>");
        if (body.startsWith("<?xml") && declaration > 0) {
            int after = declaration + 2;
            while (after < body.length() && (body.charAt(after) == '\n' || body.charAt(after) == '\r')) {
                after++;
            }
            return body.substring(0, declaration + 2) + "\n" + h + body.substring(after);
        }
        return h + body;
    }

    private static void scan(Element e, Set<String> elements, Set<String> orqixAttributes, Set<String> flowableAttributes) {
        if (ORQIX_NS.equals(e.getNamespaceURI())) {
            elements.add("orqix:" + e.getLocalName());
        }
        NamedNodeMap attributes = e.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr a = (Attr) attributes.item(i);
            if (ORQIX_NS.equals(a.getNamespaceURI())) {
                orqixAttributes.add("orqix:" + a.getLocalName());
            } else if (FLOWABLE_NS.equals(a.getNamespaceURI())) {
                flowableAttributes.add("flowable:" + a.getLocalName());
            }
        }
        for (Element child : children(e)) {
            scan(child, elements, orqixAttributes, flowableAttributes);
        }
    }

    /** Header text must be comment-safe: no double hyphen and nothing that could end the comment. */
    private static String safe(String value) {
        return value == null ? "" : value.replace("--", "- -").replace(">", "&gt;").replace("<", "&lt;")
                .replaceAll("[\\r\\n]", " ");
    }

    private static String esc(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String num(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            long l = (long) value;
            return Long.toString(l);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
