# ============================================================
# Tapstate Cloud — Global Control Plane Dockerfile
# Multi-stage build: compile with Maven → runtime with JDK 21 JRE
# Maven reactor builds common → cloud-control-plane → client/java
# ============================================================

# ---------- Stage 1: Build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Cache Maven dependencies — copy parent pom and module poms first
COPY pom.xml .
COPY common/pom.xml common/
COPY cloud-control-plane/pom.xml cloud-control-plane/
COPY client/java/pom.xml client/java/
# Generate-sources touches openapi.yaml so copy it too for SDK module
COPY client/api client/api/

# Download dependencies (cache layer — poms unchanged → skip)
RUN mvn -q -B dependency:go-offline -pl common,cloud-control-plane,client/java -am 2>&1 | tail -5

# Full source
COPY common/src common/src
COPY cloud-control-plane/src cloud-control-plane/src
COPY client/java/src client/java/src

# Build (skip tests — tests run on CI, not in image build)
RUN mvn -q -B clean package -DskipTests -pl cloud-control-plane -am

# ---------- Stage 2: Runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: non-root user
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

# Copy the fat jar from cloud-control-plane module
COPY --from=build /workspace/cloud-control-plane/target/tapstate-cloud-control-plane.jar ./app.jar

# Default JVM settings (can be overridden via env)
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseContainerSupport"

EXPOSE 8080

# Health check (k3s livenessProbe)
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD wget -qO- http://localhost:8080/health || exit 1

USER app

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
