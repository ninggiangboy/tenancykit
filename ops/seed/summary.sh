#!/usr/bin/env bash
# The accounts and addresses the seed leaves behind, printed at the end of a run. README.md lists the same.
# Sourced by seed.sh.

seed_summary() {
  cat <<SUMMARY

Sign in with the email on the tenant's own address; the password of every seeded account is ${SEED_PASSWORD}

  Platform console   http://platform.localhost:8090/console/
    root@suite.localhost       platform administrator (everything)
    support@suite.localhost    platform support (read-only)
    ${BOOTSTRAP_EMAIL}   the bootstrap administrator from the environment file (its own password)

  Tenant consoles    http://<tenant>.localhost:8090/console/
    acme     Acme Coffee Co.       all apps, the full demo
    globex   Globex Fitness        billing, notification, booking, customer, loyalty
    initech  Initech               billing and customer, nothing in them (the empty state)
    dormant  Dormant Ltd           deactivated (Keycloak still signs in, every API answers 404)
    per tenant: admin@<tenant>.localhost (administrator), manager@ (all rights but erasure and ledger
                reversal), viewer@ (read-only), nobody@ (no role)

  Mail the suite sends     http://localhost:8025   (Mailpit)
  Keycloak admin           http://localhost:8081
SUMMARY
}
