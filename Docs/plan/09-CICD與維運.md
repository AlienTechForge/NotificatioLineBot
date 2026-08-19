# 09 — CI/CD 與維運

← [文件索引](README.md) ｜ 前一份 [08-測試計畫](08-測試計畫.md)

涵蓋：部署管線、可觀測性、環境設定、金鑰生命週期、故障排除。

---

## 1. 部署架構

見 [ADR-0010](adr/0010-CICD-改採-GHCR-加-SSH-部署.md)。全部 job 跑在 GitHub-hosted runner。

```text
push main
   │
   ▼
GitHub Actions（ubuntu-latest）
   ├── test    mvn verify（Testcontainers 自備 PostgreSQL + 覆蓋率門檻）
   ├── image   build → 推 GHCR，打 sha-<短碼> tag
   └── deploy  SSH → docker compose pull + up -d + 健康檢查 + 失敗回滾
                     │
                     ▼
              Server（notif.azndev.com）
                ├── nginx（反向代理，見 deploy/）
                ├── app 容器（從 GHCR 拉，只綁 127.0.0.1:${APP_PORT}）
                └── postgres 容器
```

**機密永遠不進 GitHub**：`.env` 由 server 端 root 管理（`chmod 600`），
deploy job 只送 `docker-compose.yml`。job 若發現 `.env` 不存在會直接失敗並明講，
不會用預設值硬跑。

## 2. Workflow

單一檔案 `.github/workflows/ci-cd.yml`，三個 job。

### 2.1 `test`

```yaml
runs-on: ubuntu-latest
steps:
  - checkout
  - setup-java 21 (temurin, cache: maven)
  - run: mvn -B -ntp -f server/pom.xml verify
  - upload jacoco report as artifact
```

`verify` 一次跑完三件事：

| 階段 | 內容 |
|---|---|
| surefire | `*Test` 單元測試 |
| failsafe | `*IT` 整合測試（Testcontainers 起真的 PostgreSQL） |
| jacoco `check` | 覆蓋率門檻（整體 80% / `auth` 95%），不達標直接失敗 |

**跑在 GitHub-hosted 是刻意的**：測試自備資料庫，不需要 server 上的任何東西。
PR 完全不接觸正式環境。

### 2.2 `image`

`needs: test`，且 `if: github.event_name != 'pull_request'` —— PR 不推 image。

用 `docker/metadata-action` 打 tag：

| 觸發 | 產生的 tag |
|---|---|
| push `main` | `main`、`latest`、`sha-<短碼>` |
| push tag `v1.2.3` | `1.2.3`、`1.2`、`sha-<短碼>` |

> **Dockerfile 不在根目錄**，所以 `file: docker/Dockerfile` 但 `context: .` ——
> 它要 COPY `server/` 與 `admin-ui/`。

### 2.3 `deploy`

`needs: image`，且 `if: github.ref == 'refs/heads/main'`。

```text
1. 檢查 SSH secrets 是否齊全 -> 缺就「跳過」而非失敗
2. 寫入部署金鑰、ssh-keyscan 目標主機
3. scp docker-compose.prod.yml -> $DEPLOY_PATH/docker-compose.yml
4. SSH 進去：
     確認 .env 存在（不存在直接失敗）
     docker inspect 記下目前的 image（供回滾）
     docker login ghcr.io（用 GITHUB_TOKEN，短效）
     NOTIFYLINE_IMAGE=<sha tag> docker compose pull && up -d
     輪詢 /actuator/health 最多 180 秒
     失敗 -> 印出日誌 + 用記下的舊 image 回滾
     docker logout
5. always: 刪除 runner 上的部署金鑰
```

**用 `sha-<短碼>` 而非 `latest` 部署**：`latest` 是浮動的，「現在跑的是哪一版」無法回答，
回滾也沒有明確目標。

`concurrency: { group: deploy-prod, cancel-in-progress: false }` ——
**取消部署到一半比讓它跑完更危險**。

**缺 secrets 時跳過而非失敗**：讓「先把 image 推上去、之後再接部署」是一條合法路徑，
不會每次 push 都看到紅燈。

## 3. 需要設定的 Secrets / Variables

`Settings` → `Secrets and variables` → `Actions`

| 名稱 | 類型 | 必要性 | 說明 |
|---|---|---|---|
| `GITHUB_TOKEN` | 內建 | 自動 | 推 GHCR 用，免設 |
| `DEPLOY_HOST` | secret | deploy 才需 | server 位址 |
| `DEPLOY_USER` | secret | deploy 才需 | SSH 帳號 |
| `DEPLOY_SSH_KEY` | secret | deploy 才需 | **專用部署私鑰全文**，不要用個人日常金鑰 |
| `DEPLOY_PORT` | variable | 非 22 才需 | SSH port |
| `DEPLOY_PATH` | variable | 選用 | 預設 `/opt/notifyline` |

一次性的 repo 設定：

- `Settings` → `Actions` → `General` → Workflow permissions = **Read and write**
  （讓內建 `GITHUB_TOKEN` 能推 GHCR）
- 首次 push 成功後，`Packages` → 該 package → `Manage Actions access` → 連結 repo

### 安全前提

| 措施 | 為什麼 |
|---|---|
| **Repo 保持 private** | 這條與 ADR-0006 時期一樣重要 |
| `permissions: contents: read` + `packages: write` | 不給多餘權限 |
| **禁用 `pull_request_target`** | 該事件會帶著 repo secrets 執行 PR 的程式碼 |
| **專用部署金鑰**，非個人日常金鑰 | 外洩時影響範圍可控 |
| 在 server 上限制該金鑰可執行的操作 | `authorized_keys` 的 `command=` 限制，或用專用低權限帳號 |
| deploy job 只在 `main` 觸發 | PR 碰不到部署路徑 |

> ⚠️ **相對 self-hosted runner 變差的一點**：多了一把能登入 server 的私鑰放在
> GitHub Secrets。若這個風險不可接受，改用 Watchtower —— 完全不需要
> GitHub → server 的憑證，代價是部署時間不可控且無法自動回滾。
> 完整取捨見 [ADR-0010](adr/0010-CICD-改採-GHCR-加-SSH-部署.md)。


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
RUN useradd -r -u 10001 -m appuser
WORKDIR /app
COPY --from=build /src/server/target/*.jar app.jar
USER appuser                                          # 非 root
ARG GIT_SHA=unknown
ENV APP_GIT_SHA=$GIT_SHA
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

先 copy `pom.xml` 再 `dependency:go-offline`，讓 Docker layer cache 在只改程式碼時不用重抓依賴。

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
    env_file: ../.env
    depends_on:
      postgres: { condition: service_healthy }
    ports:
      - "127.0.0.1:8080:8080"      # 只綁 localhost，對外由反向代理處理
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8080/actuator/health"]
      interval: 15s
      timeout: 3s
      retries: 5
      start_period: 40s
    restart: unless-stopped

volumes:
  pgdata:
```

> ⚠️ **本設定僅支援單一 app 實例。** 加開第二個實例前必須先處理 [缺口 G12](02-架構設計.md#g12--多實例的排程重複執行--加第二個實例前必須解決)（`@Scheduled` 重複執行）與 [07 §11](07-非同步與可靠性設計.md#11-已知限制與擴充路徑) 的速率限制 per-instance 問題。

`ports` 綁 `127.0.0.1` 而非 `0.0.0.0`——應用不直接對外，一律經反向代理。
主機 port 由 `.env` 的 `APP_PORT` 決定（預設 19080；8080 在多數機器上已被占用）。

### 反向代理

實際的 nginx 設定與部署步驟見 [`deploy/`](../../deploy/README.md)。幾個**不能照抄一般範本**的地方：

| 設定 | 為什麼 |
|---|---|
| **不開** `proxy_intercept_errors`、**不 include** 自訂錯誤頁 | 這個 API 的 4xx **本身就是契約** —— 呼叫端要靠 `error.code` 分支處理。換成 HTML 錯誤頁對方就無法判讀 |
| `proxy_request_buffering off`，且不套任何改動 body 的模組 | **HMAC 驗簽是對原始 body bytes 做的**。gzip、sub_filter、ModSecurity 的改寫都會讓簽章對不上，而錯誤只會是一句「Invalid signature」 |
| 只開 `/actuator/health`，不開整個 `/actuator` | `/actuator/env`、`/actuator/configprops` 會洩漏設定值（呼應 [缺口 F7](10-任務拆解.md#t1-實作發現規劃時未預見已修正)） |
| `location / { return 404; }` 預設拒絕 | 日後新增端點要明確開放，不會有人不小心把管理介面暴露出去 |

> ⚠️ **不要只用 LINE Console 的「Verify」判斷是否接通。** 它只檢查是否回 200 ——
> 若反向代理還沒設好而 origin 回的是佔位頁，Verify 會顯示成功但事件根本沒進到 app。
> 驗證方式見 [`deploy/README.md` §5](../../deploy/README.md#5-驗證不要只信-line-的-verify-綠勾)。

---

## 5. 環境設定

### `.env.example`

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

# lineMessages 允許的連結網域，逗號分隔
APP_ALLOWED_URI_HOSTS=example.com,docs.example.com

# ── 資料庫 ────────────────────────────────────────
DB_URL=jdbc:postgresql://postgres:5432/notifyline
DB_USER=notifyline
DB_PASSWORD=

# ── Profile ───────────────────────────────────────
SPRING_PROFILES_ACTIVE=prod
```

### `application.yml` 要點

```yaml
spring:
  jpa:
    hibernate.ddl-auto: validate        # schema 一律由 Flyway 管
    open-in-view: false                 # 避免 view 層意外觸發查詢
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  forward-headers-strategy: framework   # 反向代理後方才能取得正確 scheme/host
  error.include-message: never          # 不外洩內部訊息

line.bot:
  channel-token: ${LINE_CHANNEL_TOKEN}
  channel-secret: ${LINE_CHANNEL_SECRET}
  handler.path: /line/webhook

management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health:
    probes.enabled: true                # 啟用 liveness / readiness
    show-details: when-authorized
```

`forward-headers-strategy: framework` 是必要的——沒有它，`APP_PUBLIC_BASE_URL` 以外的地方（例如錯誤頁的絕對連結）會拿到容器內的 `http://localhost:8080`。

### 啟動 fail-fast 檢查

任一項不通過就**拒絕啟動**：

- `APP_SECRET_ENC_KEY` 存在，base64 解碼後恰為 32 bytes，且能完成一次加解密 round-trip
- `LINE_CHANNEL_TOKEN` / `LINE_CHANNEL_SECRET` 非空
- `APP_PUBLIC_BASE_URL` 以 `https://` 開頭
- 資料庫可連線且 Flyway migration 已套用至最新版

**LINE token 的有效性檢查是 warn 不是 fail**——呼叫 `/v2/bot/info` 驗證，失敗只記 warn。理由：LINE 端暫時性故障不該讓我們的服務起不來，否則已排隊的通知會更難送出。

### 環境隔離（缺口 G14）

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

| 機制 | 說明 |
|---|---|
| 產生 | `RequestIdFilter` 沿用呼叫端的 `X-Request-Id`，沒有則產生 |
| 傳遞 | 放入 MDC；`@Async` 用 `TaskDecorator` 複製（見 [07 §8.1](07-非同步與可靠性設計.md#81-mdc-傳遞缺口-g3)） |
| 回傳 | 回應 header 帶 `X-Request-Id`，錯誤 body 也帶 |
| 落地 | 存進 `notification.request_id` |

**所有 log 固定帶**：`requestId`、`clientId`、`notificationId`、`batchNo`。

**LINE 的 `x-line-request-id` 一律記錄並存進 `notification_delivery`**——這是跟 LINE 客服對帳的唯一憑據，事後無法補。

### 6.2 指標（Micrometer）

| 指標 | 型別 | 標籤 |
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

| 探針 | 檢查 | 用途 |
|---|---|---|
| `/actuator/health/liveness` | JVM 存活 | 容器重啟判定 |
| `/actuator/health/readiness` | JVM + **資料庫** | 是否接受流量 |
| `/actuator/health` | 上述 + LINE 狀態（僅 `details`） | 人工判讀 |

> ⚠️ **LINE API 不可達絕對不能算 unhealthy。**
>
> 若算，LINE 端故障 → health check 失敗 → 容器被反覆重啟 → 已排隊的通知永遠送不出去。這會把「LINE 暫時掛了，等它好」變成「我們也一起掛了，而且資料在重啟中反覆中斷」。

### 6.4 自我監控告警（Phase 2）

告警條件：佇列最舊等待 > 10 分鐘、批次失敗率 > 20%、LINE 月額度剩餘 < 10%、斷路器 `OPEN`、`SENDING` 超過 30 分鐘的 notification。

送給 OWNER。

> ⚠️ **遞迴風險**：告警本身是透過 LINE 發送的。LINE 掛掉時，「發送失敗」的告警也發不出去，而嘗試發送又會產生新的失敗與新的告警——無窮迴圈。
>
> 防護三點：(1) 告警走**獨立的直接 push 路徑**，不進 outbox 佇列；(2) 同類型告警**抑制窗口**至少 30 分鐘；(3) 告警發送失敗時**只記 log，絕不產生新告警**。

---

## 7. 金鑰生命週期

### 7.1 `APP_SECRET_ENC_KEY`

這把 key 加密所有 client secret（見 [03 §3](03-權限與認證設計.md#3-client-secret-的儲存)）。

> ⚠️ **key 必須與資料庫備份分開存放。** 放在一起等於沒有加密。

#### 例行輪替

```text
1. openssl rand -base64 32              產生新 key
2. 以 APP_SECRET_ENC_KEY_V2 加入設定，舊 key 保留
3. 重啟應用（此時能用 v1 解密、用 v2 加密）
4. 執行重加密任務：逐筆用 v1 解密 → v2 加密 → secret_key_version = 2
5. 確認 SELECT count(*) WHERE secret_key_version = 1 為 0
6. 移除舊 key，把 V2 改名為主要 key
```

`secret_key_version` 欄位讓這個流程**不需要停機**。

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

輪替時直接更新 `.env` 並重啟。因為 token 只存在一處（本專案的初衷），不需要通知任何呼叫端。

Channel Secret 變更會讓所有 webhook 驗簽失敗，需同步更新。

### 7.3 Client Secret

- 使用者：傳「重設金鑰」自助處理
- SERVICE client：由 OWNER 重建並通知該服務
- **無法讀回明文**——這是刻意的設計，見 [03 §5.3](03-權限與認證設計.md#53-安全性分析)

---

## 8. 備份

| 項目 | 頻率 | 存放 |
|---|---|---|
| PostgreSQL（`pg_dump`） | 每日 | **與加密 key 不同的位置** |
| `APP_SECRET_ENC_KEY` | 變更時 | 至少兩個獨立位置 |
| `.env`（不含 key） | 變更時 | 與 DB 備份同處可接受 |

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
| Runner 卡住不動 | 前一次部署的 job 沒結束 | `concurrency` 群組會排隊；檢查 runner 服務狀態 |
| 資料庫變大很快 | 保留清理沒跑 | 檢查 `@Scheduled` log；手動跑一次清理 |

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
