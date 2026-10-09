package com.orqix.migration.pega;

import java.io.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import com.orqix.migration.camunda.*;
import com.orqix.shared.json.Json;
import tools.jackson.databind.JsonNode;

/** Native Pega archives are inventoried, never interpreted as executable BPMN or run as source code. */
public final class PegaSource {
    public static final String ADAPTER = "pega-review-0.1";
    public static Report.CompatibilityReport archive(byte[] bytes) {
        List<Report.Issue> issues = new ArrayList<>(); Set<String> names = new HashSet<>();
        long expanded = 0;
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw SourceException.invalid("Expected a Pega ZIP/RAP archive.");
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName().replace('\\', '/');
                if (name.length() > 500 || name.startsWith("/") || name.contains(":") || Arrays.asList(name.split("/")).contains("..")
                        || !names.add(name)) throw SourceException.invalid("Unsafe or duplicate archive path.");
                if (names.size() > 200) throw new SourceException("Archive exceeds 200 entries.", true);
                long size = 0; byte[] buffer = new byte[8192]; int read;
                while ((read = zip.read(buffer)) != -1) {
                    size += read; expanded += read;
                    if (size > 1_048_576 || expanded > 4_194_304) throw new SourceException("Archive exceeds extraction limits.", true);
                }
                if (!entry.isDirectory()) issues.add(new Report.Issue(name, name, "archiveEntry", Report.BLOCKER,
                        "PEGA_RULE_REQUIRES_MAPPING", "Native Pega rules/data require explicit target mapping and behavior verification.",
                        "Prepare an Orqix Pega mapping package for this archive; preserve each file disposition.", null, Report.OPEN, name));
            }
        } catch (IOException e) { throw SourceException.invalid("The archive is corrupt or unsupported."); }
        if (issues.isEmpty()) throw SourceException.invalid("The archive contains no files.");
        return report(null, Json.sha256Hex(bytes), issues, false);
    }

    public static Report.CompatibilityReport mapping(JsonNode packageNode, Report.CompatibilityReport archive, boolean acknowledged) {
        if (!"orqix-pega-mapping-0.1".equals(packageNode.path("format").asString())
                || !archive.sourceHash().equals(packageNode.path("sourceArchiveHash").asString())
                || !packageNode.path("definition").isObject() || packageNode.path("sourceVersion").asString("").isBlank()) {
            throw SourceException.invalid("Mapping package needs its format, Pega version, matching archive checksum and canonical definition.");
        }
        Set<String> targetNodes = new HashSet<>();
        packageNode.path("definition").path("nodes").forEach(n -> targetNodes.add(n.path("id").asString()));
        Set<String> mappedTargets = new HashSet<>(); List<Report.Issue> issues = new ArrayList<>();
        var dispositions = packageNode.path("fileMappings");
        Set<String> knownFiles = new HashSet<>(archive.issues().stream().map(Report.Issue::sourceElementId).toList());
        if (!dispositions.isObject() || !knownFiles.containsAll(dispositions.propertyNames())) throw SourceException.invalid("Unknown or missing archive file mappings.");
        for (var original : archive.issues()) {
            var mapping = dispositions.path(original.sourceElementId());
            boolean excluded = "excluded".equals(mapping.path("disposition").asString());
            boolean mapped = "mapped".equals(mapping.path("disposition").asString()) && mapping.path("targetNodeIds").isArray()
                    && !mapping.path("targetNodeIds").isEmpty();
            String first = null;
            if (mapped) for (var target : mapping.path("targetNodeIds")) {
                String id = target.asString("");
                if (!targetNodes.contains(id)) throw SourceException.invalid("A file mapping references an unknown target node.");
                mappedTargets.add(id); if (first == null) first = id;
            }
            if ((!excluded && !mapped) || mapping.path("explanation").asString("").isBlank()) {
                issues.add(original); continue;
            }
            issues.add(new Report.Issue(original.sourceElementId(), original.sourceName(), "archiveEntry", Report.INFO,
                    excluded ? "PEGA_FILE_EXCLUDED" : "PEGA_FILE_MANUALLY_MAPPED", mapping.path("explanation").asString(),
                    "Review the manual mapping and compare source/target behavior before publishing.", first,
                    acknowledged ? Report.ACKNOWLEDGED : Report.OPEN, original.sourcePath()));
        }
        if (!mappedTargets.containsAll(targetNodes)) throw SourceException.invalid("Every target node needs source provenance in fileMappings.");
        return report(packageNode.path("sourceVersion").asString(), archive.sourceHash(), issues,
                issues.stream().allMatch(i -> Report.ACKNOWLEDGED.equals(i.resolutionStatus())));
    }

    private static Report.CompatibilityReport report(String version, String hash, List<Report.Issue> issues, boolean ready) {
        return new Report.CompatibilityReport("pega", version, ADAPTER, hash, null, null, Report.ElementCounts.of(issues), issues,
            List.of("Archive inventory and manual mappings do not establish Pega rule-resolution or case-behavior equivalence.",
                    "No live cases, history, identities, credentials or attachments are migrated.",
                    "Validate routing, stages, forms, SLA/calendar behavior and integrations with the source owner."),
            new Report.RequiredMappings(List.of(), List.of(), List.of()), ready);
    }
}
