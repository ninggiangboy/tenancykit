# Local runtime for the suite and its console. `make help` lists the targets.
#
#   make up        backends in Docker (needs .env)
#   make console   build the console, publish the shells and remotes into the stack
#   make dev       hot-reload dev servers for every shell and remote
#
# A remote is published under a fresh version on every run, so a rebuild of the same commit never collides with the
# directory a previous publish left behind. Registering never activates a version: activate it in Platform Admin.

SHELL := /bin/bash

COMPOSE   ?= docker compose
FRONTEND  := frontend
REMOTES   ?= platform billing
SHELLS    ?= platform tenant
PLATFORM_URL ?= http://platform.localhost:8090/console/
# Caddy depends on Platform, Billing, Notification, Mailroom, Customer, Loyalty, the tenant and the console server, so
# starting it brings up exactly the services the console talks to.
CONSOLE_SERVICES ?= caddy

.DEFAULT_GOAL := help

.PHONY: help env-check up up-all down clean ps logs frontend-install console-build console-publish console dev e2e smoke isolation

help: ## List the targets
	@grep -E '^[a-zA-Z0-9_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-18s %s\n", $$1, $$2}'

env-check: ## Create .env with generated secrets if it does not exist (never overwrites)
	@ops/env/generate-env.sh

up: env-check ## Start what the console needs (Caddy and its dependencies) and wait until healthy
	$(COMPOSE) up -d --build --wait $(CONSOLE_SERVICES)
	$(COMPOSE) ps

up-all: env-check ## Start every service, including Survey, Booking and Workflow
	$(COMPOSE) up -d --build --wait
	$(COMPOSE) ps

down: ## Stop the stack, keep the data
	$(COMPOSE) down

clean: ## Stop the stack and delete its volumes (databases, console assets)
	$(COMPOSE) down -v

ps: ## Show the stack's services
	$(COMPOSE) ps

logs: ## Follow logs; limit with SERVICE=platform
	$(COMPOSE) logs -f --tail=100 $(SERVICE)

frontend-install: ## Install frontend dependencies from the lockfile
	cd $(FRONTEND) && pnpm install --frozen-lockfile

console-build: frontend-install ## Build every shell and remote, each remote under a fresh version
	@set -euo pipefail; \
	cd $(FRONTEND); \
	pnpm --filter './apps/*' build; \
	sha=$$(git rev-parse --short HEAD); stamp=$$(date +%s); \
	for r in $(REMOTES); do \
	  pkg=$$(node -p "require('./remotes/$$r/package.json').version"); \
	  v="$$pkg+$$sha-dev$$stamp"; \
	  echo "building remote $$r $$v"; \
	  REMOTE_VERSION=$$v pnpm --filter "@suite/remote-$$r" build; \
	  echo "$$v" > "remotes/$$r/dist/.published-version"; \
	done

console-publish: env-check ## Publish the built shells and remotes into the running stack
	@set -euo pipefail; \
	set -a; . ./.env; set +a; \
	for s in $(SHELLS); do ops/console/deploy-shell.sh $$s $(FRONTEND)/apps/$$s-shell/dist; done; \
	for r in $(REMOTES); do \
	  REMOTE_VERSION_OVERRIDE=$$(cat $(FRONTEND)/remotes/$$r/dist/.published-version) \
	    ops/console/deploy-remote.sh $$r $(FRONTEND)/remotes/$$r/dist; \
	done; \
	echo; echo "Published. Sign in at $(PLATFORM_URL) and activate the new remote versions in Platform Admin."

console: console-build console-publish ## Build and publish the console into the running stack

dev: frontend-install ## Run every shell and remote dev server (the stack must be up)
	cd $(FRONTEND) && pnpm dev

e2e: ## Run the Playwright suite against the running stack
	cd $(FRONTEND)/e2e && pnpm test

smoke: env-check ## Run the suite smoke test against the running stack
	ops/smoke/suite-smoke.sh

isolation: env-check ## Prove no service reaches another's database or exchange
	ops/smoke/isolation.sh
