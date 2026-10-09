package com.orqix.migration.camunda;

/** XML namespaces the adapter recognises. */
public final class Namespaces {

    public static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    public static final String BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI";
    public static final String CAMUNDA7 = "http://camunda.org/schema/1.0/bpmn";
    public static final String ZEEBE = "http://camunda.org/schema/zeebe/1.0";
    public static final String MODELER = "http://camunda.org/schema/modeler/1.0";
    public static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";

    private Namespaces() {
    }

    public static String prefixed(String ns, String local) {
        return switch (ns) {
            case BPMN -> local;
            case CAMUNDA7 -> "camunda:" + local;
            case ZEEBE -> "zeebe:" + local;
            case BPMNDI -> "bpmndi:" + local;
            default -> ns.isEmpty() ? local : "{" + ns + "}" + local;
        };
    }
}
