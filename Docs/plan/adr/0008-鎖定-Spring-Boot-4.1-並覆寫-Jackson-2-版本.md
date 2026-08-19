# ADR-0008 — 鎖定 Spring Boot 4.1 並覆寫 Jackson 2 版本

**狀態**：Accepted ｜ 2026-08-18

## 背景

專案為全新建置，需選定 Spring Boot 與 LINE SDK 版本。

規劃時對 Maven Central 實際查證（2026-08-18）：

| 元件 | 最新版 | 關鍵事實 |
|---|---|---|
| Spring Boot | **4.1.0**（2026-06 釋出） | 含 Spring Framework 7.0.8、Hibernate 7.4.1 |
| `line-bot-spring-boot-webmvc` | **10.1.0** | 其 POM 直接 `import spring-boot-dependencies:4.1.0` |

第二點很重要：**LINE SDK 10.1.0 本身就是建構在 Spring Boot 4.1.0 之上的**。上游已經驗證過這個組合。

### 發現的衝突

檢查依賴時發現 Spring Boot 4.1.0 的 BOM **同時管理兩套 Jackson**：

```xml
<jackson-2-bom.version>2.21.4</jackson-2-bom.version>   <!-- com.fasterxml.jackson -->
<jackson-bom.version>3.1.4</jackson-bom.version>         <!-- tools.jackson -->
```

Spring Boot 4 的 Spring MVC 預設用 Jackson 3（package 遷移到 `tools.jackson`），但仍為相容性保留 Jackson 2 的版本管理。

而 `line-bot-jackson:10.1.0` 明確依賴：

```xml
<artifactId>jackson-databind</artifactId>
<version>2.22.0</version>
```

**Maven 的 BOM 版本管理會壓過 transitive 依賴版本**，所以實際解析出來會是 **2.21.4**，而不是 LINE SDK 期望的 2.22.0。

## 決策

1. **採用 Spring Boot 4.1.0 + LINE SDK 10.1.0 + Java 21**
2. **在 `server/pom.xml` 覆寫 Jackson 2 的 BOM 版本**：

```xml
<properties>
    <jackson-2-bom.version>2.22.0</jackson-2-bom.version>
</properties>
```

3. **不排除任何一套 Jackson**——兩者共存

## 理由

### 為什麼選 Spring Boot 4.1

- LINE SDK 10.1.0 就是建構在它之上，版本相容性由上游保證。用更舊的 Spring Boot 反而要自己承擔相容性風險
- Java 21（本機已裝的 LTS）在支援範圍內
- 新專案沒有既有包袱，沒有理由起手就落後兩個主版本

### 為什麼是覆寫而非排除

兩套 Jackson 是**不同的 groupId 與 package**：

| | Jackson 2 | Jackson 3 |
|---|---|---|
| groupId | `com.fasterxml.jackson.core` | `tools.jackson.core` |
| package | `com.fasterxml.jackson.*` | `tools.jackson.*` |
| 使用者 | LINE SDK（透過 retrofit + 自己的 ObjectMapper） | Spring MVC |

**它們在 classpath 上不衝突**——不同的類別全名，各自載入各自的。LINE SDK 用自己的 ObjectMapper 序列化 LINE API 的請求／回應，完全不經過 Spring 的 `HttpMessageConverter`。

所以正確做法是「讓兩者都在，且讓 Jackson 2 是 LINE SDK 期望的版本」，而不是排除其中一方。排除 Jackson 2 會讓 LINE SDK 在執行期 `NoClassDefFoundError`；排除 Jackson 3 會讓 Spring MVC 無法序列化 JSON。

### 為什麼要對齊到 2.22.0 而非接受 2.21.4

2.21.4 與 2.22.0 是相近的 minor 版本，**很可能**可以正常運作。但：

- LINE SDK 是對 2.22.0 測試的，用它可以完全排除這個變數
- 差異一旦造成問題，症狀會是「某些 LINE message object 反序列化失敗」，而錯誤訊息通常指向欄位層級，極難聯想到是 Jackson 版本
- 覆寫一行 property 的成本近乎零

**用零成本換掉一個難診斷的風險，划算。**

## 已考慮的替代方案

| 方案 | 為什麼沒選 |
|---|---|
| **用 Spring Boot 3.5.x** | 較保守，但 LINE SDK 10.1.0 是對 4.1 建構的。用 3.5 要自己驗相容性，反而風險更高。且新專案沒有停留在舊版的理由 |
| **接受 2.21.4，不覆寫** | 可能沒事，但把一個難診斷的風險留在系統裡，只為了省一行設定 |
| **排除 LINE SDK 的 Jackson 2 依賴** | 會導致執行期 `NoClassDefFoundError`。SDK 需要它 |
| **強制 Spring MVC 也用 Jackson 2** | 逆著 Spring Boot 4 的預設走，未來升級時會不斷產生摩擦 |
| **用舊版 LINE SDK（6.x/9.x）** | 6.x 的 API 與 10.x 差異極大且已停止維護；9.x 沒有理由選 |

## 後果

**正面**

- 版本組合與上游驗證過的一致
- 兩套 Jackson 各司其職，互不干擾
- 排除了一個難診斷的潛在故障

**負面**

| 後果 | 緩解 |
|---|---|
| **Classpath 上有兩套 JSON 函式庫** | 是 Spring Boot 4 過渡期的現實，非本專案造成。撰寫程式碼時要注意 import 的是哪一套 |
| 覆寫 BOM property 是隱式行為，容易被日後升級時忽略 | `pom.xml` 中加**明確註解**說明原因並連結本 ADR |
| **Spring Boot 4.1 + LINE SDK 10.1 是相對新的組合**，社群案例少 | [10-任務拆解 T1](../10-任務拆解.md#t1--地基) 明訂**第一步就驗證** SDK auto-config 能起來、webhook 收得到事件 |
| 升級 Spring Boot 時要重新確認 Jackson 2 版本 | 記入升級檢查清單 |
| Jackson 3 遷移後 Jackson 2 的支援可能被移除 | 屆時需等 LINE SDK 遷移到 Jackson 3，或自行提供 ObjectMapper。開新 ADR 處理 |

**驗證方式**

```bash
mvn -f server/pom.xml dependency:tree | grep -i jackson
```

應看到 `com.fasterxml.jackson.core:jackson-databind:2.22.0` 與 `tools.jackson.core:jackson-databind:3.1.4` 並存。

同時，[08-測試計畫](../08-測試計畫.md#4-line-api-打樁測試) 的 LINE API 打樁測試會實際跑一次序列化／反序列化，若版本有問題會在測試階段就浮現，而不是上線後。
