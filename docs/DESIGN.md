# DIW 正式服務端設計（diw-wallet-server）

狀態：S1 已實作（中繼、健康檢查、設定、部署檔）；S2 以後為設計。

## 1. 目的與範圍

錢包的部分功能需要一台可公開連線的主機。`test-servers` 只供測試，不適合正式環境。本服務是獨立的 repo 與部署單位，與錢包的關係只有兩點：

- 協定相同：沿用錢包已使用的端點，App 不需要另外的分支邏輯。
- 共用程式碼：透過釘選版本引用錢包 repo 的 `core`（協定、簽章、zkTLS 查驗與回執）與原生程式庫。

| 功能 | 版本 | 說明 | 1 GB 主機 |
|---|---|---|---|
| 中繼 | v1 | `/zktls/relay/{room}`，兩端各連一次後轉送二進位訊框；只看得到 Noise 密文，不參與信任，不儲存 | 可 |
| zkTLS 查驗主機與 notary | v1 | `/zktls/profiles`、`/zktls/sessions`；以 TLSNotary proxy 模式查驗持有人的網頁證明，並簽發網頁證明回執（持有人主動產生時使用） | 一次一個工作階段，記憶體待實測 |
| zkPDF 證明器 | v2 | p4-zkp.md 第 8 節「送到自架主機產生」；主機會看到整份文件 | 不可，需數 GB 記憶體 |
| Bonds 年齡查驗主機 | 不做 | Bonds 有自己的查驗端；需要時再評估 | — |

## 2. 技術選擇

- Kotlin 2.2.21、Ktor 3.3.3（Netty）、JVM 21，與錢包一致。
- 不用 Rust 重寫：zkTLS 查驗、描述檔檢查與回執簽發都在錢包的 `core`（`ZkTlsVerification`、`ZkTlsCheck`、`WebProofCredentials`）。重寫一份會讓兩邊的協定逐漸分歧。
- `core` 的引用方式：錢包 repo 以 git submodule 釘選在指定 commit，用 Gradle composite build 建置。升級時改 submodule 指標，CI 驗證通過才發佈。
- 原生程式庫：在 CI 以錢包的 `native/build.sh` 建出 linux-x86_64 的 `tlsn` 版本（錢包的 `ZkNative` 目前只載入 linux-x86_64，arm64 主機要等錢包支援）。VPS 不編譯 Rust。
- 建置需要 Android SDK：錢包的 `core` 是含 Android 目標的 Kotlin Multiplatform 函式庫，composite build 會一併設定。只在 CI 與開發機需要，執行環境不需要。
- 容器映像只放建好的發行檔（`./gradlew installDist`），不在映像內編譯。

## 3. 對外介面

### 3.1 中繼

`GET /zktls/relay/{room}`（WebSocket）。`room` 是 16 bytes 的 base64url；每間最多兩條連線。限制都可設定：

| 限制 | 預設值 |
|---|---|
| 同時房數 | 64 |
| 存活時間 | 15 分鐘 |
| 雙向總量 | 256 MB（MPC 模式傳輸量為數十 MB） |
| 單一訊框 | 128 KB |
| 每個 IP 的同時連線 | 8 |

### 3.2 zkTLS 查驗主機

| 方法 | 路徑 | 認證 | 說明 |
|---|---|---|---|
| GET | `/zktls/profiles` | 無 | 可查驗的頁面（已安裝的簽章範本包） |
| POST | `/zktls/sessions` | `Authorization: Bearer <token>` | 建立工作階段，回傳簽章請求與輪詢權杖 |
| GET | `/zktls/sessions/{sid}?token=` | 輪詢權杖 | 狀態與結果 |
| GET | `/zktls/sessions/{sid}/ws` | 請求中的一次性工作階段 | 持有人連入並證明 |

建立工作階段必須帶存取權杖，避免主機被他人當成免費的 notary 或 proxy。proxy 模式下主機會連往目標網站，但只連已安裝描述檔的 `host` 與 `port`，不接受請求中夾帶的描述檔。

### 3.3 健康與資訊

- `GET /healthz`：程序與原生程式庫都正常時回 200，供容器與 systemd 檢查。
- `GET /info`：版本、啟用的功能，以及簽章憑證鏈的 SHA-256 指紋（供使用者在錢包內比對後加入信任）。

### 3.4 錢包端需要的修改（在錢包 repo）

- 設定的「查驗主機」新增選填的存取權杖，存在加密儲存，只送到該主機。
- 查驗主機的信任：主機憑證鏈若連到使用者匯入的根，判定為 T2；否則以 T3 強提示（沿用現有信任模型）。

## 4. 金鑰與信任

- 簽章金鑰：P-256，用來簽查驗請求（`x5c`）與網頁證明回執。
- 兩種來源：
  - 匯入：設定檔指定金鑰與憑證鏈的 PEM。
  - 自動產生：第一次啟動時產生自簽根與葉憑證，存進資料目錄（權限 0600）。之後重啟沿用，不會換鑰。
- 金鑰、權杖與憑證從不寫入 log。

## 5. 部署

TLS 由 Caddy 自動處理：自動申請與更新 Let's Encrypt 憑證、自動轉址 HTTPS、代理 WebSocket。不需要互動式 console。兩種部署方式擇一：

### 5.1 容器（建議）

- CI 建出多架構映像 `ghcr.io/<owner>/diw-wallet-server:<version>`（amd64、arm64），內含 JRE 與原生程式庫，以非 root 使用者執行。
- `compose.yaml` 包含 `server` 與 `caddy` 兩個服務：
  - `restart: unless-stopped`；
  - `healthcheck` 打 `/healthz`；
  - 資料目錄與 Caddy 憑證目錄各用一個 volume；
  - 只對外開 80 與 443。
- 使用者只需填 `.env`：網域、ACME 聯絡信箱、存取權杖。之後執行 `docker compose up -d`。

### 5.2 不用容器

- Release 附 tarball：jlink 精簡的 JRE、程式 jar、原生程式庫、`diw-server.service` 範例與 `Caddyfile` 範例。
- 以 systemd 管理：`Restart=on-failure`、`MemoryMax`、`DynamicUser` 或專用帳號、`StateDirectory`，log 寫進 journald。
- Caddy 用發行版套件安裝，同樣自動處理憑證。

### 5.3 設定

`key=value` 設定檔（預設 `/etc/diw-server/server.conf`，或以 `DIW_CONFIG` 指定），每個鍵都可用環境變數 `DIW_<鍵的大寫>` 覆寫。設定檔中的未知鍵會讓程式拒絕啟動；其他 `DIW_` 開頭的環境變數不視為設定。

| 鍵 | 預設 | 說明 |
|---|---|---|
| `public_url` | 必填 | 對外網址，例如 `https://diw.example.org` |
| `listen_host`、`listen_port` | `127.0.0.1`、`8080` | 由 Caddy 代理；容器內為 `0.0.0.0` |
| `trust_forwarded` | 監聽 loopback 時為 true | 是否採用代理轉送的用戶端位址（每個位址的連線上限依此計算） |
| `features` | `relay` | 啟用的功能，以逗號分隔 |
| `data_dir` | `/var/lib/diw-server` | 資料目錄（原生程式庫解壓、之後的金鑰） |
| `relay_max_rooms` 等 | 見 3.1 | 中繼限制 |

S2 會加入：存取權杖（只存 SHA-256）、金鑰來源、工作階段上限、範本包來源（與錢包相同的簽章範本包，以釘選公鑰驗證）。

## 6. 1 GB 主機的資源配置

- JVM：`-Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m`。S1 只開中繼時，閒置 RSS 實測約 144 MB。
- zkTLS 查驗同時只跑 1 個（可設定），其餘排隊或回 503。
- 建議加 1 GB swap。
- 驗收時在 `MemoryMax=900M` 的 cgroup 中跑完整的持有人查驗流程，記錄峰值 RSS。超出時降低工作階段上限，或只開中繼。

## 7. 隱私與安全

- 中繼只轉送密文。查驗主機只看到持有人揭露的回應片段，與錢包內查驗方看到的相同。查詢輸入（證號、驗證碼、cookie）不會出現在主機。
- 不保存逐字紀錄與查驗結果；工作階段過期即刪除。
- log 不含請求內容、揭露值、權杖與 IP 以外的識別資料。
- 依賴更新：Dependabot 或 Renovate；映像以 digest 釘選基底。

## 8. 里程碑與驗收

| 里程碑 | 內容 | 驗收 |
|---|---|---|
| S1 | repo 骨架、submodule、Gradle、中繼、健康檢查、Dockerfile、compose、systemd 範例 | 錢包的 relay 測試對容器通過；`compose up` 後 `/healthz` 為 200 |
| S2 | zkTLS 查驗主機與回執、存取權杖、金鑰管理；錢包加權杖設定 | 錢包 e2e 以容器為主機完成主動網頁證明；900 MB cgroup 內通過並記錄峰值 |
| S3 | CI 多架構映像、release tarball、部署文件 | 在乾淨 VPS 依文件部署，Caddy 取得憑證，手機可連線 |
| S4 | zkPDF 證明器（等 M3c） | 在大記憶體主機產生證明，手機驗證後使用 |
