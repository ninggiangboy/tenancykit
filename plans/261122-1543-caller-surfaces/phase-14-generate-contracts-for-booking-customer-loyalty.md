---
phase: 14
title: "Generate contracts for Booking, Customer and Loyalty"
status: pending
priority: P2
effort: 4d
dependencies: [6]
---

# Phase 14: Generate contracts for Booking, Customer and Loyalty

## Goal
Booking, Customer and Loyalty generate `contracts/openapi/<app>.yaml` from their controllers exactly as Survey,
Mailroom, Attribution, Workflow, Billing, Notification and Platform do, so all 10 documents (and the caller labels,
prefixes and per-caller files derived from them) come from code and nothing about callers is typed by hand.

## What is already true (verified)
- All three apps already have `springdoc` and `core-openapi` on the classpath and already serve a generated
  `/v3/api-docs.yaml` (`build.gradle.kts` of each `app-boot`; Booking and Customer already pass `openapi.update`).
- Their `OpenApiContractTest` today only proves the served operations are a subset of the hand-written YAML and that
  each names a catalogued permission, plus (Loyalty) the server prefix and the Scalar page. The committed YAML is the
  source, which is why it was "written ahead of the phases".
- The generated services use a test that writes the committed file under `-Dopenapi.update=true` and compares
  otherwise (`backend/services/apps/survey/apps/app-boot/src/test/java/dev/ngb/survey/contract/OpenApiContractTest.java`
  is the model).

## Files to Create / Modify
- Modify: `backend/services/apps/{booking,customer,loyalty}/apps/app-boot/src/test/java/dev/ngb/<app>/contract/OpenApiContractTest.java`
  (adopt the generated-contract pattern; drop the "served is a subset of documented" comparison and the phase 3 label
  comparison for these apps, since the labels and the "Callers / Requires" line are now generated)
- Create: `backend/services/apps/{booking,customer,loyalty}/apps/app-boot/src/main/resources/openapi-descriptions.conf`
  (field, parameter, header and body descriptions)
- Modify: the controllers of the three apps: `@Operation(summary, description)` and `@Tag(name, description)` carrying
  the prose that today lives only in the YAML
- Modify: each app's web configuration to declare its security schemes through the shared customizer (Booking has
  `SuiteToken` and `CustomerToken`; the customer token belongs to the public gate)
- Replace: `contracts/openapi/{booking,customer,loyalty}.yaml` with the generated output, and generate
  `contracts/openapi/<caller>/{booking,customer,loyalty}.yaml`
- Modify: `contracts/README.md` (these three move from the "written by hand" sentence to the generated list)

## Tasks & Steps
1. For each app, snapshot the current hand-written YAML (`git show` copy outside the repo) as the reference.
2. Move the prose into annotations and `openapi-descriptions.conf`, one resource at a time, regenerating as you go and
   diffing against the reference. Descriptions are the real cost: Booking has about 144 operations.
3. Preserve what clients depend on: path set, operation ids, schemas and field names, status codes, `x-required-permission`.
   A deliberate change is allowed only if recorded in the PR description with the reason.
4. Swap the contract test to the generated pattern; run it with `-Dopenapi.update=true`; review the diff against the
   reference; commit the generated file.
5. Run the label rules from phase 3 and the prefix checks from phase 5 against the new documents; the three apps now
   behave like the other seven.
6. Delete the hand-written-contract comparison code and any helper only it used.

## Verification
- `./gradlew test` in booking, customer and loyalty (the contract test passes without the update flag).
- A script comparing path set, operation ids and `x-required-permission` between the reference and the generated YAML
  reports no unexplained difference for each app.
- Phase 3's `CallerLabelRules` passes for all 10 documents without any hand-edited label.
- `make docs` shows these three apps in every caller page that applies to them.

## Risks
- Prose lost in migration: the diff against the reference is the guard; review every description change.
- Operation id churn breaks generated clients: no frontend client is generated for these three today (only Platform and
  Billing under `frontend/packages/api-client`); confirm in phase 1 before starting.
- Booking's public gate uses a customer token the generated document must still describe; check the security scheme.
- If the effort is too high, this phase can be dropped without affecting the others: phase 3's comparison test already
  keeps the hand-written files honest.
