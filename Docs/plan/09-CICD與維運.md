# 09 — CI/CD 與維運

> 校訂日期：2026-09-21。依 2026-09-21 main 的程式碼核對；歷史方案與未實作項目另行標示。

← [文件索引](README.md) ｜ 前一份 [08-測試計畫](08-測試計畫.md)

涵蓋：部署管線、可觀測性、環境設定、金鑰生命週期、故障排除。

---

## 1. 部署架構

見 [ADR-0006](adr/0006-CICD-採-self-hosted-runner.md)、
[ADR-0010](adr/0010-CICD-改採-GHCR-加-SSH-部署.md) 與
[ADR-0013](adr/0013-公開原始碼與私人維運分離.md)。原始碼 repo 與 production workflow 分離：

```text
public NotificatioLineBot main
   │  私人 workflow 每 10 分鐘讀取 exact SHA
   ▼
private NotificatioLineBot-Operations
   ├── detect  self-hosted：比對 $DEPLOY_PATH/.public-source-sha
   ├── test    GitHub-hosted：mvn verify
   ├── image   GitHub-hosted：建置私人 GHCR sha-<12碼SHA>
   └── deploy  self-hosted：沿用主機 .env → pull + up -d
               → 健康檢查 → 成功記錄 SHA；失敗回滾
```

公開 repo 不含 workflow 且 Actions 已停用。本專案的 self-hosted jobs 只存在私人 Operations repo，
所以公開 Fork PR 不會在正式主機執行。部署不使用 SSH，也不需對外開 22 port。

正式 `.env` 只留在 server，權限必須為 `600`。私人 workflow 只確認必要欄位非空，
不重建、不輸出、不把正式網域或 LINE ID 存成 GitHub Variables。安全邊界見 [§3](#3-安全前提)。

## 2. Workflow

公開 repo 的 `.github/workflows/` 已移除。私人 Operations repo 有兩個 workflow：

| 檔案 | name | 觸發 | 用途 |
|---|---|---|---|
| `.github/workflows/deploy.yml` | `Sync public main and deploy` | 每 10 分鐘排程、`workflow_dispatch` | `detect` → `test` → `image` → `deploy` |
| `.github/workflows/admin.yml` | `Production operations` | **僅** `workflow_dispatch` | `status`、`logs`、`restart` |

兩者共用 `notifyline-production` concurrency group 且 `cancel-in-progress: false`，避免部署和重啟交錯。
所有外部 action 都固定完整 commit SHA，workflow 只使用 `actions/*` 與 `docker/*` 官方 action。

### 2.1 `detect` 與 `test`

`detect` 在 self-hosted runner 以 `git ls-remote` 讀取公開 `main` SHA，與主機上的
`.public-source-sha` 比較。SHA 相同時後續 jobs 全部跳過；手動 dispatch 的 `force=true` 可強制重跑。

有新 SHA 時，`test` 在 `ubuntu-latest` checkout 該**確切 commit**，設定 Java 21 並執行
`mvn -B -ntp -f server/pom.xml verify`。JaCoCo 報告保留 14 天。

> 專案沒有 Maven Wrapper；GitHub-hosted runner 直接呼叫預裝 `mvn`。建置的可重現性仍受 Maven
> 版本影響；如需完全可重現的 Maven 工具鏈，應補 Maven Wrapper。

`verify` 一次跑完三件事：

| 階段 | 內容 |
|---|---|
| surefire | `*Test` 單元測試 |
| failsafe | `*IT` 整合測試（Testcontainers 起真的 PostgreSQL） |
| jacoco `check` | 覆蓋率門檻（整體 80% / `auth` 95%），不達標直接失敗 |

**跑在 GitHub-hosted 是刻意的**：測試自備資料庫，不和正式服務競爭資源。公開 PR 不會觸發
任何 workflow；只有公開 `main` 的 SHA 能進入私人流程。

### 2.2 `image`

`needs: [detect, test]`。它再次 checkout 相同公開 SHA，建立並推送私人 image：

```text
ghcr.io/alientechforge/notificationlinebot-operations:sha-<公開SHA前12碼>
ghcr.io/alientechforge/notificationlinebot-operations:latest
```

`docker/build-push-action` 使用 `context: .`、`file: docker/Dockerfile`、build-args
`WITH_ADMIN_UI=false` 與完整公開 SHA，並啟用 GHA cache、provenance 與 SBOM。部署只使用 immutable
`sha-...` tag，不依賴 `latest`。

> **Dockerfile 不在根目錄**，所以 `file: docker/Dockerfile` 但 `context: .` ——
> 它要 COPY `server/` 與 `admin-ui/`。

> ⚠️ **`WITH_ADMIN_UI` 目前唯一可用值是 `false`**，因為 `admin-ui/` 是空殼。見 [§4](#4-docker)。

> 有 `provenance` 與 `sbom` attestation，但**沒有 image 簽章，部署端也不驗證來源**。

### 2.3 `deploy`

`needs: [detect, image]`，跑在 `[self-hosted, Linux, X64]`。步驟如下：

```text
1. checkout 公開 repo 的 exact SHA
2. 解析 TARGET=${DEPLOY_PATH_OVERRIDE:-$HOME/notifyline}
3. 確認 .env 存在、權限為 600、Docker 可用，四個必要 secret 非空
4. 複製 docker-compose.prod.yml 到部署目錄
5. 記下目前 image，使用私人 repo GITHUB_TOKEN 登入私人 GHCR
6. pull immutable image 並 docker compose up -d
7. 最多 180 秒健康檢查；失敗回滾前一個 image
8. 成功才寫入 .public-source-sha
```

workflow 不會建立或改寫 `.env`，也不會把值輸出到 log。`DEPLOY_PATH` 是唯一需要的 repository
variable；其餘正式設定由部署者直接管理主機檔案。這也讓 GitHub Secrets 不再成為正式機密的第二份副本。

回滾只換回 image，不回滾資料庫 schema。若 migration 已不相容，仍要以向前修復處理。

### 2.4 `admin.yml`（手動維運）

私人 Operations repo 的 `Production operations` 只接受 choice 型的 `action`，不接受任意字串：

| `action` | 行為 |
|---|---|
| `status` | `docker compose ps`、`/actuator/health` 與目前 image |
| `logs` | 最近 120 行 app log，寫入私人 run summary |
| `restart` | 以目前 image 重啟 app，最多等 120 秒健康檢查 |

建立／作廢 client、設定 owner、列出 client 與直接 SQL 已從 Actions 移除，改由 Admin UI 執行。
因此新的 client secret 不會進 raw log，也沒有 workflow input 直接插入 production shell／SQL 的路徑。

金鑰重加密工具仍未實作；目前 Actions 也不提供金鑰檢視或輪替捷徑。


## 3. 安全前提

### Secrets / Variables

Operations repo 只需內建 `GITHUB_TOKEN` 推／拉自己的私人 GHCR package。可選 variable `DEPLOY_PATH`
指定部署目錄，未設則使用 `$HOME/notifyline`。LINE、DB、加密金鑰、正式網域與 LINE ID 都只存在
主機 `.env`，不存 GitHub Secrets／Variables。

### 一次性的 repo 設定

- 公開 repo：Actions disabled；Fork PR approval = **Require approval for all outside collaborators**
- 私人 Operations repo：Workflow permissions = **Read and write**
- runner group 允許私人 Operations repo 使用部署用 runner；公開 source repo 保持 Actions disabled

### 必須遵守

| 措施 | 為什麼 |
|---|---|
| **Operations repo 保持 private** | production workflow、GHCR package、run log 與 runner access 不公開 |
| **公開 repo 停用 Actions** | Fork PR 無法執行 workflow；approval policy 是第二層防護 |
| 不使用 `pull_request_target`／`repository_dispatch` | 不讓公開事件攜帶私人 token 或執行 production workflow |
| 第三方 action 釘完整 SHA | 降低浮動 tag 被改指向的供應鏈風險 |
| `permissions` 逐 job 最小化 | test 只讀 contents；image 才可寫 packages；deploy 只讀 packages |
| self-hosted 只做 detect／deploy／維運 | 公開來源的 build 與測試在隔離的 GitHub-hosted runner |


## 4. Docker

### `docker/Dockerfile`（multi-stage）

```dockerfile
# ── Stage 1: admin-ui（Phase 2）──────────────────────
FROM node:25-alpine AS ui
ARG WITH_ADMIN_UI=false
WORKDIR /ui
COPY admin-ui/package*.json ./
RUN if [ "$WITH_ADMIN_UI" = "true" ]; then npm ci; fi
COPY admin-ui/ ./
RUN if [ "$WITH_ADMIN_UI" = "true" ]; then npm run build; else mkdir -p dist; fi

# ── Stage 2: server ─────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY server/pom.xml ./server/
RUN mvn -B -f server/pom.xml dependency:go-offline    # 依賴層快取
COPY server/ ./server/
COPY --from=ui /ui/dist ./server/src/main/resources/static/admin/
RUN mvn -B -f server/pom.xml clean package -DskipTests

# ── Stage 3: runtime ────────────────────────────────
FROM eclipse-temurin:21-jre
RUN groupadd -r -g 10001 app && useradd -r -u 10001 -g app -m app
RUN apt-get install curl … && rm -rf /var/lib/apt/lists/*   # compose healthcheck 需要
WORKDIR /app
COPY --from=build /src/server/target/*.jar app.jar         # chown app:app
ARG GIT_SHA=unknown
ENV APP_GIT_SHA=$GIT_SHA
USER app                                                    # 非 root
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
```

先 copy `pom.xml` 再 `dependency:go-offline`，讓 Docker layer cache 在只改程式碼時不用重抓依賴。
image 建置階段 `-DskipTests` —— **測試只在 CI 的 `test` job 跑**。

Dockerfile **本身沒有 `HEALTHCHECK` 指令、也沒有 `VOLUME` 宣告**，健康檢查一律由 compose 提供。

> ⚠️ **`admin-ui/` 是空殼。** 目錄下只有 6 個 `.gitkeep`，**沒有 `package.json`**。
> 所以 stage 1 一旦帶 `WITH_ADMIN_UI=true`，`npm ci` **必然失敗**，
> 目前唯一能用的值是 `false`（CI 的 build-arg 也寫死 `false`）。
>
> 而且實際上線的管理介面是**手寫的靜態檔且已進版控**
> （`server/src/main/resources/static/admin/` 下的 `index.html`、`login.html`、
> `assets/app.css`、`assets/app.js`），與 `admin-ui/` 的目錄結構毫無關聯。
> `WITH_ADMIN_UI=true` 若哪天能跑起來，stage 1 產出的 `dist/` 會被
> `COPY --from=ui` **覆蓋掉這些手寫檔**。這兩件事必須一起處理。

> ⚠️ `APP_GIT_SHA` 這個 ENV **沒有任何程式讀取**
> （`grep -rn "APP_GIT_SHA\|GIT_SHA" server/src admin-ui` 零命中），
> 而 `spring-boot-maven-plugin` 也沒啟用 `build-info` goal，`/actuator/info` 沒有版本資訊。
> 結果是「執行中的 image 是哪個 commit」只能靠 `docker inspect` 看 tag。

> Dockerfile 註解指向私人 Operations repo 的 deploy workflow；公開 source repo 不含 CI workflow。

### `docker/docker-compose.yml`

```yaml
services:
  postgres:
    image: postgres:18-alpine
    environment:
      POSTGRES_DB: notifyline
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      # PostgreSQL 18+ 要掛 /var/lib/postgresql（不是 .../data）。
      # 映像會自己在底下建 major-version 專屬子目錄，讓日後 pg_upgrade --link
      # 不跨掛載點。掛舊路徑容器會直接啟動失敗（T1 實測踩到）。
      - pgdata:/var/lib/postgresql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER} -d notifyline"]
      interval: 10s
      retries: 5
    restart: unless-stopped

  app:
    build:
      context: ..
      dockerfile: docker/Dockerfile
      args:
        WITH_ADMIN_UI: "false"
        GIT_SHA: ${GIT_SHA:-local}
    container_name: notifyline-app
    env_file: ../.env
    depends_on:
      postgres: { condition: service_healthy }
    ports:
      - "127.0.0.1:${APP_PORT:-19080}:8080"   # 只綁 localhost，對外由反向代理處理
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8080/actuator/health"]
      interval: 15s
      timeout: 3s
      retries: 5
      start_period: 60s
    restart: unless-stopped

volumes:
  pgdata:
```

### `docker/docker-compose.prod.yml`

與上者只有兩處差別，其餘（postgres、ports、healthcheck、restart、volume）完全相同：

- `app` **不 build**，改 `image: ${NOTIFYLINE_IMAGE:-ghcr.io/alientechforge/notificationlinebot-operations:latest}`
- `env_file: .env`（與 compose 檔同目錄，即 `$DEPLOY_PATH/.env`）

> ⚠️ **本設定僅支援單一 app 實例，而且沒有任何機制強制這件事。** 兩份 compose 檔的檔頭都寫了警告，但程式碼層沒有 leader election、沒有分散式鎖。加開第二個實例前必須先處理 [缺口 G12](02-架構設計.md#g12--多實例的排程重複執行--加第二個實例前必須解決)（`@Scheduled` 重複執行，需 ShedLock 之類的機制）與 [07 §11](07-非同步與可靠性設計.md#11-已知限制與擴充路徑) 的 `RateLimitFilter` per-instance 計數問題。

`ports` 綁 `127.0.0.1` 而非 `0.0.0.0`——應用不直接對外，一律經反向代理。
主機 port 由 `.env` 的 `APP_PORT` 決定（預設 19080；8080 在多數機器上已被占用）。
PostgreSQL **不對主機 publish 任何 port**，只在 compose 內部網路。

#### Port 總表

| 位置 | Port |
|---|---|
| 容器內應用 | `8080`（`server.port: 8080`、`EXPOSE 8080`） |
| 主機（僅 loopback） | `127.0.0.1:${APP_PORT:-19080}` |
| nginx upstream | `127.0.0.1:19080`（**硬寫**在 `deploy/nginx/notifyline.conf.example`） |
| 對外 | 80（301 轉址）、443（TLS） |
| PostgreSQL | 不 publish |

> ⚠️ **`APP_PORT` 在三處各自定義且需人工保持一致**：`.env`（compose port mapping）、
> nginx conf 的 `upstream notifyline`（範例為 `127.0.0.1:19080`）。
> **只改 `.env` 會讓 nginx 指向一個沒人聽的 port。** 兩處必須在同一次維運中同步修改。

> ⚠️ compose 兩份檔案都**沒有 `logging:` 區塊**，走 docker 預設的 json-file driver
> 且**無 `max-size` / `max-file` 上限**；應用本身也不寫檔案 log。
> 長期執行可能把磁碟吃滿，應在 Compose 加入 log rotation。

### 反向代理

實際的 nginx 設定（`deploy/nginx/notifyline.conf.example`，放在
`/etc/nginx/conf.d/`）與部署步驟見 [`deploy/`](../../deploy/README.md)。
幾個**不能照抄一般範本**的地方：

| 設定 | 為什麼 |
|---|---|
| **不開** `proxy_intercept_errors`、**不 include** 自訂錯誤頁 | 這個 API 的 4xx **本身就是契約** —— 呼叫端要靠 `error.code` 分支處理。換成 HTML 錯誤頁對方就無法判讀 |
| `location = /line/webhook` 與 `location /api/`：`proxy_request_buffering off` + `proxy_buffering off`，且不套任何改動 body 的模組（`modsecurity off`） | **HMAC 驗簽是對原始 body bytes 做的**。gzip、sub_filter、ModSecurity 的改寫都會讓簽章對不上，而錯誤只會是一句「Invalid signature」 |
| 只開 `location /actuator/health`（前綴比對，涵蓋 `/liveness`、`/readiness`），不開整個 `/actuator` | `/actuator/env`、`/actuator/configprops` 會洩漏設定值（呼應 [缺口 F7](10-任務拆解.md#t1-實作發現規劃時未預見已修正)）。副作用：`/actuator/prometheus` 也一併被擋，見 [§6.2](#62-指標micrometer) |
| `location /enroll/` 覆寫 `Cache-Control: no-store` + `Referrer-Policy: no-referrer` + `X-Frame-Options: DENY` + `X-Content-Type-Options: nosniff` | 該頁會顯示 client secret，而且只顯示一次 |
| `location / { return 404; }` 預設拒絕 | 日後新增端點要明確開放，不會有人不小心把管理介面暴露出去 |
| `client_max_body_size 1m` | 應用層自身上限是 64KB → 413，nginx 只擋掉更離譜的 |
| `map_hash_bucket_size 128;` | admin gate 的密鑰字串超過預設的 64 bytes，不設會讓 nginx 啟動報 `could not build map_hash` |

#### 管理介面門禁（方案 C，現行）

Cloudflare Transform Rule 對 `/admin*` 注入 `X-Admin-Gate: <密鑰>`，
nginx 以 `map $http_x_admin_gate $admin_denied` 比對，不符即 `return 403`。
`location = /admin/login` 另加
`limit_req_zone $http_cf_connecting_ip zone=notifyline_admin_login:10m rate=5r/m`
搭配 `burst=3 nodelay`、`limit_req_status 429`。

密鑰檔 `/etc/nginx/conf.d/admin-gate.map`（`root:root`、`640`）**不進版控**，
範本是 `deploy/nginx/admin-gate.map.example`，密鑰以 `openssl rand -hex 24` 產生。

> ⚠️ 這個門禁**信任 Cloudflare 注入的 header，但沒有驗證連線確實來自 Cloudflare**。
> conf 檔尾的 SECURITY 註記已載明可疊加方案 B（防火牆只放行 Cloudflare IP 段連 origin 443），
> 代價是要維護 Cloudflare 的 IP 段清單。目前未採用。

> ⚠️ 密鑰輪替是**純人工的兩處手改**：Cloudflare Transform Rule + server 上的 `.map` 檔
> + `systemctl reload nginx`。沒有自動化、沒有備援。

> ⚠️ **不要只用 LINE Console 的「Verify」判斷是否接通。** 它只檢查是否回 200 ——
> 若反向代理還沒設好而 origin 回的是佔位頁，Verify 會顯示成功但事件根本沒進到 app。
> 驗證方式見 [`deploy/README.md` §5](../../deploy/README.md#5-驗證不要只信-line-的-verify-綠勾)。

---

## 5. 環境設定

### 5.1 環境變數全表

欄位的判讀方式：

- **`.env.example`**：範本檔有列
- **deploy 檢查**：私人 workflow 是否在部署前驗證主機 `.env` 的該值
- **必填**：以「沒有這個值應用是否無法正常運作」判定，不是以有無預設值判定

#### 應用實際會讀的變數

| 變數 | 綁定屬性 / 類別 | 用途 | 必填 | 預設值 | `.env.example` | deploy 檢查 |
|---|---|---|---|---|---|---|
| `LINE_CHANNEL_TOKEN` | `line.bot.channel-token` | LINE Messaging API 發訊息用的 channel access token | **是** | 無（dev 為 `dev-dummy-channel-token`） | ✅ | ✅ 非空 |
| `LINE_CHANNEL_SECRET` | `line.bot.channel-secret` | webhook `x-line-signature` 驗簽 | **是** | 無（dev 為 `dev-dummy-channel-secret`） | ✅ | ✅ 非空 |
| `DB_URL` | `spring.datasource.url` | JDBC 連線字串 | **是** | 無（dev 為 `jdbc:postgresql://localhost:5432/notifyline`） | ✅ | — |
| `DB_USER` | `spring.datasource.username` | DB 帳號；compose 也用它建 `POSTGRES_USER` 與 `pg_isready` | **是** | 無（dev 為 `notifyline`） | ✅ | — |
| `DB_PASSWORD` | `spring.datasource.password` | DB 密碼；compose 也用它建 `POSTGRES_PASSWORD` | **是** | 無（dev 為 `notifyline`） | ✅（空） | ✅ 非空 |
| `APP_SECRET_ENC_KEY` | `app.crypto.keys["1"]`（`CryptoProperties`） | AES-256 加密所有 client secret | **是** | 空字串（dev 為固定 dummy key） | ✅ | ✅ 解碼後 32 bytes |
| `APP_SECRET_ENC_KEY_VERSION` | `app.crypto.current-key-version` | 新資料用哪個 key 版本加密 | 否 | `1`（`≤0` 亦回退為 1） | ✅ | — |
| `APP_SECRET_ENC_KEY_V2` | `app.crypto.keys["2"]` | 輪替期間的第二把 key；空字串視同未設定 | 否 | 空 | ✅ | — |
| `APP_PUBLIC_BASE_URL` | `AppProperties.publicBaseUrl` | 產生 enrollment 一次性連結的絕對 URL | **是**（prod） | `http://localhost:8080` | ✅ | — |
| `APP_OWNER_LINE_USER_ID` | `AppProperties.ownerLineUserId` | 啟動時 upsert `is_owner=true`；逗號分隔可多個 | 否 | 空 | ✅ | — |
| `APP_ALLOWED_URI_HOSTS` | `AppProperties.allowedUriHosts` | 通知內容允許出現的連結網域；**空 = 不允許任何外部連結**；子網域自動涵蓋 | 否 | 空 | ✅ | — |
| `APP_ADMIN_USERNAME` | `AdminAuthProperties.username` | 管理介面帳號；空 = **整條 `/admin/**` chain 不註冊** | 否 | 空 | ✅ | — |
| `APP_ADMIN_PASSWORD` | `AdminAuthProperties.password` | 管理介面密碼明文，啟動時 bcrypt 雜湊 | 否 | 空 | ✅ | —（應用啟動時驗證 ≥12 字元） |
| `APP_DISPATCH_POLL_INTERVAL` | `DispatchProperties.pollInterval` | 派送器排程取件間隔（最壞延遲） | 否 | `PT10S` | ✅ | ❌ |
| `APP_DISPATCH_BATCH_LIMIT` | `DispatchProperties.batchLimit` | 單輪取幾批（`≤0` 回退 10） | 否 | `10` | ✅ | ❌ |
| `LINE_RATE_LIMIT_PER_SECOND` | `LineApiProperties.rateLimitPerSecond` | 我方主動限速（LINE multicast 上限 200 req/s，只用一半） | 否 | `100`（`≤0` 回退 100） | ✅ | ❌ |
| `SPRING_PROFILES_ACTIVE` | Spring 內建 | 啟用的 profile | **是**（prod） | 無 | ✅ | — |
| `APP_DISPATCH_LEASE` | `DispatchProperties.lease` | 取件租約，把 `next_attempt_at` 推後以避免重複取件 | 否 | `PT2M` | **❌ 缺** | ❌ |
| `APP_DISPATCH_ENABLED` | `DispatchProperties.enabled` | 關掉派送器（javadoc 註明「給整合測試用，正式環境不要關」） | 否 | `true` | **❌ 缺** | ❌ |
| `APP_RATE_LIMIT_PER_MINUTE` | `app.rate-limit.default-per-minute`（`RateLimitFilter` 的 `@Value`） | 呼叫端預設每分鐘上限；`client.rate_limit_per_min` 可個別覆寫 | 否 | `60` | **❌ 缺** | ❌ |
| `LINE_API_BASE_URL` | `LineApiProperties.apiBaseUrl` | LINE API base URL；測試指向 MockWebServer | 否 | `https://api.line.me` | **❌ 缺** | ❌ |
| `APP_PROFILE_SYNC_CRON` | `app.profile-sync.cron` | 每日 LINE profile 同步排程 | 否 | `0 0 3 * * *` | **❌ 缺** | ❌ |
| `APP_MONITOR_ENABLED` | `MonitorProperties.enabled` | 關掉則 `ApiMonitorScheduler` 不註冊 | 否 | `true` | **❌ 缺** | ❌ |
| `APP_MONITOR_POLL_INTERVAL` | `MonitorProperties.pollInterval` | 監控排程輪詢間隔 | 否 | `PT10S` | **❌ 缺** | ❌ |
| `APP_MONITOR_CLAIM_LIMIT` | `MonitorProperties.claimLimit` | 單輪取件上限（`≤0` 回退 5） | 否 | `5` | **❌ 缺** | ❌ |
| `APP_MONITOR_LEASE` | `MonitorProperties.lease` | 監控租約，推後 `next_run_at` | 否 | `PT2M` | **❌ 缺** | ❌ |
| `APP_MONITOR_MIN_INTERVAL` | `MonitorProperties.minInterval` | 使用者可設定的最小輪詢間隔（DB 另有 `interval_seconds >= 30` 硬底線） | 否 | `PT60S` | **❌ 缺** | ❌ |
| `APP_MONITOR_CONNECT_TIMEOUT` | `MonitorProperties.connectTimeout` | 抓取目標 API 的連線逾時 | 否 | `PT5S` | **❌ 缺** | ❌ |
| `APP_MONITOR_READ_TIMEOUT` | `MonitorProperties.readTimeout` | 抓取目標 API 的讀取逾時 | 否 | `PT10S` | **❌ 缺** | ❌ |
| `APP_MONITOR_MAX_BODY_BYTES` | `MonitorProperties.maxBodyBytes` | 回應大小上限，邊讀邊擋（`≤0` 回退 1048576） | 否 | `1048576` | **❌ 缺** | ❌ |
| `APP_MONITOR_ALLOWED_HOSTS` | `MonitorProperties.allowedHosts` | 監控目標白名單 🔴 **空 = 允許任何公開網域** | 否 | 空 | **❌ 缺** | ❌ |
| `APP_MONITOR_FAILURE_NOTIFY_THRESHOLD` | `MonitorProperties.failureNotifyThreshold` | 連續失敗幾次才發失敗通知（`≤0` 回退 3） | 否 | `3` | **❌ 缺** | ❌ |
| `APP_MONITOR_RUN_RETENTION` | `MonitorProperties.runRetention` | `api_monitor_run` 保留期 | 否 | `P14D` | **❌ 缺** | ❌ |
| `APP_MONITOR_SEEN_ITEM_RETENTION` | `MonitorProperties.seenItemRetention` | `api_monitor_seen_item` 保留期 | 否 | `P90D` | **❌ 缺** | ❌ |

> `APP_PROFILE_SYNC_CRON` 與 `APP_DISPATCH_ENABLED` 這兩項連 `application.yml` 都沒列，
> 只能靠 Spring 的 relaxed binding 從環境變數注入。

#### 落差一：`.env.example` 有、但應用完全不讀

| 變數 | 實際被誰使用 |
|---|---|
| `APP_PORT` | **純基礎設施用途**：compose 的 port mapping `127.0.0.1:${APP_PORT:-19080}:8080`、私人 deploy／admin workflows 的健康檢查 URL、nginx upstream（範例為 `127.0.0.1:19080`）。Spring 端 `server.port` 固定 `8080`，跟這個變數無關 |

另有 `APP_GIT_SHA`：在 image 中由 build-arg `GIT_SHA` 寫成 `ENV`，但**沒有任何程式讀取**。

#### 落差二：程式要讀、但 `.env.example` 沒寫 —— 共 17 項

`APP_DISPATCH_LEASE`、`APP_DISPATCH_ENABLED`、`APP_RATE_LIMIT_PER_MINUTE`、
`LINE_API_BASE_URL`、`APP_PROFILE_SYNC_CRON`，以及 `APP_MONITOR_*` 的 12 個。

這些全部有程式碼層預設值，所以**缺漏不會導致啟動失敗**；維運者仍可直接寫入主機 `.env`，
但範本沒列就很難知道它們存在。

> 🔴 **`APP_MONITOR_ALLOWED_HOSTS` 是這 17 項裡最需要注意的一個。**
> 它的空值語意是「**允許打任何公開網域**」，與 `APP_ALLOWED_URI_HOSTS`
> 的「空 = 不允許任何外部連結」**完全相反**。
>
> 也就是說：一個沒讀過 `MonitorProperties` 原始碼的維運者，不會知道
> API 監控功能預設可以對任意公開網域發出請求；而範本檔完全沒有提到這個開關。
> 這是一個**未被曝光的安全設定**，收斂它的第一步就是把它寫進 `.env.example`。

（`ApiMonitorFetcher` 仍有 IP 層防護，白名單是額外的一道；細節見
[11-API監控輪詢設計](11-API監控輪詢設計.md)。）

### 5.2 `.env.example`

以下是節錄；完整內容以 repo 根目錄的 `.env.example` 為準
（實際檔案另含管理介面、派送、部署三段的大量說明註解）。

```bash
# ── LINE ──────────────────────────────────────────
LINE_CHANNEL_TOKEN=
LINE_CHANNEL_SECRET=

# ── 應用 ──────────────────────────────────────────
# 32 bytes 的 base64。產生方式：openssl rand -base64 32
# ⚠️ 遺失 = 所有 client secret 無法解密，只能全體重發
APP_SECRET_ENC_KEY=

# 只在輪替期間需要，平時留空（空字串會被忽略）
APP_SECRET_ENC_KEY_VERSION=1
APP_SECRET_ENC_KEY_V2=

# 產生 enrollment 連結用，必須是 https
APP_PUBLIC_BASE_URL=https://notify.example.com

# 初始管理者的 LINE User ID（啟動時 upsert is_owner=true）
# 取得方式：加 Bot 好友後傳「我的ID」
APP_OWNER_LINE_USER_ID=

# 通知內容允許出現的連結網域，逗號分隔。
# 留空 = 不允許任何外部連結（安全的預設值）。子網域自動涵蓋。
APP_ALLOWED_URI_HOSTS=

# ── 管理介面 ──────────────────────────────────────
# 兩者留空 = 整個管理介面停用（連登入頁都不存在）。
# 密碼至少 12 字元，啟動時檢查。
APP_ADMIN_USERNAME=
APP_ADMIN_PASSWORD=

# ── 派送 ──────────────────────────────────────────
APP_DISPATCH_POLL_INTERVAL=PT10S
APP_DISPATCH_BATCH_LIMIT=10
LINE_RATE_LIMIT_PER_SECOND=100

# ── 資料庫 ────────────────────────────────────────
DB_URL=jdbc:postgresql://postgres:5432/notifyline
DB_USER=notifyline
DB_PASSWORD=

# ── 部署 ──────────────────────────────────────────
# 主機上要對應的 port。只綁 127.0.0.1，由同機的 nginx 反向代理對外。
# 換 port 前先確認沒被占用：  ss -ltnp | grep :19080
# ⚠️ 改這裡也要同步改 nginx 的 upstream（那邊硬寫 127.0.0.1:19080）
APP_PORT=19080

# ── Spring ────────────────────────────────────────
SPRING_PROFILES_ACTIVE=prod
```

> ⚠️ 上面看不到的 17 個變數就是 [§5.1 落差二](#落差二程式要讀但-envexample-沒寫--共-17-項) 列的那些。

### 5.3 `application.yml` 要點

```yaml
spring:
  jpa:
    hibernate.ddl-auto: validate        # schema 一律由 Flyway 管
    open-in-view: false                 # 避免 view 層意外觸發查詢
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080                            # 固定；主機側 port 由 APP_PORT 決定
  forward-headers-strategy: framework   # 反向代理後方才能取得正確 scheme/host
  shutdown: graceful
  error:                                # 錯誤回應一律不外洩內部資訊
    include-message: never
    include-stacktrace: never
    include-binding-errors: never
    include-exception: false

line.bot:
  channel-token: ${LINE_CHANNEL_TOKEN}
  channel-secret: ${LINE_CHANNEL_SECRET}
  handler.path: /line/webhook

management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health:
    probes.enabled: true                # 啟用 liveness / readiness
    show-details: never                 # dev profile 覆寫為 always；prod 明確再設一次 never
  health:
    livenessstate.enabled: true
    readinessstate.enabled: true
```

`forward-headers-strategy: framework` 是必要的——沒有它，`APP_PUBLIC_BASE_URL` 以外的地方（例如錯誤頁的絕對連結）會拿到容器內的 `http://localhost:8080`。

其他執行期要點：

| 設定 | 值 | 說明 |
|---|---|---|
| `spring.task.scheduling.pool.size` | `3` | 預設 1 會讓派送器排在 03:00 的 profile 同步後面而停擺 |
| `spring.task.scheduling.shutdown` | `await-termination: true`、`await-termination-period: 30s` | 配合 `server.shutdown: graceful` |
| Hikari | `maximum-pool-size: 10`、`connection-timeout: 5000`（test profile 為 4 / `minimum-idle: 1`） | |
| `spring.jpa.open-in-view` | `false` | 避免 view 層意外觸發查詢 |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | `UTC` | |
| `spring.autoconfigure.exclude` | `UserDetailsServiceAutoConfiguration` | 避免 Spring Boot 生出預設的 in-memory 使用者 |

排程作業一覽（維運相關）：

| 類別 | 排程 | 工作 |
|---|---|---|
| `auth/NonceStore` | `fixedDelay PT5M` | `purgeExpired()` |
| `lineuser/ProfileSyncService` | `cron ${app.profile-sync.cron:0 0 3 * * *}` | `reconcileDaily()` |
| `monitor/ApiMonitorScheduler` | `fixedDelay ${app.monitor.poll-interval:PT10S}` | `poll()` |
| `monitor/ApiMonitorSweeper` | `cron 0 20 3 * * *` | `sweep()` |
| `notification/dispatch/DeliveryScheduler` | `fixedDelay ${app.dispatch.poll-interval:PT10S}` | `poll()` |
| `webhook/WebhookEventGuard` | `cron 0 15 3 * * *` | `purgeExpired()` |

> ⚠️ 這六個排程都**假設只有一個實例在跑**，沒有任何分散式鎖。見 [02 的缺口 G12](02-架構設計.md#g12--多實例的排程重複執行--加第二個實例前必須解決)。

### 5.4 啟動 fail-fast 檢查

任一項不通過就**拒絕啟動**：

- `APP_SECRET_ENC_KEY` 存在，base64 解碼後恰為 32 bytes，且能完成一次加解密 round-trip
- `LINE_CHANNEL_TOKEN` / `LINE_CHANNEL_SECRET` 非空
- `APP_PUBLIC_BASE_URL` 以 `https://` 開頭
- 資料庫可連線且 Flyway migration 已套用至最新版

**LINE token 的有效性檢查是 warn 不是 fail**——呼叫 `/v2/bot/info` 驗證，失敗只記 warn。理由：LINE 端暫時性故障不該讓我們的服務起不來，否則已排隊的通知會更難送出。

### 5.5 環境隔離（缺口 G14）

| 環境 | LINE Channel | 資料庫 |
|---|---|---|
| `test`（自動化） | **無**，一律 MockWebServer | Testcontainers |
| `dev` | **專用的測試 Official Account** | 本機或測試機 |
| `prod` | 正式 Official Account | 正式庫 |

> ⚠️ dev 用正式 Channel 測試，訊息會直接發到真實使用者手機上。**必須另建測試帳號。**

---

## 6. 可觀測性（缺口 G3、G9）

### 6.1 Correlation ID

一個請求跨越「HTTP 執行緒 → `@Async` 執行緒 → N 次 LINE 呼叫 → 可能還有 redriver 在幾分鐘後重試」。沒有貫穿的識別碼，事故當下無法把 log 串起來。

實作在 `server/src/main/java/com/jason/notifyline/observability/RequestIdFilter.java`
（`@Component` + `@Order(Ordered.HIGHEST_PRECEDENCE)`，繼承 `OncePerRequestFilter`）。
**刻意放在最外層**，讓認證失敗（401）的回應也帶得到 requestId。

| 機制 | 說明 |
|---|---|
| 產生 | 沿用呼叫端的 `X-Request-Id`；未傳或空白則 `UUID.randomUUID()` |
| 正規化 | `trim()` → 超過 `MAX_LENGTH = 64` 截斷 → `replaceAll("[^A-Za-z0-9_.:-]", "_")` |
| 傳遞 | `MDC.put("requestId", …)`；`finally` 一定 `MDC.clear()`（執行緒重用，不清會讓下一個請求繼承錯誤 context） |
| 回傳 | 回應 header 帶 `X-Request-Id`，錯誤 body 也帶 |
| 落地 | 存進 `notification.request_id` |

`RequestContext` 另定義 `clientId`、`notificationId`、`batchNo` 三個 MDC key，
以及 request attribute `notifyline.principal`（由 `HmacAuthFilter` 放入）。

**LINE 的 `x-line-request-id` 一律記錄並存進 `notification_delivery`**——這是跟 LINE 客服對帳的唯一憑據，事後無法補。

> `@Async` 由 `AsyncConfig` 的 `TaskDecorator` 複製並清理 MDC；`@Scheduled` 沒有原始 HTTP request，因此只能使用工作本身保存的 request id 或建立新的 context。詳見 [07 §8.1](07-非同步與可靠性設計.md#81-mdc-傳遞缺口-g3)。

**log 設定現況**：

- **沒有 `logback.xml` / `logback-spring.xml`**，全部走 `application*.yml` 的 `logging.*`
- console pattern：`%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{requestId:-}] %logger{36} - %msg%n`
- level：預設 `root: INFO` / `com.jason.notifyline: INFO`；`dev` 加 `com.jason.notifyline: DEBUG` + `org.flywaydb: DEBUG`；`prod` 另加 `org.springframework.web: WARN`；`test` 為 `root: WARN`
- **只寫 stdout**，沒有檔案輸出、沒有 JSON structured logging、沒有集中式收集 ——
  事故排查只能 `docker compose logs` + `grep requestId`

### 6.2 指標（Micrometer）

> 🔴 **以下是規劃，不是現況。**
>
> 專案已依賴 `io.micrometer:micrometer-registry-prometheus`（runtime scope），
> 但 `grep -rn "MeterRegistry\|Counter\.\|Timer\.\|@Timed\|Gauge\." server/src/main/java`
> **零命中** —— 目前**沒有任何自訂 metric**，只有 Spring Boot / Micrometer 自動註冊的那些。
>
> 而且 `/actuator/prometheus` **內外都抓不到**：
> `management.endpoints.web.exposure.include` 雖然開了 `prometheus`，但
> `SecurityConfig.PUBLIC_PATHS` 只放行 `/actuator/health` 與 `/actuator/health/**`，
> 其餘落到 `.anyRequest().denyAll()` → **403**；nginx 那邊也只放行
> `/actuator/health`，`/actuator/prometheus` 落到 `location / { return 404; }` → **404**。
> `/actuator/info` 同樣是 403 / 404。
>
> repo 內也**沒有任何 Prometheus server 或 Grafana 設定進版控**。
> 要讓下表成真，至少要做三件事：實作 metric、在 `SecurityConfig` 開一條受控路徑、
> 架設抓取端。

| 規劃指標 | 型別 | 標籤 |
|---|---|---|
| `notification.submitted` | Counter | `target_type`, `client_id` |
| `notification.recipients` | Counter | `target_type` |
| `notification.rejected` | Counter | `reason`（scope / quota / rate） |
| `delivery.batch.sent` | Counter | `status` |
| `delivery.batch.duration` | Timer | — |
| `delivery.queue.depth` | Gauge | PENDING 批次數 |
| `delivery.queue.oldest_age_seconds` | Gauge | 最舊 PENDING 的等待秒數 |
| `line.api.call` | Timer | `endpoint`, `outcome` |
| `line.quota.remaining` | Gauge | 每小時更新 |
| `circuitbreaker.state` | Gauge | — |

> `queue.oldest_age_seconds` 比 `queue.depth` 更有用。深度 100 但都是剛進來的，沒問題；深度 3 但最舊的等了 20 分鐘，代表卡住了。

### 6.3 Health check 分層

| 探針 | 檢查 | 用途 | Spring Security | nginx |
|---|---|---|---|---|
| `/actuator/health/liveness` | JVM 存活 | 容器重啟判定 | `permitAll` | 放行 |
| `/actuator/health/readiness` | JVM + **資料庫** | 是否接受流量 | `permitAll` | 放行 |
| `/actuator/health` | 上述 + LINE 狀態（僅 `details`） | 人工判讀 | `permitAll` | 放行（前綴比對，`access_log off`） |
| `/actuator/prometheus`、`/actuator/info` | — | — | **403**（`denyAll`） | **404**（`location /`） |

實際被誰用：compose 的 `healthcheck`（容器內 `localhost:8080`）、
deploy job 的 180 秒健康檢查迴圈（`127.0.0.1:$APP_PORT`）、
私人 `admin.yml` 的 `status` 與 `restart`。

> `show-details: never`（`dev` profile 覆寫為 `always`，`prod` 明確再設一次 `never`）——
> 所以正式環境的 `/actuator/health` 回的是 `{"status":"UP"}` 而不含細項。
> deploy job 的健康檢查就是 `grep -q '"status":"UP"'`。

> ⚠️ **LINE API 不可達絕對不能算 unhealthy。**
>
> 若算，LINE 端故障 → health check 失敗 → 容器被反覆重啟 → 已排隊的通知永遠送不出去。這會把「LINE 暫時掛了，等它好」變成「我們也一起掛了，而且資料在重啟中反覆中斷」。

### 6.4 自我監控告警（Phase 2）

> 🔴 **目前完全沒有告警。**
>
> 現存的健康檢查只有兩處：部署當下的 180 秒迴圈（跑完就結束），
> 以及 docker 的 `healthcheck`（只會重啟容器）。
> **沒有外部 uptime 監控、沒有任何通知管道。**
>
> 這件事的荒謬之處在於：**這是一個通知服務，但它自己掛掉的時候沒有人會被通知。**
> 唯一的發現途徑是有人剛好去跑私人 `admin.yml` 的 `status`，或是等使用者回報沒收到訊息。

以下為規劃。告警條件：佇列最舊等待 > 10 分鐘、批次失敗率 > 20%、LINE 月額度剩餘 < 10%、斷路器 `OPEN`、`SENDING` 超過 30 分鐘的 notification。

送給 OWNER。

> ⚠️ **遞迴風險**：告警本身是透過 LINE 發送的。LINE 掛掉時，「發送失敗」的告警也發不出去，而嘗試發送又會產生新的失敗與新的告警——無窮迴圈。
>
> 防護三點：(1) 告警走**獨立的直接 push 路徑**，不進 outbox 佇列；(2) 同類型告警**抑制窗口**至少 30 分鐘；(3) 告警發送失敗時**只記 log，絕不產生新告警**。

---

## 7. 金鑰生命週期

### 7.1 `APP_SECRET_ENC_KEY`

這把 key 加密所有 client secret（見 [03 §3](03-權限與認證設計.md#3-client-secret-的儲存)）。

> **備份選項、替代方案與完整取捨分析**見 [`deploy/金鑰管理.md`](../../deploy/金鑰管理.md)。

> ⚠️ **key 必須與資料庫備份分開存放。** 放在一起等於沒有加密。

#### 例行輪替

```text
1. openssl rand -base64 32              產生新 key
2. 以 APP_SECRET_ENC_KEY_V2 加入設定，舊 key 保留
3. 重啟，確認兩個版本都能被讀取；此時不要移除 v1
4. 先實作並審查一次性的重加密工具，再逐筆把 v1 資料改用 v2
5. 確認 client、monitor_secret、monitor_login 的各加密欄位都沒有 v1 資料
6. 把 active VERSION 切到 2，完成讀寫驗證後才移除 v1
```

版本欄位讓新舊 key 可以並存；目前缺少第 4 步的工具，因此「可不停機輪替」仍是能力缺口，不是已完成的維運功能。

> ⚠️ **第 4 步「重加密任務」目前沒有自動化。** 私人維運 workflow 沒有重加密或金鑰檢視指令；
> `.env.example` 與 [`deploy/金鑰管理.md`](../../deploy/金鑰管理.md)
> 只記載了程序，沒有可執行的東西。
> 現有 CLI 沒有重加密 action。在工具完成前，例行輪替應停在「新增 v2 並保留 v1」，不可移除舊 key。

#### 外洩

同輪替流程但**加速執行**，並且額外要求所有 client 重新取得 secret——舊 secret 已可能被解出，光是換加密 key 不夠。

#### 遺失 🔴

> **無法復原。** 沒有 key 就無法解密任何 client secret，所有呼叫端一起失效。

處置：

1. 產生新 key
2. `UPDATE client SET status = 'REVOKED'`（全部）
3. 通知所有使用者重新傳「申請金鑰」
4. 重建所有 SERVICE client 並通知各服務更新設定

**這是最貴的一種事故。** 預防措施：key 存在至少兩個獨立位置（例如密碼管理器 + 離線備份），且**不與 DB 備份同處**。

### 7.2 LINE Channel Token

輪替時在主機安全更新 `.env`，再從私人 Operations repo 以 `force` **重跑整個 deploy**。
workflow 不會覆蓋主機 `.env`。因為 token 只存在一處，不需要通知任何呼叫端。

Channel Secret 變更會讓所有 webhook 驗簽失敗，需同步更新。

> ⚠️ `LINE_CHANNEL_TOKEN` / `LINE_CHANNEL_SECRET` / `DB_PASSWORD` 與管理帳密都**沒有 workflow
> 熱更新路徑**；更新主機 `.env` 後須重啟或 `force` 部署。

### 7.3 Client Secret

- 使用者：傳「重設金鑰」自助處理
- SERVICE client：由 OWNER 重建並通知該服務
- **無法讀回明文**——這是刻意的設計，見 [03 §5.3](03-權限與認證設計.md#53-安全性分析)

---

## 8. 備份

> 🔴 **repo 內沒有任何備份機制。** `deploy/` 下只有 nginx 設定與兩份 md，
> **沒有 `pg_dump` 排程、沒有保留策略、沒有還原腳本**。下表是應有的樣子，不是現況。
>
> 這個缺口跟 [§7.1](#71-app_secret_enc_key) 疊在一起特別危險：
> `APP_SECRET_ENC_KEY` 遺失等同**所有 client secret 一次報廢**，
> 正式 key 只在 server `.env`；必須另有一份與 DB 分離、受控且已驗證可還原的備份。
>
> 另外 `docker compose down -v` 會連 `pgdata` volume 一起刪，**沒有任何防呆**。

| 項目 | 頻率 | 存放 |
|---|---|---|
| PostgreSQL（`pg_dump`） | 每日 | **與加密 key 不同的位置** |
| `APP_SECRET_ENC_KEY` | 變更時 | 至少兩個獨立位置 |
| `.env`（不含 key） | 變更時 | 與 DB 備份同處可接受 |
| nginx `admin-gate.map` 密鑰 | 變更時 | 不進版控，目前只存在 server 上 |

**還原演練**：至少每季做一次「從備份還原到全新環境」的完整演練。沒演練過的備份等於沒有備份——最常見的失敗是「備份檔案其實是空的」或「還原時才發現少了某個設定」。

---

## 9. 故障排除

| 症狀 | 可能原因 | 怎麼查 |
|---|---|---|
| 應用起不來，log 有 `APP_SECRET_ENC_KEY` | key 缺失或長度不對 | 檢查 `.env`，base64 解碼後須為 32 bytes |
| 應用起不來，Flyway 錯誤 | migration 衝突，或有人手改過 schema | `SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC` |
| 應用起不來，Hibernate validate 失敗 | entity 與 schema 不一致 | log 會指出哪個欄位；補一個新的 migration，**不要改舊的** |
| LINE Console 的 Verify 失敗 | 端點不通、憑證問題、或空事件請求沒回 200 | `curl -X POST {url}/line/webhook -d '{"events":[]}'` 應回 200 |
| Webhook 收不到事件 | Console 的 webhook 未啟用、URL 錯、反向代理沒轉發 | 看 access log 有沒有進來 |
| 所有請求 `401 AUTH_INVALID_SIGNATURE` | 呼叫端 canonical string 組錯 | 對照 [05 §6](05-API契約.md#6-簽章範例)；最常見是**序列化兩次**導致簽的 body 與送的 body 不同 |
| 所有請求 `401 AUTH_TIMESTAMP_SKEW` | 呼叫端或 server 時鐘偏移 | 比對回應的 `Date` header 與呼叫端時間 |
| 通知一直 `QUEUED` | 派送器沒啟動，或在交易提交前就跑了 | 查 `delivery.queue.depth` 指標；檢查 `afterCommit` 觸發 |
| 通知一直 `SENDING` | 派送中途程序被 kill | redriver 會自動修復；若沒有，查 delivery 是否卡在非 PENDING 的狀態 |
| 大量 `FAILED` + `429` | LINE 月額度耗盡 | `GET /v2/bot/message/quota/consumption` |
| 大量 `FAILED` + `401` | Channel token 失效或被撤銷 | 到 LINE Console 重新發行 |
| 使用者說沒收到但狀態 `SUCCEEDED` | 使用者已封鎖 Bot | [06 §1.3(a)](06-LINE整合設計.md#a-對已封鎖使用者-multicast-仍然回-200)；查 `line_user.status` |
| 非同步段的 log 沒有 `requestId` | `TaskDecorator` 沒設定 | 見 [07 §8.1](07-非同步與可靠性設計.md#81-mdc-傳遞缺口-g3) |
| Runner 卡住不動 | 前一次部署未結束，或有人在跑私人 `admin.yml` | 兩者共用 `notifyline-production` concurrency group；檢查 runner 服務與私人 repo access |
| 資料庫變大很快 | 保留清理沒跑 | 檢查 `@Scheduled` log；手動跑一次清理 |
| `deploy` job 一開始就失敗，訊息提到 `docker info` | runner 使用者不在 `docker` 群組 | job 會直接印出 `usermod -aG docker` 的指示 |
| 部署成功，但 enrollment 連結指向 `http://localhost:8080` | 主機 `.env` 的 `APP_PUBLIC_BASE_URL` 缺失或錯誤 | 檢查 `$DEPLOY_PATH/.env`，值必須是正式 HTTPS base URL |
| nginx 回 502 / 連不到後端 | `APP_PORT` 改了但 nginx upstream 沒跟著改（**硬寫 `127.0.0.1:19080`**） | `ss -ltnp \| grep :19080` 對照 `.env` 的 `APP_PORT` |
| `/admin/` 回 403 | Cloudflare 沒注入 `X-Admin-Gate`，或 server 上的 `admin-gate.map` 密鑰不同 | 兩處都要對；改完 `systemctl reload nginx` |
| `/actuator/prometheus` 回 403 或 404 | **設計如此**：`SecurityConfig` 只放行 `/actuator/health*`（→403），nginx 也只放行 health（→404） | 見 [§6.2](#62-指標micrometer)；要開需同時改兩處 |
| 回滾成功但應用起不來，Flyway/validate 錯誤 | 新版本已經跑過 migration，回滾後舊版 entity 與新 schema 對不上 | **migration 沒有回滾機制**，見 [§2.3](#23-deploy)；只能往前修 |
| 磁碟被吃滿 | docker json-file log **沒有 `max-size` 上限** | `docker system df`；在 Compose 補 `logging.options.max-size/max-file` |
| 想知道線上跑的是哪個 commit | `/actuator/info` 沒有版本資訊，`APP_GIT_SHA` 沒人讀 | 只能 `docker inspect --format='{{.Config.Image}}' notifyline-app` 看 sha tag |

### 有用的查詢

```sql
-- 佇列健康度
SELECT status, count(*), min(next_attempt_at)
  FROM notification_delivery GROUP BY status;

-- 卡住的通知
SELECT * FROM notification
 WHERE status = 'SENDING' AND started_at < now() - interval '30 min';

-- 某 client 今日用量
SELECT sum(recipient_count) FROM notification
 WHERE client_id = ? AND created_at >= current_date;

-- 近期認證失敗
SELECT * FROM audit_log
 WHERE action LIKE 'AUTH_%' AND occurred_at > now() - interval '1 hour'
 ORDER BY occurred_at DESC;
```

---

**下一份** → [10-任務拆解](10-任務拆解.md)
