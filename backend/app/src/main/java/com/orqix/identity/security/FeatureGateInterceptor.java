package com.orqix.identity.security;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.orqix.identity.CallerResolver;
import com.orqix.identity.PolicyService;
import com.orqix.identity.WorkspaceFeatures;
import com.orqix.shared.Ids;

/**
 * Enforces workspace feature switches (docs/build/S127) for every operation in
 * {@link WorkspaceFeatures#GATED_OPERATIONS}, matched by HTTP method and route (the contract path), before the controller
 * runs. Order: a caller who cannot see the workspace still gets 404; then 409 FEATURE_DISABLED; then the controller's
 * own permission check. Nothing is read or written by a refused call. Nothing else is touched, so reads and work that is
 * already running are never blocked here.
 */
@Configuration
public class FeatureGateInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final String API = "/api/v1";
    private static final java.util.regex.Pattern WORKSPACE_VARIABLE =
            java.util.regex.Pattern.compile("^/api/v1/workspaces/\\{([^}/]+)}");

    private final WorkspaceFeatures features;
    private final PolicyService policy;
    private final CallerResolver callers;

    public FeatureGateInterceptor(WorkspaceFeatures features, PolicyService policy, CallerResolver callers) {
        this.features = features;
        this.policy = policy;
        this.callers = callers;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/workspaces/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(pattern instanceof String template) || !template.startsWith(API)) {
            return true;
        }
        Set<WorkspaceFeatures.Feature> gated = WorkspaceFeatures.GATED_ROUTES.get(
                new WorkspaceFeatures.Gate(request.getMethod(), template.substring(API.length()), Set.of()).route());
        if (gated == null) {
            return true;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request
                .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        // The workspace is the first path variable, whatever the controller calls it.
        java.util.regex.Matcher name = WORKSPACE_VARIABLE.matcher(template);
        UUID workspaceId = variables == null || !name.find() ? null : Ids.parseOrNull(variables.get(name.group(1)));
        if (workspaceId == null) {
            return true;
        }
        Set<WorkspaceFeatures.Feature> off = features.disabled(workspaceId);
        for (WorkspaceFeatures.Feature feature : gated) {
            if (off.contains(feature)) {
                // Outsiders learn nothing about the workspace: the usual 404 comes first.
                policy.requireVisible(callers.current(), workspaceId);
                throw WorkspaceFeatures.refused(feature);
            }
        }
        return true;
    }
}
