package com.orqix.migration;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import com.orqix.migration.camunda.*;
import com.orqix.migration.pega.PegaSource;

class MigrationSafetyTest {
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
