# =====================================================================
#  build context 是 repo 根目錄（見 docker-compose.yml 的 context: ..）
#
#  多階段：admin-ui（Phase 2，預設略過） -> maven build -> jre runtime
# =====================================================================

# --- Stage 1: admin-ui ------------------------------------------------
# Phase 1 預設 WITH_ADMIN_UI=false，此階段只產生空的 dist/，
# 後端建置完全不需要 Node。
FROM node:25-alpine AS ui
ARG WITH_ADMIN_UI=false
WORKDIR /ui
COPY admin-ui/ ./
RUN if [ "$WITH_ADMIN_UI" = "true" ]; then \
        npm ci && npm run build; \
    else \
        mkdir -p dist; \
    fi


# --- Stage 2: server --------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# 先只 copy pom 抓依賴，讓 Docker layer cache 在只改程式碼時不用重抓。
COPY server/pom.xml ./server/pom.xml
RUN mvn -B -f server/pom.xml dependency:go-offline

COPY server/ ./server/
COPY --from=ui /ui/dist/ ./server/src/main/resources/static/admin/

# 映像建置不跑測試 —— 測試在 CI 的 ci.yml（GitHub-hosted + Testcontainers）跑，
# 這裡跑會需要 docker-in-docker。見 Docs/plan/09-CICD與維運.md
RUN mvn -B -f server/pom.xml clean package -DskipTests


# --- Stage 3: runtime -------------------------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd -r -g 10001 app && useradd -r -u 10001 -g app -m app \
 && apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /src/server/target/*.jar app.jar
RUN chown app:app app.jar

ARG GIT_SHA=unknown
ENV APP_GIT_SHA=$GIT_SHA

USER app
EXPOSE 8080

# MaxRAMPercentage 讓 JVM 依容器記憶體上限自動調整 heap
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
