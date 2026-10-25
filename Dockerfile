# Stage 1: Build JAR using Maven
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B || true
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Minimal runtime with essential developer CLI tools
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache bash curl git python3 py3-pip jq coreutils

WORKDIR /workspace

# Copy compiled spring boot jar
COPY --from=builder /app/target/demo-agent-*.jar /app/demo-agent.jar

EXPOSE 8080
ENV WORKSPACE_DIR=/workspace

ENTRYPOINT ["java", "-jar", "/app/demo-agent.jar"]
