# 部署與操作指南

> 校訂日期：2026-09-21。公開原始碼 repo 不含 workflow；以私人
> `AlienTechForge/NotificatioLineBot-Operations` 的 workflows 與本 repo Compose 為準。
> 文件使用 `notify.example.com` 作為示例，正式網域與帳號只存在部署環境。

## 1. 部署架構

公開 `AlienTechForge/NotificatioLineBot` 維持 public，但 Actions 已停用。私人 Operations repo 每
10 分鐘檢查公開 `main`：

```text
公開 main 新 SHA
  → 私人 workflow 在 GitHub-hosted runner 執行 mvn verify
  → 建立私人 GHCR image（sha-<12碼SHA>）
  → Alien-Server self-hosted runner 執行 Compose
  → 健康檢查成功後記錄 .public-source-sha
```

無 SSH deploy，也不需要對外開放 22 port。排程可能延遲；需要立即部署時，到私人 repo 手動執行
`Sync public main and deploy`。勾選 `force` 可重新測試、建置與部署相同 SHA。

詳細決策見 [ADR-0013](../Docs/plan/adr/0013-公開原始碼與私人維運分離.md)。

## 2. 前置設定

- 組織 runner group 必須允許私人 Operations repo 使用 `alien-server`；公開 source repo 的 Actions 保持 disabled。
- runner 使用者可執行 Docker／Compose，且沒有 passwordless sudo。
- 私人 repo 的 Workflow permissions 允許 `Read and write permissions`，workflow 自身再縮到
  `contents: read`、`packages: write/read`。
- 公開 repo 的 Actions 設為 disabled，Fork PR approval policy 設為
  `Require approval for all outside collaborators`。
- 反向代理、TLS、DNS 與管理門禁由部署者配置；workflow 不更新 nginx。

Operations repo 只使用內建 `GITHUB_TOKEN` 存取自己的私人 GHCR package，不需 PAT 或 SSH key。
所有 GitHub／Docker Actions 都固定到完整 commit SHA。

## 3. 正式 `.env`

預設位置是 runner 使用者的 `$HOME/notifyline/.env`。若使用其他位置，在私人 repo 設定非敏感
repository variable `DEPLOY_PATH`。檔案權限必須是 `600`。

部署 workflow **不建立、不改寫、不輸出** `.env`，只確認下列必要值不是空字串：

- `LINE_CHANNEL_TOKEN`
- `LINE_CHANNEL_SECRET`
- `APP_SECRET_ENC_KEY`
- `DB_PASSWORD`

完整範例見 [`.env.example`](../.env.example)。至少另行確認：

| 設定 | 說明 |
|---|---|
| `APP_SECRET_ENC_KEY` | base64 解碼後 32 bytes；需與 DB 備份分開備份 |
| `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` | 同時設定或同時留空；密碼至少 12 字元 |
| `APP_PUBLIC_BASE_URL` | 部署者自己的 HTTPS base URL |
| `APP_ALLOWED_URI_HOSTS` | 通知內容允許的連結網域；空值拒絕全部外部連結 |
| `APP_OWNER_LINE_USER_ID` | 選填，屬個人識別資料，不放 GitHub Variables |
| `APP_PORT` | host loopback port，預設 `19080`；容器固定 `8080` |
| `DB_USER` | 預設 `notifyline` |

輪替機密時先在主機安全更新 `.env`，再從私人 Operations repo 手動執行 `force` 部署。不要把值
貼入 workflow input、summary、issue 或聊天紀錄。

## 4. Image、啟動與回滾

私人 workflow 建置：

```text
ghcr.io/alientechforge/notificationlinebot-operations:sha-<公開SHA前12碼>
```

部署會把 `docker/docker-compose.prod.yml` 複製為 `$DEPLOY_PATH/docker-compose.yml`，記錄目前
`notifyline-app` image，登入私人 GHCR，拉取確切 SHA image 後執行 `docker compose up -d`。

健康檢查最多 90 次、每次間隔 2 秒。若新容器在 180 秒內沒有回傳 `{"status":"UP"}`，workflow
以先前 image 執行回滾。回滾不還原資料庫 migration；新 schema 若與舊應用不相容，仍需向前修復。

成功後才將完整公開 SHA 寫入 `$DEPLOY_PATH/.public-source-sha`，下一輪排程用它判斷是否有新版本。

手動操作前先以短效、核准的憑證登入私人 GHCR，並使用 `--password-stdin`：

```bash
cd "$HOME/notifyline"
export NOTIFYLINE_IMAGE=ghcr.io/alientechforge/notificationlinebot-operations:sha-<12碼SHA>
docker compose --env-file .env pull app
docker compose --env-file .env up -d
curl -fsS http://127.0.0.1:19080/actuator/health
```

## 5. Nginx

[`nginx/notifyline.conf.example`](nginx/notifyline.conf.example) 只含示例網域與 loopback upstream，
不含正式 IP、憑證路徑或網域。依環境複製到主機後自行填值，不要把正式檔案提交回 repo。

| 路徑 | 行為 |
|---|---|
| `/api/` | 通知 API；`/api/v1/**` 由應用 HMAC 保護 |
| `/line/webhook` | LINE SDK 驗簽；proxy 不得改 body bytes |
| `/actuator/health` 及子路徑 | 健康檢查；其他 actuator 不公開 |
| `/admin` | 轉到 `/admin/` |
| `/admin/`、`/admin/login` | `X-Admin-Gate` 門禁，再由應用帳密／session 驗證 |
| 其他 | 404 |

`admin-gate.map.example` 的正式密鑰只放主機 `/etc/nginx/conf.d/admin-gate.map` 或 secret manager。
代理必須覆寫訪客提供的 `X-Admin-Gate`；應用登入仍是必要的第二層驗證。

## 6. 維運 Actions

私人 Operations repo 的 `Production operations` 只能手動執行，且與部署共用 concurrency group：

| 動作 | 用途 |
|---|---|
| `status` | Compose 狀態、健康回應及目前 image |
| `logs` | 私人 run summary 中顯示最近 120 行 app log |
| `restart` | 以目前 image 重啟 app 並等待健康檢查 |

建立、作廢 client、變更 owner 等資料操作一律使用 Admin UI。這些功能刻意不放進 Actions，以免
secret、LINE ID 或可注入的字串進入持久 log 與 production shell。

日誌可能含 LINE ID、監控 URL、host 及第三方錯誤摘要。不要下載後貼到公開 issue。

## 7. 故障排除

| 症狀 | 檢查方向 |
|---|---|
| 排程沒有執行 | 私人 repo default branch、Actions 是否 enabled、GitHub schedule 是否延遲 |
| Detect job 一直排隊 | runner service、runner group 是否允許私人 repo、labels 是否為 `self-hosted, Linux, X64` |
| 找不到 `.env` 或權限錯誤 | `DEPLOY_PATH` 與檔案權限 `600` |
| GHCR push／pull denied | 私人 repo Workflow permissions 與 package access |
| 測試失敗 | 私人 run 的 Maven log 與 JaCoCo artifact |
| webhook 401／403 | Channel Secret、proxy 是否改 body、是否落到錯誤的 security chain |
| 所有 client 認證失敗 | 加密 key 版本是否遺失；不可直接用新 key 覆蓋舊 key |
| 後台 404 | 管理帳密是否成對配置，更新 `.env` 後是否重啟 |
| 外部 502 | app health、loopback port 與 nginx upstream 是否一致 |

金鑰備份與輪替見 [`金鑰管理.md`](金鑰管理.md)，隱私風險與歷史清理狀態見
[`Docs/隱私與敏感資訊檢查.md`](../Docs/隱私與敏感資訊檢查.md)。
