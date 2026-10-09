package com.orqix.runtime.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.impl.util.ProcessDefinitionUtil;

/**
 * Reads the Orqix extension elements (namespace {@value #NAMESPACE}) from Flowable's cached, parsed
 * {@link BpmnModel} (S4 section 6). Only usable inside an engine command.
 */
public final class OrqixModel {

    public static final String NAMESPACE = "https://orqix.example/bpmn/v0";

    private OrqixModel() {
    }

    public static BpmnModel model(String processDefinitionId) {
        BpmnModel model = ProcessDefinitionUtil.getBpmnModel(processDefinitionId);
        if (model == null) {
            throw new IllegalStateException("No BPMN model for " + processDefinitionId);
        }
        return model;
    }

    public static FlowElement flowElement(String processDefinitionId, String elementId) {
        FlowElement element = model(processDefinitionId).getFlowElement(elementId);
        if (element == null) {
            throw new IllegalStateException("No element " + elementId + " in " + processDefinitionId);
        }
        return element;
    }

    public static Optional<ExtensionElement> extension(BaseElement element, String name) {
        if (element == null || element.getExtensionElements() == null) {
            return Optional.empty();
        }
        List<ExtensionElement> values = element.getExtensionElements().get(name);
        if (values == null) {
            return Optional.empty();
        }
        return values.stream().filter(e -> NAMESPACE.equals(e.getNamespace())).findFirst();
    }

    public static ExtensionElement requireExtension(BaseElement element, String name) {
        return extension(element, name).orElseThrow(() -> new IllegalStateException(
                "Element " + element.getId() + " lacks orqix:" + name));
    }

    public static String attribute(ExtensionElement element, String name) {
        String value = element.getAttributeValue(null, name);
        if (value == null) {
            value = element.getAttributeValue(NAMESPACE, name);
        }
        return value;
    }

    public static List<ExtensionElement> children(ExtensionElement element, String name) {
        List<ExtensionElement> values = element.getChildElements().get(name);
        return values == null ? List.of() : values;
    }

    /** {@code orqix:input name=... fieldRef=...} / {@code orqix:result name=... pointer=...} children as an ordered map. */
    public static Map<String, String> childMap(ExtensionElement element, String childName, String valueAttribute) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ExtensionElement child : children(element, childName)) {
            map.put(attribute(child, "name"), attribute(child, valueAttribute));
        }
        return map;
    }
}
