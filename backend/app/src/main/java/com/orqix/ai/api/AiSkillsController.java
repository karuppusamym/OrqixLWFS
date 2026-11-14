package com.orqix.ai.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orqix.ai.skills.AiSkills;
import com.orqix.ai.tasks.AiEvaluations;
import com.orqix.ai.tasks.AiShadow;
import com.orqix.identity.CallerResolver;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** AI skills, evaluations, shadow mode, promotions and the AI policy (contracts/openapi.yaml tag AiSkills; S134). */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class AiSkillsController {

    private final CallerResolver callers;
    private final AiSkills skills;
    private final AiEvaluations evaluations;
    private final AiShadow shadow;

    public AiSkillsController(CallerResolver callers, AiSkills skills, AiEvaluations evaluations, AiShadow shadow) {
        this.callers = callers;
        this.skills = skills;
        this.evaluations = evaluations;
        this.shadow = shadow;
    }

    // ---------------------------------------------------------------- skills

    @GetMapping("/ai-skills")
    public ObjectNode listAiSkills(@PathVariable UUID workspaceId) {
        return skills.list(callers.current(), workspaceId);
    }

    @PostMapping("/ai-skills")
    public ResponseEntity<ObjectNode> createAiSkill(@PathVariable UUID workspaceId,
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(skills.create(callers.current(), workspaceId, body));
    }

    @GetMapping("/ai-skills/{skillKey}")
    public ObjectNode getAiSkill(@PathVariable UUID workspaceId, @PathVariable String skillKey) {
        return skills.get(callers.current(), workspaceId, skillKey);
    }

    @PostMapping("/ai-skills/{skillKey}/versions")
    public ResponseEntity<ObjectNode> createAiSkillVersion(@PathVariable UUID workspaceId, @PathVariable String skillKey,
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(skills.newVersion(callers.current(), workspaceId, skillKey, body));
    }

    @PutMapping("/ai-skills/{skillKey}/versions/{version}")
    public ObjectNode updateAiSkillVersion(@PathVariable UUID workspaceId, @PathVariable String skillKey,
            @PathVariable int version, @RequestBody(required = false) JsonNode body) {
        return skills.update(callers.current(), workspaceId, skillKey, version, body);
    }

    @PostMapping("/ai-skills/{skillKey}/versions/{version}/submit")
    public ObjectNode submitAiSkillVersion(@PathVariable UUID workspaceId, @PathVariable String skillKey,
            @PathVariable int version) {
        return skills.submit(callers.current(), workspaceId, skillKey, version);
    }

    @PostMapping("/ai-skills/{skillKey}/versions/{version}/approve")
    public ObjectNode approveAiSkillVersion(@PathVariable UUID workspaceId, @PathVariable String skillKey,
            @PathVariable int version, @RequestBody(required = false) JsonNode body) {
        return skills.approve(callers.current(), workspaceId, skillKey, version, body);
    }

    @PostMapping("/ai-skills/{skillKey}/versions/{version}/retire")
    public ObjectNode retireAiSkillVersion(@PathVariable UUID workspaceId, @PathVariable String skillKey,
            @PathVariable int version) {
        return skills.retire(callers.current(), workspaceId, skillKey, version);
    }

    // ---------------------------------------------------------------- evaluations

    @PostMapping("/ai-skills/{skillKey}/versions/{version}/evaluations")
    public ResponseEntity<ObjectNode> startAiEvaluation(@PathVariable UUID workspaceId, @PathVariable String skillKey,
            @PathVariable int version, @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(evaluations.start(callers.current(), workspaceId, skillKey, version, body));
    }

    @GetMapping("/ai-skills/{skillKey}/versions/{version}/evaluations")
    public ObjectNode listAiEvaluations(@PathVariable UUID workspaceId, @PathVariable String skillKey,
            @PathVariable int version) {
        return evaluations.list(callers.current(), workspaceId, skillKey, version);
    }

    @GetMapping("/ai-evaluations/{evaluationId}")
    public ObjectNode getAiEvaluation(@PathVariable UUID workspaceId, @PathVariable UUID evaluationId) {
        return evaluations.get(callers.current(), workspaceId, evaluationId);
    }

    @GetMapping("/ai-eval-suites")
    public ObjectNode listAiEvalSuites(@PathVariable UUID workspaceId) {
        return skills.listSuites(callers.current(), workspaceId);
    }

    @GetMapping("/ai-eval-suites/{suiteKey}")
    public ObjectNode getAiEvalSuite(@PathVariable UUID workspaceId, @PathVariable String suiteKey) {
        return skills.getSuite(callers.current(), workspaceId, suiteKey);
    }

    @PutMapping("/ai-eval-suites/{suiteKey}")
    public ObjectNode saveAiEvalSuite(@PathVariable UUID workspaceId, @PathVariable String suiteKey,
            @RequestBody(required = false) JsonNode body) {
        return skills.saveSuite(callers.current(), workspaceId, suiteKey, body);
    }

    // ---------------------------------------------------------------- shadow mode, promotion, drift

    @GetMapping("/ai-skills/{skillKey}/versions/{version}/shadow")
    public ObjectNode getAiShadowDashboard(@PathVariable UUID workspaceId, @PathVariable String skillKey,
            @PathVariable int version) {
        return shadow.dashboard(callers.current(), workspaceId, skillKey, version);
    }

    @PostMapping("/ai-skills/{skillKey}/versions/{version}/promotions")
    public ResponseEntity<ObjectNode> requestAiSkillPromotion(@PathVariable UUID workspaceId,
            @PathVariable String skillKey, @PathVariable int version, @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(shadow.requestPromotion(callers.current(), workspaceId, skillKey, version, body));
    }

    @GetMapping("/ai-skills/{skillKey}/promotions")
    public ObjectNode listAiSkillPromotions(@PathVariable UUID workspaceId, @PathVariable String skillKey) {
        return shadow.promotions(callers.current(), workspaceId, skillKey);
    }

    @PostMapping("/ai-skill-promotions/{promotionId}/decision")
    public ObjectNode decideAiSkillPromotion(@PathVariable UUID workspaceId, @PathVariable UUID promotionId,
            @RequestBody(required = false) JsonNode body) {
        return shadow.decidePromotion(callers.current(), workspaceId, promotionId, body);
    }

    @PostMapping("/ai-skills/{skillKey}/versions/{version}/drift-check")
    public ObjectNode checkAiSkillDrift(@PathVariable UUID workspaceId, @PathVariable String skillKey,
            @PathVariable int version) {
        return shadow.driftCheck(callers.current(), workspaceId, skillKey, version);
    }

    @PostMapping("/ai-skills/{skillKey}/versions/{version}/clear-drift")
    public ObjectNode clearAiSkillDrift(@PathVariable UUID workspaceId, @PathVariable String skillKey,
            @PathVariable int version, @RequestBody(required = false) JsonNode body) {
        return shadow.clearDrift(callers.current(), workspaceId, skillKey, version, body);
    }

    @GetMapping("/ai-review-queue")
    public ObjectNode listAiReviewQueue(@PathVariable UUID workspaceId) {
        return shadow.reviewQueue(callers.current(), workspaceId);
    }

    @GetMapping("/ai-eval-candidates")
    public ObjectNode listAiEvalCandidates(@PathVariable UUID workspaceId) {
        return shadow.candidates(callers.current(), workspaceId);
    }

    @PostMapping("/ai-eval-candidates/{candidateId}/review")
    public ObjectNode reviewAiEvalCandidate(@PathVariable UUID workspaceId, @PathVariable UUID candidateId,
            @RequestBody(required = false) JsonNode body) {
        return shadow.reviewCandidate(callers.current(), workspaceId, candidateId, body);
    }

    // ---------------------------------------------------------------- policy

    @GetMapping("/ai-policy")
    public ObjectNode getAiPolicy(@PathVariable UUID workspaceId) {
        return skills.getPolicy(callers.current(), workspaceId);
    }

    @PutMapping("/ai-policy")
    public ObjectNode updateAiPolicy(@PathVariable UUID workspaceId, @RequestBody(required = false) JsonNode body) {
        return skills.updatePolicy(callers.current(), workspaceId, body);
    }
}
