# ── Stage 1: Build ────────────────────────────────────────────────────────────
# eclipse-temurin Debian maven supports linux/amd64 + linux/arm64 (Apple Silicon)
# Builder size doesn't affect the final image — only the last stage counts
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
# Download dependencies first for layer caching
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# ── Stage 2: Extract layered JAR ──────────────────────────────────────────────
# Spring Boot 3.x layertools splits the fat-jar into 4 layers by change frequency:
#   dependencies → spring-boot-loader → snapshot-dependencies → application
# amazoncorretto:17-alpine supports both linux/amd64 AND linux/arm64
FROM amazoncorretto:17-alpine AS extractor
WORKDIR /app
COPY --from=builder /app/target/coursechatbot-backend-1.0.0.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ── Stage 3: Custom minimal JRE via jlink ────────────────────────────────────
# jlink strips out all JDK modules not needed by Spring Boot, cutting the JRE
# from ~200 MB (full JDK) down to ~50 MB
FROM amazoncorretto:17-alpine AS jre-builder
# binutils supplies the `objcopy` binary that jlink needs for --strip-debug
RUN apk add --no-cache binutils && \
    $JAVA_HOME/bin/jlink \
      --add-modules java.base,java.logging,java.xml,java.naming,java.desktop,java.management,java.security.jgss,java.instrument,java.sql,java.transaction.xa,jdk.unsupported,jdk.crypto.ec \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=2 \
      --output /custom-jre

# ── Stage 4: Minimal runtime ──────────────────────────────────────────────────
# alpine:3.19 base ≈ 7 MB + custom JRE ≈ 50 MB + app ≈ 100 MB → ~160 MB total
FROM alpine:3.19
ENV JAVA_HOME=/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=jre-builder /custom-jre ${JAVA_HOME}

WORKDIR /app

# Run as non-root for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy layers most-stable → least-stable for maximum Docker cache reuse
COPY --from=extractor /app/dependencies/          ./
COPY --from=extractor /app/spring-boot-loader/    ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/application/           ./

USER appuser
EXPOSE 8080

# MaxRAMPercentage keeps heap sane inside Docker
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "org.springframework.boot.loader.launch.JarLauncher"]
