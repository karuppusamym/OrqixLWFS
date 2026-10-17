# ADR 0003 - Pega scope for the minimum product demo

Status: **Accepted**. Date: 17 October 2026. The user delegated this decision and explicitly permitted an independent model review. This closes the scope decision and removes MIG11 from the minimum-demo blockers; it does not mark native RAP conversion implemented.

## Decision

The minimum Pega demo uses the delivered MIG10 and MIG13 capabilities:

1. **Automatic bounded flow conversion:** upload XPDL 2.0, derive target nodes/routing/simple inputs, review group and approval mappings, compile, publish separately and execute target cases. No hand-authored canonical target is submitted.
2. **Archive assessment and reconstruction:** inventory a ZIP/RAP, retain its checksum and source findings, and use an explicitly reviewed mapping package for unsupported native rules. Synthetic archive-to-target acceptance is retained as evidence.
3. **Visible refusal:** unsupported source behavior produces reasons and cannot create a misleading partial executable workflow.

Genuine Pega exports are **not required for this synthetic local demo**. They remain required for claims about a customer export, source release compatibility, Pega rule resolution and source-runtime equivalence. Demonstration artifacts must be labelled synthetic.

**MIG11 is deferred outside the minimum-demo scope.** It remains future engineering for direct native RAP rule pages, inheritance, ruleset resolution, circumstances and associated integration semantics. It must not appear as completed functionality or a current prerequisite for the local demo. AC01/EXT01 remain customer/source qualification gates, not local-demo blockers.

## Basis and alternatives

The independent read-only review confirmed that XPDL work is now present in the current branch (`e7a9d8c`, renumbered by `46ec063`), and that native archive conversion remains blocked in `MigrationService`. Synthetic fixture success cannot establish an authentic native RAP grammar. Requiring unavailable customer exports would hold the demo open without adding engineering evidence; relabelling synthetic XML as a genuine native export would misstate the product.

This decision reuses existing evidence: MIG10's 105/105 synthetic reviewed-target Docker checks and MIG13's 30 focused backend, 21 frontend and 88 contract checks. These were recorded at their stated checkpoints, not rerun by this documentation decision and not proof that the latest merged tree is demo-ready.

## Remaining release gates

The Pega feature scope is settled. A demo candidate still needs a build of the merged tree with its migrations, a running local Docker application/identity stack, seeded personas and a real-browser rehearsal of [the Pega demo](../demo-pega-migration.md). Any failures remain release blockers. No production infrastructure work or customer-system access is added by this decision.
