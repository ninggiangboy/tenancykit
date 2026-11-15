---
title: Mailer service implemented end to end
date: 2026-11-15
summary: "Built the mailer service from plans/261115-0307-mailer-service: new Gradle build, delivery ledger schema with RLS, AMQP command consumer, SMTP and SESv2 adapters, tenant credential modes, compose/ops wiring and Terraform. mailer build green with 122 tests. Two findings worth remembering: the dispatcher silently acks commands when no InboundEventHandler bean is declared, and the shared shape rules assume a read side that mailer does not have."
---

# Mailer service implemented end to end

Built the mailer service from plans/261115-0307-mailer-service: new Gradle build, delivery ledger schema with RLS, AMQP command consumer, SMTP and SESv2 adapters, tenant credential modes, compose/ops wiring and Terraform. mailer build green with 122 tests. Two findings worth remembering: the dispatcher silently acks commands when no InboundEventHandler bean is declared, and the shared shape rules assume a read side that mailer does not have.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
