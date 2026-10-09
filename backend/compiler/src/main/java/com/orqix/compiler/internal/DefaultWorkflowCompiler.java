package com.orqix.compiler.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orqix.compiler.CompilationException;
import com.orqix.compiler.CompileContext;
import com.orqix.compiler.CompiledWorkflow;
import com.orqix.compiler.ValidationReport;
import com.orqix.compiler.WorkflowCompiler;
import com.orqix.compiler.internal.bpmn.BpmnGenerator;
import com.orqix.compiler.internal.dmn.DmnGenerator;
import com.orqix.compiler.internal.json.Jcs;
import com.orqix.compiler.internal.model.Enums.FieldKind;
import com.orqix.compiler.internal.model.Node;
import com.orqix.compiler.internal.model.WorkflowDefinition;
import com.orqix.compiler.internal.model.WorkflowDefinition.DecisionTable;
import com.orqix.compiler.internal.model.WorkflowDefinition.Dependency;
import com.orqix.compiler.internal.rules.Connections;
import com.orqix.compiler.internal.rules.FieldTypes;
import com.orqix.compiler.internal.rules.RuleContext;
import com.orqix.compiler.internal.schema.SchemaValidation;

/** Default {@link WorkflowCompiler}: stages 1-7 via {@link Pipeline}, stage 8 via the BPMN/DMN generators. */
public final class DefaultWorkflowCompiler implements WorkflowCompiler {

    /**
     * Semantic version of the generated output (S4 section 9): minor for new constructs, patch for formatting-only
     * changes. Changing it changes the golden files ({@code orqix:release compilerVersion}).
     */
    public static final String VERSION = "0.1.0";

    private final Pipeline pipeline;

    public DefaultWorkflowCompiler() {
        this(new SchemaValidation());
    }

    public DefaultWorkflowCompiler(SchemaValidation schemas) {
        this.pipeline = new Pipeline(VERSION, schemas);
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public ValidationReport validate(String definitionJson, CompileContext context) {
        return pipeline.run(definitionJson, context).report();
    }

    @Override
    public CompiledWorkflow compile(String definitionJson, CompileContext context) throws CompilationException {
        Pipeline.Outcome outcome = pipeline.run(definitionJson, context);
        ValidationReport report = outcome.report();
        if (!report.publishable()) {
            throw new CompilationException(report);
        }
        WorkflowDefinition d = outcome.definition();
        RuleContext analysis = outcome.analysis();

        Set<String> referenced = new LinkedHashSet<>();
        d.nodes().forEach(n -> {
            if (n instanceof Node.Decision dn) {
                referenced.add(dn.decisionRef());
            }
        });
        Map<String, String> resourceByKey = new LinkedHashMap<>();
        Map<String, String> dmnResources = new LinkedHashMap<>();
        Map<String, String> dmnHashes = new LinkedHashMap<>();
        List<CompiledWorkflow.DecisionRef> decisions = new ArrayList<>();
        Set<String> seenTables = new LinkedHashSet<>();
        for (DecisionTable table : d.decisions()) {
            if (!referenced.contains(table.key()) || !seenTables.add(table.key())) {
                continue;
            }
            String resource = DmnGenerator.resourceName(table.key());
            String xml = DmnGenerator.generate(table, inputKind(analysis, table));
            resourceByKey.put(table.key(), resource);
            dmnResources.put(resource, xml);
            dmnHashes.put(resource, Jcs.sha256Hex(xml));
            decisions.add(new CompiledWorkflow.DecisionRef(table.key(), table.version()));
        }

        BpmnGenerator.Result bpmn = BpmnGenerator.generate(d, VERSION, report.semanticHash(), resourceByKey);

        Set<String> usedConnections = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        for (Node n : d.nodes()) {
            if (n instanceof Node.Http h) {
                usedConnections.add(h.connectionRef());
            } else if (n instanceof Node.Approval a) {
                groups.add(a.candidateGroupRef());
            }
        }
        List<CompiledWorkflow.ConnectionRef> connections = new ArrayList<>();
        for (Dependency dep : d.dependencies()) {
            Connections.Pinned pinned = analysis.connections().pinned(dep.key());
            if (usedConnections.contains(dep.key()) && pinned.dependency() == dep) {
                connections.add(new CompiledWorkflow.ConnectionRef(dep.key(), dep.definitionVersion(),
                        pinned.version().definitionHash()));
            }
        }
        return new CompiledWorkflow(
                VERSION,
                d.workflowKey(),
                bpmn.xml(),
                Collections.unmodifiableMap(dmnResources),
                bpmn.sourceMap().toJson(),
                outcome.sourceHash(),
                report.semanticHash(),
                Jcs.sha256Hex(bpmn.xml()),
                Collections.unmodifiableMap(dmnHashes),
                report.warnings(),
                List.copyOf(decisions),
                List.copyOf(connections),
                List.copyOf(groups));
    }

    private static FieldKind inputKind(RuleContext analysis, DecisionTable table) {
        if (analysis.types().resolve(table.rows().get(0).when().fieldRef()) instanceof FieldTypes.Resolved r) {
            return r.type().kind();
        }
        return switch (table.rows().get(0).when().value().typeName()) {
            case "string" -> FieldKind.STRING;
            case "boolean" -> FieldKind.BOOLEAN;
            default -> FieldKind.NUMBER;
        };
    }
}
