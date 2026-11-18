# syntax=docker/dockerfile:1.7
#
# Build context is the repository root, because each service build includes `core/` as a separate Gradle build and
# mailer packages its command schemas from `contracts/`.
#
# This file is based on Billing's, not the sample tenant's, and the difference is the whole point: Billing copies
# `contracts/`, the tenant does not. Mailer's `processResources` packages `contracts/commands` onto the classpath, so a
# tenant-shaped Dockerfile produces an image that builds green and then crash-loops on a missing schema resource — and
# only in ECS, never on a laptop where the resource is on the classpath from the source tree.
#
# Build: docker build -f mailer/apps/app-boot/Dockerfile .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# Only what this build reads; a change under any other service never invalidates it.
COPY mailer/ mailer/
COPY core/ core/
COPY contracts/ contracts/
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    cd mailer && ./gradlew :apps:app-boot:bootJar --no-daemon

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home /app app
COPY --from=build /workspace/mailer/apps/app-boot/build/libs/app-boot.jar /app/application.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
