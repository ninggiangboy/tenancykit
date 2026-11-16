---
title: "Workflow app: scope, identity seams and skeleton"
date: 2026-11-16
summary: Executed phases 1-2 of the workflow plan and built the phase 3 skeleton; recorded the refund-scoping decision and the deferred webhook/compose work.
---

# Workflow app: scope, identity seams and skeleton

Phase 1: contracts/permissions/workflow.yaml (9 codes), contracts/workflow/README.md, docs/workflow.md, and the suite-rule wording in the messaging, scope and index docs. Phase 2: Platform Tenant System purpose (GENERIC|WORKFLOW) with the grant rule, the workflow service client and the infra seams, billing:refund:create made grantable with caller scoping on the write path (user decision), contracts and jOOQ regenerated. Phase 3: workflow/ copied from mailroom, trimmed to platform + empty definitions/runtime/triggers/connections/tasks, V1 baseline, replica queue, arch/RLS/startup/contract tests green; webhook context and compose runtimes deferred to phases 13 and 15.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
