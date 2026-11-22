# Build context is the repository root:
#
#   docker build -f ops/terraform/keycloak/Dockerfile -t <ecr>/tenancykit/<env>/keycloak:<tag> .
#
# Terraform does not build images; CI (or a manual command in ops/terraform/README.md) builds and pushes this one.
# Docker Compose builds the same image for local runs, so the sign-in page behaves the same everywhere.

# The tenant username form is a Keycloak provider; Keycloak 26 runs on Java 21, so the jar is built against it.
FROM eclipse-temurin:21-jdk AS provider
WORKDIR /provider
COPY ops/keycloak/provider/ ./
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew --no-daemon jar

FROM quay.io/keycloak/keycloak:26.8.2

COPY --from=provider /provider/build/libs/suite-keycloak-provider.jar /opt/keycloak/providers/suite-keycloak-provider.jar
COPY ops/keycloak/theme/suite /opt/keycloak/themes/suite
# The sign-in page reads the design system's own tokens rather than keeping a second copy of them.
COPY frontend/packages/ui/src/styles/tokens.css /opt/keycloak/themes/suite/login/resources/css/tokens.css
COPY ops/keycloak/realm-export.json /opt/keycloak/data/import/realm-export.json
