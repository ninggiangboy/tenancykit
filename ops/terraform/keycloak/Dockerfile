# Build context is the repository root:
#
#   docker build -f ops/terraform/keycloak/Dockerfile -t <ecr>/core-billing/<env>/keycloak:<tag> .
#
# Terraform does not build images; CI (or a manual command in ops/terraform/README.md) builds and pushes this one.
FROM quay.io/keycloak/keycloak:26.8.2

COPY ops/keycloak/realm-export.json /opt/keycloak/data/import/realm-export.json
