# syntax=docker/dockerfile:1.7
# svc-rsk-decisioning container image.
# Build: docker build -t risk-decisioning-service:dev .
# The image is built from source with the Gradle wrapper so CI and local
# builds produce the same artifact; tests run in ci/test, not here.

FROM eclipse-temurin:23-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY risk-domain risk-domain
COPY risk-application risk-application
COPY risk-infrastructure risk-infrastructure
COPY risk-bootstrap risk-bootstrap
RUN ./gradlew --no-daemon :risk-bootstrap:bootJar -x test \
 && java -Djarmode=tools -jar risk-bootstrap/build/libs/risk-decisioning-service.jar \
      extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:23-jre AS runtime
RUN groupadd --system --gid 10001 risk \
 && useradd --system --uid 10001 --gid risk --no-create-home --shell /usr/sbin/nologin risk
WORKDIR /app
# Layers ordered from least to most frequently changed for cache reuse.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
