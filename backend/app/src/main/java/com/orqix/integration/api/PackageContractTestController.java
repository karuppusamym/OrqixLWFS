package com.orqix.integration.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.identity.CallerResolver;
import com.orqix.integration.packages.PackageContractTests;

/** CG-Q04 (S148): replay the fixtures of a package archive without importing it. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class PackageContractTestController {

    private final CallerResolver callers;
    private final PackageContractTests tests;

    public PackageContractTestController(CallerResolver callers, PackageContractTests tests) {
        this.callers = callers;
        this.tests = tests;
    }

    @PostMapping("/connector-packages/contract-tests")
    public PackageContractTests.Report testConnectorPackageContract(@PathVariable UUID workspaceId,
            @RequestBody PackageContractTests.Request request) {
        return tests.run(callers.current(), workspaceId, request);
    }
}
