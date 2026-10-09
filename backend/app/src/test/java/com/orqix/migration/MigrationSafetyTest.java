package com.orqix.migration;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import com.orqix.migration.camunda.*;
import com.orqix.migration.pega.PegaSource;
import com.orqix.shared.json.Json;
import tools.jackson.databind.json.JsonMapper;

class MigrationSafetyTest {
    @Test void pegaMappingRequiresAReviewedVersionAndConsistentFileProvenance() throws Exception {
        var json = new Json(JsonMapper.builder().build());
        var source = PegaSource.archive(archive("rules.xml", "synthetic".getBytes(StandardCharsets.UTF_8)));
        var pkg = json.object(); pkg.put("format", "orqix-pega-mapping-0.1"); pkg.put("sourceArchiveHash", source.sourceHash());
        pkg.putObject("definition").putArray("nodes").addObject().put("id", "start");
        var mapping = pkg.putObject("fileMappings").putObject("rules.xml");
        mapping.put("disposition", "mapped"); mapping.putArray("targetNodeIds").add("start"); mapping.put("explanation", "Reviewed synthetic mapping.");
        pkg.put("sourceVersion", "ENTER-PEGA-VERSION");
        assertThatThrownBy(() -> PegaSource.mapping(pkg, source, true)).isInstanceOf(SourceException.class);
        pkg.put("sourceVersion", 24);
        assertThatThrownBy(() -> PegaSource.mapping(pkg, source, true)).isInstanceOf(SourceException.class);
        pkg.put("sourceVersion", "synthetic-24");
        assertThat(PegaSource.mapping(pkg, source, true).publishable()).isTrue();
        mapping.put("disposition", "excluded");
        assertThatThrownBy(() -> PegaSource.mapping(pkg, source, true)).isInstanceOf(SourceException.class).hasMessageContaining("Excluded files");
    }
    private byte[] archive(String name,byte[] content) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry(name)); zip.write(content); zip.closeEntry(); }
        return bytes.toByteArray();
    }
    @Test void rejectsTraversalAndOversizedExpandedArchiveEntries() throws Exception {
        byte[] traversal=archive("../rules.xml",new byte[0]);
        assertThatThrownBy(()->PegaSource.archive(traversal)).isInstanceOf(SourceException.class).hasMessageContaining("Unsafe");
        byte[] bomb=archive("rules.xml",new byte[1_048_577]);
        assertThatThrownBy(()->PegaSource.archive(bomb)).isInstanceOf(SourceException.class).hasMessageContaining("extraction limits");
        assertThatThrownBy(()->PegaSource.archive("not a zip".getBytes(StandardCharsets.UTF_8))).isInstanceOf(SourceException.class);
    }
    @Test void unsupportedSourceBehaviorCannotBeAcknowledgedIntoExecutableSupport() {
        String xml="""
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test">
             <process id="unsupported" isExecutable="true"><startEvent id="start"/><scriptTask id="script"/><endEvent id="end"/>
              <sequenceFlow id="first" sourceRef="start" targetRef="script"/><sequenceFlow id="last" sourceRef="script" targetRef="end"/>
             </process></definitions>
            """;
        var a=Analyzer.analyze(xml,Limits.DEFAULT);
        assertThat(a.items().stream().map(Analysis.Item::issue)).anyMatch(i->i.reasonCode().equals("TASK_TYPE_NOT_SUPPORTED"));
        assertThat(Converter.report(a,a.items().stream().map(Analysis.Item::issue).toList()).publishable()).isFalse();
    }
    @Test void rejectsXmlEntitiesAndEnforcesXmlDepthAndElementLimits() {
        assertThatThrownBy(()->SafeXml.parse("<!DOCTYPE x [<!ENTITY x SYSTEM 'http://localhost/'>]><x>&x;</x>",Limits.DEFAULT)).isInstanceOf(SourceException.class);
        assertThatThrownBy(()->SafeXml.parse("<a><b><c/></b></a>",new Limits(1024,10,2))).isInstanceOf(SourceException.class);
        assertThatThrownBy(()->SafeXml.parse("<a><b/><c/></a>",new Limits(1024,2,10))).isInstanceOf(SourceException.class);
    }
}
