# syntax=docker/dockerfile:1.7
#
# Build context is the repository root, because each service build includes `core/` as a separate Gradle build.
# Build: docker build -f tenant/apps/app-boot/Dockerfile .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY tenant/ tenant/
COPY core/ core/
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    cd tenant && ./gradlew :apps:app-boot:bootJar --no-daemon

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home /app app
COPY --from=build /workspace/tenant/apps/app-boot/build/libs/app-boot.jar /app/application.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
