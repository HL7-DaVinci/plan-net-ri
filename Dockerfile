
##########################################################################
# Stage 1: Build the TanStack SPA frontend
##########################################################################
FROM oven/bun:1-slim AS build-frontend

WORKDIR /app

# Copy workspace package files and lockfile
COPY package.json bun.lock ./
COPY frontend/package.json ./frontend/

# Install dependencies
RUN bun install --frozen-lockfile

COPY frontend/index.html ./frontend/
COPY frontend/tsconfig.json ./frontend/
COPY frontend/tsconfig.build.json ./frontend/
COPY frontend/vite.config.ts ./frontend/
COPY frontend/public/ ./frontend/public/
COPY frontend/src/ ./frontend/src/

# Build the frontend for production
WORKDIR /app/frontend
RUN bun run build


##########################################################################
# Stage 2: Build the HAPI FHIR Server
##########################################################################
FROM docker.io/library/maven:3.9.12-eclipse-temurin-17 AS build-hapi
WORKDIR /tmp/hapi-fhir-jpaserver-starter

ARG OPENTELEMETRY_JAVA_AGENT_VERSION=2.24.0
RUN curl -LSsO https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OPENTELEMETRY_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar

COPY pom.xml .
COPY server.xml .
RUN mvn -ntp dependency:go-offline

COPY src/ /tmp/hapi-fhir-jpaserver-starter/src/

# Copy frontend build artifacts to server's static resources directory under /crawler
COPY --from=build-frontend /app/frontend/dist/ /tmp/hapi-fhir-jpaserver-starter/src/main/resources/static/crawler/

RUN mvn clean install -DskipTests -Djdk.lang.Process.launchMechanism=vfork


##########################################################################
# Stage 3: Package for Spring Boot
##########################################################################
FROM build-hapi AS build-distroless
RUN mvn package -DskipTests spring-boot:repackage -Pboot
RUN mkdir /app && cp /tmp/hapi-fhir-jpaserver-starter/target/ROOT.war /app/main.war

COPY src/main/java/HealthCheck.java /app/HealthCheck.java
RUN javac /app/HealthCheck.java


##########################################################################
# Stage 4: Final Production Image (Distroless)
##########################################################################
FROM gcr.io/distroless/java21-debian13:nonroot AS default
# 65532 is the nonroot user's uid
# used here instead of the name to allow Kubernetes to easily detect that the container
# is running as a non-root (uid != 0) user.
USER 65532:65532

COPY --chown=nonroot:nonroot --from=build-distroless /app /app
COPY --chown=nonroot:nonroot --from=build-hapi /tmp/hapi-fhir-jpaserver-starter/opentelemetry-javaagent.jar /app

WORKDIR /app

ENTRYPOINT ["java", "--class-path", "/app/main.war", "-Dloader.path=main.war!/WEB-INF/classes/,main.war!/WEB-INF/,/app/extra-classes", "org.springframework.boot.loader.PropertiesLauncher"]
