---
phase: 5
title: "Definition authoring and publish"
status: pending
priority: P1
effort: "L"
dependencies: [4]
---

# Phase 5: Definition Authoring and Publish

## Goal

A Tenant creates a workflow definition, edits a draft graph with optimistic concurrency, gets structural errors by path,
simulates it without side effects, and publishes an immutable version that instances can later be pinned to.

## Context

Model after Survey's authoring: PUT the whole draft with `If-Match` (412 on conflict), errors by JSON pointer, immutable
published versions. Node types and edges are in `plan.md` (Design summary). Only the shape and checks are built here; node
behaviour arrives in phases 6 to 12. The graph schema lives in `contracts/workflow/graph.schema.json`.

## Files to create

- `contracts/workflow/graph.schema.json`, `contracts/openapi/workflow.yaml` (definition endpoints), vectors for structural
  checks `contracts/workflow/graph-vectors/*.json`
- `workflow/contexts/definitions`: domain (`Definition`, `Version`, `Graph`, `StructureChecker`), application (commands and
  queries), adapter (jOOQ, controller)
- Migration `V2__definitions.sql`: `workflow_definition`, `workflow_version` (graph `jsonb`, content hash), RLS
- Tests: unit tests for the checker, API tests, contract test

## Tasks

1. Endpoints: create (`code`, name), get, list (core-query contract), `PUT /definitions/{id}/draft` with `If-Match`,
   `POST /definitions/{id}/publish`, `POST /definitions/{id}/retire`, get a version, list versions, `POST
   /definitions/{id}/simulate`. Permissions `workflow:definition:read|manage`.
2. Structural checker, each failure with a pointer and a stable code: unknown node type, duplicate id, missing target,
   unreachable node, a `parallel` without its `join`, a cycle without an `foreach` or counted loop bound, an `end` missing,
   `compensate` referencing a non-`http` node, size limits (nodes ≤ 200, edges ≤ 400, expression nodes ≤ 2,000 per version).
3. Publish assigns the next version number, freezes the graph, stores a content hash, and refuses a graph with errors. An
   instance pins `version_id` later.
4. `http` nodes: at publish, an app target must name a method and path that exist in that app's OpenAPI (the specs are
   packaged into the jar at build, read from `contracts/openapi/*.yaml`); an unknown app, method or path is an error. External
   targets only need a connection reference that exists in the Tenant.
5. Simulate: given a sample trigger payload and canned step outputs, walk the graph with the evaluator and return the path
   taken and each branch decision; it never calls out or writes.

## Validation

- Vectors for good and bad graphs pass; 412 on a stale `If-Match`; publish of an invalid draft answers 422 with pointers.
- A published version cannot be edited; publishing twice yields two versions with different numbers.
- Contract test matches `workflow.yaml`; RLS test covers the new tables.

## Risks

- A graph schema that is too loose lets bad data into the runtime: the checker is the real gate, the schema is documentation
  plus shape. The runtime re-validates a version once when it loads it.
