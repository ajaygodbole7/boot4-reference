# ---- Build stage ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    apt-get update && apt-get install -y maven && \
    mvn package -DskipTests -q

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /app

# Non-root user
RUN groupadd --system appuser && useradd --system --gid appuser appuser

COPY --from=build /app/target/*.jar app.jar
RUN chown appuser:appuser app.jar

USER appuser

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -sf http://localhost:8081/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
