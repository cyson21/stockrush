# 각 Spring Boot 서비스를 동일한 빌드/런타임 이미지 구조로 패키징합니다.
FROM maven:3.9-eclipse-temurin-17 AS build

ARG SERVICE_DIR
WORKDIR /workspace

COPY ${SERVICE_DIR}/pom.xml ./pom.xml
COPY ${SERVICE_DIR}/src ./src

RUN mvn -B -DskipTests package \
    && JAR_FILE="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*.original' | head -n 1)" \
    && test -n "$JAR_FILE" \
    && cp "$JAR_FILE" /workspace/app.jar

FROM eclipse-temurin:17-jre-jammy

ARG SERVICE_PORT=8080
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/app.jar /app/app.jar

EXPOSE ${SERVICE_PORT}
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
