# ADR — 架構決策紀錄

← [回到文件索引](../README.md)

記錄「當初為什麼這樣選」。設計文件說明**現在是什麼樣子**，ADR 說明**為什麼變成這樣**以及**考慮過但沒選的方案**。

## 索引

| # | 決策 | 狀態 |
|---|---|---|
| [0001](0001-採用-PostgreSQL-而非-SQLite.md) | 採用 PostgreSQL 而非 SQLite | Accepted |
| [0002](0002-client-secret-加密儲存而非雜湊.md) | Client secret 加密儲存而非雜湊 | Accepted |
| [0003](0003-ALL-採-multicast-而非-broadcast.md) | 全體發送採 multicast 而非 broadcast | Accepted |
| [0004](0004-權限採-scope-集合而非固定角色.md) | 權限採 scope 集合而非固定角色 | Accepted |
| [0005](0005-Admin-UI-單一-repo-打包進-jar.md) | Admin UI 置於同一 repo、打包進 jar | Accepted |
| [0006](0006-CICD-採-self-hosted-runner.md) | CI/CD 採 self-hosted runner | Accepted |
| [0007](0007-非同步採-DB-outbox-而非訊息中介.md) | 非同步採 DB outbox 而非訊息中介 | Accepted |
| [0008](0008-鎖定-Spring-Boot-4.1-並覆寫-Jackson-2-版本.md) | 鎖定 Spring Boot 4.1 並覆寫 Jackson 2 版本 | Accepted |
| [0009](0009-Admin-認證採-LINE-Login.md) | Admin 認證採 LINE Login | **Superseded by 0011** |
| [0010](0010-CICD-改採-GHCR-加-SSH-部署.md) | image 走 GHCR，部署仍由 self-hosted runner 執行（含一次判斷失誤的修正紀錄） | Accepted |
| [0011](0011-管理介面採帳密登入.md) | 管理介面採帳密登入，帳密來源為 GitHub Secrets | Accepted |

## 格式

每份 ADR 包含六段：

| 段落 | 內容 |
|---|---|
| **狀態** | Proposed / Accepted / Superseded by ADR-xxxx |
| **背景** | 當時面對什麼問題、有哪些限制 |
| **決策** | 選了什麼 |
| **理由** | 為什麼是它 |
| **已考慮的替代方案** | 還想過什麼、為什麼沒選 |
| **後果** | 帶來什麼好處**與代價**（負面後果必須寫，只寫好處的 ADR 沒有價值） |

## 什麼時候要開新 ADR

- 選擇會影響多個模組，且日後有人會問「為什麼不用 X」
- 決策有明顯的代價，需要記錄當時的取捨
- 推翻了先前的決策 → 開新的，並把舊的標為 `Superseded`

**不要**直接修改設計文件而讓 ADR 停在舊決策。設計文件描述現況，ADR 是歷史紀錄，兩者角色不同。
