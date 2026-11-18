# syntax=docker/dockerfile:1.7
#
# Build context is the repository root, because each service build includes `core/` as a separate Gradle build and
# notification packages its command schema from `contracts/`.
#
# Build: docker build -f notification/apps/app-boot/Dockerfile .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# Only what this build reads; a change under any other service never invalidates it.
COPY notification/ notification/
COPY core/ core/
COPY contracts/ contracts/
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    cd notification && ./gradlew :apps:app-boot:bootJar --no-daemon

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home /app app
COPY --from=build /workspace/notification/apps/app-boot/build/libs/app-boot.jar /app/application.jar
USER app
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
