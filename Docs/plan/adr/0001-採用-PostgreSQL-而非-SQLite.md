# ADR-0001 — 採用 PostgreSQL 而非 SQLite

> 校訂日期：2026-09-21。依 2026-09-21 main 的程式碼核對；歷史方案與未實作項目另行標示。

**狀態**：Accepted ｜ 2026-08-18

## 背景

[初步SPEC.md](../../初步SPEC.md) §7 寫「初期可使用 SQLite，如果後續規模增加，可以改成 PostgreSQL」。

這個方向在「先求簡單」的直覺上合理，但實際評估時發現三個具體阻礙：

1. **Spring Data JPA 對 SQLite 沒有官方支援**。Hibernate 7 的 SQLite 方言在 `hibernate-community-dialects`，屬社群維護，隨 Hibernate 版本升級有落後風險。
2. **SQLite 是單寫入者**。本系統的核心工作是「多個批次併發寫入 `notification_delivery` 狀態」與「redriver 併發取件」。SQLite 的寫入鎖會讓這些操作序列化。
3. **設計中用到多個 PostgreSQL 特有能力**：
   - `TEXT[]` 陣列（`notification_delivery.line_user_ids`）
   - `JSONB`（`notification.payload`、`audit_log.detail`）
   - `INET`（IP 記錄）
   - **`SELECT … FOR UPDATE SKIP LOCKED`**（outbox 取件，見 [ADR-0007](0007-非同步採-DB-outbox-而非訊息中介.md)）
   - Partial index（`WHERE status = 'ACTIVE'`）

其中 `SKIP LOCKED` 是 outbox 模式的**必要條件**，SQLite 完全沒有等價機制。

環境條件：Docker 已安裝，起一個 PostgreSQL 容器的成本接近零。

## 決策

**從第一天就用 PostgreSQL**，透過 `docker-compose.yml` 提供。

## 理由

- **省掉一次遷移**。SQLite → PostgreSQL 的遷移不只是換 driver：schema DDL 語法、型別對應、Flyway 腳本、以及所有依賴上述特有能力的程式碼都要改。這筆成本是確定會發生的，只是時間早晚。
- **設計不用自我設限**。若遷就 SQLite，`line_user_ids` 要改成關聯表或字串拼接、`payload` 要改成 TEXT、outbox 取件要自己實作樂觀鎖——每一項都讓系統變複雜且更容易寫錯。
- **Docker 已在環境中**，「不想多裝一個服務」這個 SQLite 的主要優勢在此不成立。
- **測試環境用 Testcontainers**，跑的是與正式環境相同的 PostgreSQL。用 SQLite 開發、PostgreSQL 上線是典型的「本機測試都過、上線才爆」來源。

## 已考慮的替代方案

| 方案 | 為什麼沒選 |
|---|---|
| **SQLite 先，之後遷移** | 遷移成本確定會發生；且設計會被 SQLite 的限制綁住，`SKIP LOCKED` 無替代 |
| **H2 file mode** | 純 Java 免安裝，方言比 SQLite 更接近 PostgreSQL。但正式環境不建議用 H2，等於仍要遷移，只是把問題推遲。且 H2 的陣列與 JSON 支援仍與 PostgreSQL 有差異 |
| **雲端託管 PostgreSQL** | 部署在自有 server，多一個外部相依與費用，沒有對應的好處 |

## 後果

**正面**

- 設計可以自由使用陣列、JSONB、partial index、`SKIP LOCKED`
- 開發、測試、正式環境資料庫一致
- 未來要水平擴充時資料庫層不需改動

**負面**

- 多一個容器要維運（備份、版本升級、磁碟監控）
- 本機開發需要 Docker，不能只靠一個檔案就跑起來
- 資源占用高於 SQLite（實際影響很小，但確實存在）

**緩解**：`docker-compose.yml` 把 PostgreSQL 一併起好，開發者只需 `docker compose up -d`；備份程序寫在 [09-CICD與維運](../09-CICD與維運.md#8-備份)。
