/**
 * Application pages at runtime (UI02 / S111): routes that pin one exact published page version, the page payload,
 * server-filtered extension data and composed action chains. Chains call the same task, request and case services as
 * the public operations, so every step is authorized again. Nothing depends on this module.
 */
@ApplicationModule(displayName = "Apps",
        allowedDependencies = {"shared", "identity", "audit", "catalog", "runtime", "cases"})
package com.orqix.apps;

import org.springframework.modulith.ApplicationModule;
