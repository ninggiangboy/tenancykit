# syntax=docker/dockerfile:1.7
#
# Build context is the repository root, because each service build includes `core/` as a separate Gradle build.
# Build: docker build -f backend/services/platform/apps/app-boot/Dockerfile .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY backend/services/platform/ backend/services/platform/
COPY backend/libs/core/ backend/libs/core/
COPY contracts/ contracts/
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    cd backend/services/platform && ./gradlew :apps:app-boot:bootJar --no-daemon

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home /app app
COPY --from=build /workspace/backend/services/platform/apps/app-boot/build/libs/app-boot.jar /app/application.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
