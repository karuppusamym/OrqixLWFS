/** OIDC login, subjects, policy/authorization and the access administration API. Never calls Flowable. */
@ApplicationModule(displayName = "Identity", allowedDependencies = {"shared", "audit"})
package com.orqix.identity;

import org.springframework.modulith.ApplicationModule;
