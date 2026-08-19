# LINE 通知機器人－初步 SPEC

## 1. 專案目標

建立一套以 **Spring Boot** 為核心的通知服務，提供固定 HTTP Endpoint，讓不同的內部服務或專案可以透過 JSON 請求，將通知發送到 LINE。

主要目標是：

- 不同專案都能使用同一套通知服務
- 新增通知來源時，不需要修改 LINE Bot 主程式
- 可以通知自己、特定 LINE 使用者或所有使用者
- 確保只有授權的服務可以發送通知
- 後續可以加入管理介面與排程通知功能

---

## 2. 基本架構

```text
Service A
Service B
Service C
定時任務
其他系統
    │
    │ HTTPS + JSON
    ▼
Spring Boot Notification Server
    │
    ├── 身份驗證
    ├── 通知對象判斷
    ├── 通知內容處理
    ├── LINE User 管理
    └── 通知紀錄
    │
    ▼
LINE Messaging API
    │
    ├── 自己
    ├── 特定使用者
    └── 全體使用者
```

---

## 3. 後端架構

主要使用：

- Java
- Spring Boot
- Spring Security
- LINE Messaging API
- Database
- HMAC-SHA256 身份驗證

Spring Boot 提供統一通知 API，例如：

```http
POST /api/v1/notifications
```

所有需要通知功能的專案都呼叫同一個 Endpoint。

---

## 4. 身份驗證

每個專案配置自己的：

```text
Client ID
Client Secret
```

例如：

```text
Server Monitor → Client A
Website → Client B
Backup Service → Client C
```

Client 使用自己的 Secret 對 Request 產生 HMAC Signature。

Spring Boot 驗證 Signature 正確後才允許發送通知。

因此不同專案可以獨立管理及停用，不需要共用同一組金鑰。

---

## 5. 通知對象

通知 API 至少需要支援：

```text
SELF
USER
ALL
```

### SELF

通知自己的 LINE。

### USER

通知指定的 LINE User。

### ALL

通知所有加入 Bot 且目前有效的使用者。

---

## 6. LINE User 管理

透過 LINE Webhook 接收：

```text
加入好友
封鎖 / 解除好友
其他必要事件
```

並透過 LINE Profile API 取得：

```text
LINE User ID
Display Name
Profile Picture
```

將使用者資訊儲存在 Database。

之後通知指定 User 時，以 LINE User ID 作為實際發送依據。

---

## 7. Database

資料庫主要保存：

```text
Client
Client Secret / 驗證資訊
LINE User
通知紀錄
```

初期可使用 SQLite。

如果後續規模增加，可以改成 PostgreSQL。

---

## 8. 管理介面

後續可以在同一個 Spring Boot 專案增加 Web Admin Panel。

主要用途：

```text
管理 Client
管理 LINE User
查看通知紀錄
手動發送通知
設定通知對象
設定定時通知
```

---

## 9. 定時通知

後續管理介面可以建立定時通知。

例如：

```text
每天 08:00 發送
每週一發送
指定日期時間發送
```

初期可使用：

```text
Spring Scheduler
```

如果未來需要動態新增、修改及大量管理排程，可以改為：

```text
Quartz
```

---

## 10. 整體結果

最終架構：

```text
各個專案
    │
    ▼
統一 Notification API
    │
    ▼
身份驗證
    │
    ▼
Notification Service
    │
    ▼
LINE Messaging API
    │
    ▼
LINE User
```

各個專案只需要負責：

```text
建立通知內容
↓
使用自己的 Client Secret 簽章
↓
呼叫 Notification API
```

而所有：

```text
LINE Token
LINE SDK
LINE User
通知 Routing
權限
通知紀錄
```

都集中由 Notification Server 管理。

---

## 11. 初步技術選型

```text
Backend        Java + Spring Boot
Security       Spring Security + HMAC-SHA256
LINE           LINE Messaging API
Database       SQLite → PostgreSQL
Scheduler      Spring Scheduler → Quartz
Admin Panel    Spring Boot + Web Frontend
Protocol       HTTPS + JSON REST API
```

這個專案初期定位為：

> **統一 LINE Notification Service**

後續如果有需求，再逐步擴充成可以支援 LINE、Discord、Email 等不同通知管道的 Notification Gateway。