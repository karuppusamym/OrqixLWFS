/**
 * AI assistance (S10): workflow draft generation from a description, plain-language explanation, cost-controlled
 * model policy (profiles, excluded models, price caps, budgets) and the OpenRouter provider adapter. AI never
 * publishes, never creates groups, connections or credentials and never executes anything; every draft passes the
 * compiler and is reviewed by a person. Disabled (rules-only product) when no provider key is configured.
 */
@ApplicationModule(displayName = "AI",
        allowedDependencies = {"shared", "identity", "catalog", "compiler", "integration", "audit"})
package com.orqix.ai;

import org.springframework.modulith.ApplicationModule;
