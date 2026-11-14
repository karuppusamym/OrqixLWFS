package com.orqix.catalog.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.compiler.Expressions;
import com.orqix.identity.CallerResolver;
import com.orqix.identity.Permission;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceAccess;
import com.orqix.shared.problem.ApiException;
import com.orqix.shared.problem.ProblemCode;

import tools.jackson.databind.JsonNode;

/**
 * Expressions (CG-X01, docs/build/S137): the server's own check and evaluation of an Orqix expression with sample
 * values, for the guided editor's preview. Pure: nothing is read or written besides the access check.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/expressions")
public class ExpressionController {

    /** Request body of evaluateExpression. */
    public record EvaluateRequest(String expression, JsonNode types, JsonNode values, JsonNode calendar, Instant now) {
    }

    /** The problem the expression has: code, business message and zero-based offset. */
    public record Problem(String code, String message, int position) {
    }

    /** Result of evaluateExpression; {@code value} only when the expression is valid and values were given. */
    public record EvaluateResult(boolean valid, String type, String typeDescription, List<String> fieldRefs,
            JsonNode value, Problem problem) {
    }

    private final CallerResolver callers;
    private final PolicyService policy;

    public ExpressionController(CallerResolver callers, PolicyService policy) {
        this.callers = callers;
        this.policy = policy;
    }

    @PostMapping("/evaluate")
    public EvaluateResult evaluateExpression(@PathVariable UUID workspaceId, @RequestBody EvaluateRequest request) {
        WorkspaceAccess access = policy.requireVisible(callers.current(), workspaceId);
        policy.require(access, Permission.WORKFLOW_EDIT, "evaluateExpression", false);
        if (request == null || request.expression() == null || request.expression().length() > 2000) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Send an expression of at most 2000 characters.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/expression", "maxLength",
                            "Send an expression of at most 2000 characters.")));
        }
        Expressions.Preview p;
        try {
            p = Expressions.preview(request.expression(), request.types(), request.values(), request.calendar(),
                    request.now() == null ? Instant.now() : request.now());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The types or the calendar are not valid.")
                    .withFieldErrors(List.of(new ApiException.FieldError("/types", "type", e.getMessage())));
        }
        return new EvaluateResult(p.valid(), p.type(), p.typeDescription(), p.fieldRefs(), p.value(),
                p.problemCode() == null ? null : new Problem(p.problemCode(), p.problemMessage(), p.problemPosition()));
    }
}
