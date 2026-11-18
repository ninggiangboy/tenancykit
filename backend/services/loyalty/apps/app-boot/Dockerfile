# syntax=docker/dockerfile:1.7
#
# Build context is the repository root, because each service build includes `core/` as a separate Gradle build and
# loyalty packages its permission catalog and event schemas from `contracts/`.
#
# Build: docker build -f backend/services/loyalty/apps/app-boot/Dockerfile .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# Only what this build reads; a change under any other service never invalidates it.
COPY backend/services/loyalty/ backend/services/loyalty/
COPY backend/libs/core/ backend/libs/core/
COPY contracts/ contracts/
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    cd backend/services/loyalty && ./gradlew :apps:app-boot:bootJar --no-daemon

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home /app app
COPY --from=build /workspace/backend/services/loyalty/apps/app-boot/build/libs/app-boot.jar /app/application.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
