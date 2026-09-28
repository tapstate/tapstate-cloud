# ============================================================
# Tapstate Cloud — Global Control Plane Dockerfile
# Multi-stage build: compile with Maven → runtime with JDK 21 JRE
# ============================================================

# ---------- Stage 1: Build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Cache Maven dependencies (copy pom.xml first)
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# Build the application
COPY src ./src
RUN mvn clean package -B -DskipTests -q

# ---------- Stage 2: Runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: non-root user
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

# Copy the fat jar
COPY --from=build /workspace/target/tapstate-cloud.jar ./app.jar

# Default JVM settings (can be overridden via env)
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseContainerSupport"

EXPOSE 8080

# Health check (k3s livenessProbe)
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD wget -qO- http://localhost:8080/health || exit 1

USER app

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
