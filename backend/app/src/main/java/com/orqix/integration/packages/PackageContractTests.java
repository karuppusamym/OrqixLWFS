package com.orqix.integration.packages;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.orqix.audit.AuditWriter;
import com.orqix.identity.Caller;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.junit.JUnitXml;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

/**
 * CG-Q04 (S148) connector package contract tests: a package archive (a release candidate, typically built in CI) is
 * checked as on import and every fixture is replayed through the runtime's response handling
 * ({@code HttpExecutor.replay}). Nothing is imported, stored, installed or called; the archive does not need a trusted
 * signature (its state is reported). Needs {@code connector.manage}, like an import. The live smoke run against a
 * sandbox binding is the existing {@code testConnection} (read-only operations only), driven by {@code orqix package test --smoke}.
 */
@Service
public class PackageContractTests {

    public record Request(String archive) {
    }

    public record Problem(String file, String pointer, String code, String message) {
    }

    public record Report(String packageId, String version, String sha256, String signatureStatus, boolean passed,
            int fixtures, int fixturesPassed, List<PackageValidator.FixtureResult> results, List<Problem> problems,
            String junitXml) {
    }

    private final PolicyService policy;
    private final PackageValidator validator;
    private final PackageSettings settings;
    private final AuditWriter audit;

    public PackageContractTests(PolicyService policy, PackageValidator validator, PackageSettings settings,
            AuditWriter audit) {
        this.policy = policy;
        this.validator = validator;
        this.settings = settings;
        this.audit = audit;
    }

    public Report run(Caller caller, UUID ws, Request request) {
        WorkspaceAccess access = policy.requireVisible(caller, ws);
        policy.require(access, Permission.CONNECTOR_MANAGE, "testConnectorPackageContract", true);
        if (request == null || request.archive() == null || request.archive().isBlank()) {
            throw ConnectorPackageService.invalid("/archive", "required", "Send the .orqpkg file in base64.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.archive().strip());
        } catch (IllegalArgumentException e) {
            throw ApiException.malformed("archive must be the package file in base64.");
        }
        PackageArchive.Archive archive;
        try {
            archive = PackageArchive.read(bytes);
        } catch (PackageArchive.Invalid e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The package archive cannot be read.")
                    .withFieldErrors(List.of(new ApiException.FieldError(e.path() == null ? "/archive"
                            : "/files/" + e.path(), e.code(), e.getMessage())));
        }
        PackageArchive.Verification signature = PackageArchive.verify(archive, settings);
        PackageValidator.ContractTest test = validator.contractTest(archive);
        List<Problem> problems = new ArrayList<>();
        test.problems().stream().limit(50)
                .forEach(p -> problems.add(new Problem(p.file(), p.pointer(), p.code(), p.message())));
        if (signature.state() == PackageArchive.SignatureState.INVALID) {
            problems.add(new Problem(PackageArchive.SIGNATURE, "", "PKG_SIGNATURE_INVALID",
                    "The signature does not match the package."));
        }
        String packageId = test.manifest() == null ? null : test.manifest().path("id").asString(null);
        String version = test.manifest() == null ? null : test.manifest().path("version").asString(null);
        int passedFixtures = (int) test.fixtures().stream().filter(PackageValidator.FixtureResult::passed).count();
        boolean passed = problems.isEmpty() && passedFixtures == test.fixtures().size();
        String status = signature.state().name().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (packageId != null) {
            metadata.put("packageId", packageId);
        }
        if (version != null) {
            metadata.put("version", version);
        }
        metadata.put("sha256", archive.sha256());
        metadata.put("fixtures", test.fixtures().size());
        metadata.put("passed", passed);
        audit.write(new AuditWriter.Entry(ws, AuditWriter.ActorType.USER, caller.subjectId(), "connector.package-tested",
                "connector_package", packageId == null ? archive.sha256() : packageId, AuditWriter.Outcome.SUCCESS, metadata));
        return new Report(packageId, version, archive.sha256(), status, passed, test.fixtures().size(), passedFixtures,
                test.fixtures(), List.copyOf(problems), junit(packageId, version, test.fixtures(), problems));
    }

    static String junit(String packageId, String version, List<PackageValidator.FixtureResult> fixtures,
            List<Problem> problems) {
        String name = (packageId == null ? "package" : packageId) + (version == null ? "" : "@" + version);
        String className = "orqix.package." + (packageId == null ? "unknown" : packageId);
        List<JUnitXml.Case> cases = new ArrayList<>();
        if (problems.isEmpty()) {
            cases.add(JUnitXml.Case.passed(className, "Package checks"));
        } else {
            StringBuilder detail = new StringBuilder();
            problems.forEach(p -> detail.append(p.file()).append(p.pointer()).append(" ").append(p.code()).append(": ")
                    .append(p.message()).append('\n'));
            cases.add(JUnitXml.Case.failed(className, "Package checks", problems.size() + " problem"
                    + (problems.size() == 1 ? "" : "s") + ": " + problems.get(0).message(), detail.toString()));
        }
        for (PackageValidator.FixtureResult f : fixtures) {
            String caseName = f.path() + (f.operation() == null ? "" : " (" + f.connection() + "." + f.operation() + ")");
            cases.add(f.passed() ? JUnitXml.Case.passed(className, caseName)
                    : JUnitXml.Case.failed(className, caseName, f.message() == null ? "The fixture failed." : f.message(),
                            f.outcome() == null ? "" : "Replayed outcome: " + f.outcome()
                                    + (f.code() == null ? "" : " (" + f.code() + ")")));
        }
        return JUnitXml.write(name, List.of(), cases);
    }
}
