# DIW 正式服務端設計（diw-wallet-server）

狀態：S1 至 S3 已實作（中繼、zkTLS 查驗主機與回執、存取權杖、金鑰管理、附執行環境的發行檔、容器、systemd、Cloud Run、Cloudflare Containers），Bonds 年齡查驗主機、網頁查驗端與行動自然人憑證簽署（TWFIDO SP）已實作（可選功能）；S3 的實機部署驗收待使用者執行。S4（zkPDF 證明器）為設計。

## 1. 目的與範圍

錢包的部分功能需要一台可公開連線的主機。`test-servers` 只供測試，不適合正式環境。本服務是獨立的 repo 與部署單位，與錢包的關係只有兩點：

- 協定相同：沿用錢包已使用的端點，App 不需要另外的分支邏輯。
- 共用程式碼：透過釘選版本引用錢包 repo 的 `core`（協定、簽章、zkTLS 查驗與回執）與原生程式庫。

| 功能 | 版本 | 說明 | 1 GB 主機 |
|---|---|---|---|
| 中繼 | v1 | `/zktls/relay/{room}`，兩端各連一次後轉送二進位訊框；只看得到 Noise 密文，不參與信任，不儲存 | 可 |
| zkTLS 查驗主機與 notary | v1 | `/zktls/profiles`、`/zktls/sessions`；以 TLSNotary proxy 模式查驗持有人的網頁證明，並簽發網頁證明回執（持有人主動產生時使用） | 一次查驗一個；測試 JVM（主機與持有人同程序）峰值約 345 MB，cgroup 實測未做 |
| zkPDF 證明器 | v2 | p4-zkp.md 第 8 節「送到自架主機產生」；主機會看到整份文件 | 不可，需數 GB 記憶體 |
| 網頁查驗端 | v1（可選） | `/verify` 網頁與 `/api/oidvp/*`，以官方查驗端的方言向政府數位憑證皮夾請求卡片，也可查驗 Bonds 自然人憑證自發卡 | 可 |
| Bonds 年齡查驗主機 | v1（可選） | `/api/zkp/*`，與 Bonds 查驗網站相同；預設只轉送，由查驗方手機驗證，可設定在主機驗證 | 轉送可；主機驗證不可（需載入 432 MB 驗證金鑰） |
| 行動自然人憑證簽署 | v1（可選） | `/twfido/sign`，以服務端的 SP 帳號向內政部要求簽署，SP 金鑰不放進 App | 可 |

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

房間結束時，中繼以關閉原因告訴兩端是哪種情況，錢包據此說明是誰或哪個限制中斷了連線：`Peer left`（對方離開）、`Room limit`（超過總量或單一訊框）、`Room expired`（超過存活時間）。拒絕加入時是 `Room unavailable`（房間已滿或房數已達上限）、`Too many connections`（同一 IP 連線過多）、`Malformed room`。

### 3.2 zkTLS 查驗主機

| 方法 | 路徑 | 認證 | 說明 |
|---|---|---|---|
| GET | `/zktls/profiles` | 無 | 可查驗的頁面（已安裝的簽章範本包） |
| POST | `/zktls/sessions` | `Authorization: Bearer <token>` | 建立工作階段，回傳簽章請求與輪詢權杖 |
| GET | `/zktls/sessions/{sid}?token=` | 輪詢權杖 | 狀態與結果 |
| GET | `/zktls/sessions/{sid}/ws` | 請求中的一次性工作階段 | 持有人連入並證明 |

建立工作階段必須帶存取權杖，避免主機被他人當成免費的 notary 或 proxy。proxy 模式下主機會連往目標網站，但只連已安裝描述檔的 `host` 與 `port`，不接受請求中夾帶的描述檔。

- 建立：本體 `{"profile","fields","purpose"}`（上限 16 KB，`purpose` 截為 100 字），回傳 `sessionId`、輪詢用 `token`、`request`（`diw-zktls://` 連結）與 `expiresAt`。錢包的 `ZkTlsFlow` 主動查驗與替他人查驗都用這個端點。
- 請求以主機的簽章金鑰簽署（`x5c` 帶葉憑證與根），傳輸為 `wss://<public_url>/zktls/sessions/{sid}/ws`、模式 `proxy`。
- 查驗沿用錢包 `core` 的 `ZkTlsVerification.verify` 與 `ZkTlsCheck.check`；伺服器憑證的信任根為 Mozilla 公開根加錢包內建的 GPKI 錨點。持有人要求時以同一把金鑰簽發網頁證明回執（`WebProofCredentials.receipts`）。
- 狀態：`waiting`、`verifying`、`done`（附 `outcome`）、`failed`（附 `reason`）；簽發回執時加 `receipt: true`。
- 限制：同時工作階段 16、每個位址 4、同時查驗 1（其餘連線以 `TRY_AGAIN_LATER` 關閉，持有人重試）；到期後再保留 5 分鐘供輪詢。結果只在記憶體。
- 版型包：與錢包相同的簽章範本包（`zk-templates.jwt`），以錢包釘選的公鑰驗證；來源依序為資料目錄的快取、`zktls_templates_file`、錢包 Release。每 12 小時更新，較新的版本寫回快取。

### 3.3 健康與資訊

- `GET /healthz`：程序與原生程式庫都正常時回 200，供容器與 systemd 檢查。
- `GET /info`：版本、啟用的功能、原生程式庫的後端，以及 `signing`（根憑證主體、`rootSha256` 與根憑證 PEM，供使用者在錢包內比對後加入信任）與 `templatesVersion`。

### 3.4 錢包端的修改（在錢包 repo，已實作）

- 設定改為一個「正式服務端」網址，同時用於中繼、zkTLS 查驗主機與年齡查驗主機（`zkVerifierUrl`），不必分別填寫。
- 選填的存取權杖，存在加密儲存，只送到該主機。
- 加入服務端時一併加入信任（2026-10-25 使用者要求）：錢包讀取 `/info` 的簽章根憑證與 SHA-256 指紋，顯示給使用者確認後，以正式環境、zkTLS 查驗方與網頁證明回執發行者的角色加入自訂信任（與使用者匯入的根相同，T2）。之後主機簽的查驗請求與回執經 `TrustRegistry.decide` 判定為 T2。未加入信任時以 T3 強提示，仍可使用（沿用現有信任模型）。
- 同一次確認也信任網頁查驗端的 DID（`/info` 的 `verifierDid`）：加入錢包的使用者信任 DID（只有查驗方角色，T2），持卡人出示給這台服務端時不再顯示 T3。DID 只由使用者輸入的網址與 TLS 確認，沒有另外的指紋。
- 服務端換鑰或換查驗端 DID 時，錢包不自動接受，需使用者重新確認。
- 實作：錢包的 `ServerModel`（讀取 `/info`、自行計算指紋並與服務端回報的比對、以 `server:<host>` 來源加入查驗方與發行者兩個角色的使用者錨點，換鑰時移除舊錨點）、設定的「零知識證明」頁「正式服務端」區塊（網址、權杖、加入信任與換鑰警示），`ZkTlsFlow` 只對設定的服務端帶 `Authorization: Bearer`。

### 3.5 Bonds 年齡查驗主機（`bonds-age`）

API 與 Bonds 查驗網站及錢包的測試伺服器相同，錢包的年齡查驗流程（`BondsAgeVerifierFlow`）不需修改：

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/api/zkp/config` | 可用模式（`relay`，主機可驗證時加 `server`） |
| POST | `/api/zkp/sessions` | 建立請求，回傳請求 QR 內容與輪詢權杖 |
| POST | `/api/zkp/response/{id}` | 持有人送出證明包（上限 6 MB），回覆判定 |
| GET | `/api/zkp/sessions/{id}?token=` | 狀態；轉送模式下回傳待驗的證明包 |
| POST | `/api/zkp/sessions/{id}/verdict?token=` | 轉送模式下查驗方手機回傳判定，再轉給持有人 |

- 轉送模式（預設）：主機不驗證，只把證明包交給建立請求的查驗方，再把判定交回持有人。證明包只含證明，不含出生日期；回覆後即丟棄。
- 主機驗證（`bonds_age_verify=true`）：需要含 OpenAC 的原生程式庫，啟動時把兩把驗證金鑰（以釘選雜湊）安裝到資料目錄，完成前只轉送。一次只驗一個，忙碌時回 503 讓持有人重送。政府卡的發卡者需在 TWDIW 信任清單（T2 以上），自發卡標示為自我聲明。
- 限制：同時請求數 64、每個位址 8 個；請求 5 分鐘到期。

### 3.6 網頁查驗端（`verifier`）

查驗方在 `/verify` 輸入卡片類型代碼與要揭露的欄位，頁面顯示 QR Code，持卡人以皮夾掃描出示，頁面輪詢後顯示結果。也可只用 API。

| 方法 | 路徑 | 說明 |
|---|---|---|
| POST | `/api/oidvp/sessions` | 建立查驗（`kind` 為 `twdiw` 或 `moica`、`cardType`、`fields`、`purpose`），回傳 `modadigitalwallet://` 與 `openid4vp://` 連結與輪詢權杖；設定存取碼時需 `Authorization: Bearer` |
| GET | `/api/oidvp/request/{id}` | 簽章的請求物件（`oauth-authz-req+jwt`），回覆後不再提供 |
| POST | `/api/oidvp/response` | 皮夾的 `direct_post`（form），成功回 `{"code":"0"}` |
| GET | `/api/oidvp/sessions/{id}?token=` | 狀態與結果 |
| GET | `/api/oidvp/sessions/{id}/qr.svg?token=&scheme=` | QR Code |

- 方言依官方查驗端（錢包 docs/research/twdiw.md 1.4、4.3 節）：裸 did:key 的 client_id、以參照傳遞的簽章請求物件、Presentation Exchange 以 `$.type` 指定卡片類型、`direct_post`、jwt_vp 內含卡片的 SD-JWT。自發卡沿用 Bonds 的 `vc+moica` 描述。
- 檢查：持有者簽章與 `aud`、`nonce`；卡片的發卡者簽章、持有者綁定（`sub` 與 `cnf`）、效期、每個揭露都有發卡者承諾、卡片類型；發卡者需在 TWDIW 信任清單（T2 以上）才判定通過；有狀態清單時查詢撤銷狀態（逾時則註明未檢查）。自發卡以 MOICA 憑證鏈驗證，標示為持卡人自我聲明。
- 只顯示要求的欄位；官方皮夾以子字串比對選欄位，可能多送，多送的欄位不顯示也不保存。
- 查驗端身分：P-256 金鑰與其 did:key（jwk_jcs-pub），第一次啟動時產生在 `verifier_key_dir`（預設資料目錄下的 `verifier/`，權限 0600），之後沿用；`/info` 的 `verifierDid` 列出 DID。開源版官方皮夾查不到未註冊的 DID 時仍可繼續（推論新版也是如此，未實測）；要在同意畫面顯示機關名稱需向數位發展部註冊這個 DID。
- 結果只存在記憶體，請求到期後再保留 5 分鐘供頁面讀取，不寫入 log。

### 3.7 行動自然人憑證簽署（`twfido`）

2026-10-25 使用者要求：SP 服務代碼與 AES 金鑰只放在服務端，避免隨 App 發佈而外洩；錢包可選用服務端、編譯時設定的 SP 或開發者手動設定。

| 方法 | 路徑 | 說明 |
|---|---|---|
| POST | `/twfido/sign` | 需 `Authorization: Bearer <權杖>`；JSON `{mode, idNumber, hint, data, timeLimit}`，回 `{sessionId, token, spTicket, environment, expiresAt}` |
| GET | `/twfido/sign/{sessionId}?token=` | `{status: pending\|done\|failed, signature, certificate, hashedIdNumber, code, reason}` |

- `mode`：`push`（推播到持有人手機上的行動自然人憑證 App）或 `app2app`（錢包以 `spTicket` 開啟官方 App；票證不含金鑰）。`data` 為 base64 的待簽位元組（CMS 時為含文件雜湊的簽署屬性，上限 8 KB，不含文件），`hint` 為顯示在官方 App 的提示（1 到 60 字），`timeLimit` 為 30 到 600 秒（預設 180）。
- 沿用錢包 `core` 的 `TwFidoClient`：向內政部要求簽署、輪詢結果，並以 SP 金鑰驗證回應的 `idp_checksum`；檢查失敗時回 `failed`。不論錢包多常輪詢，服務端最多每 3 秒問一次內政部。
- 簽章值與憑證由錢包自行驗證（簽章對應憑證、憑證連到 MOICA），服務端不參與信任判定。
- 限制：建立需存取權杖（只存 SHA-256），避免被用來對任意身分證號推播；同時請求 16、每個位址 4、每個身分證號 2（以雜湊計，不保存號碼）。
- 隱私：服務端看得到身分證號、提示與待簽位元組；三者與結果只在記憶體，到期後 2 分鐘刪除，不寫入 log，回應也不回傳身分證號。
- 環境：`twfido_environment` 為 `production`（預設）或 `uat`；兩者都需內政部核發的 SP 帳號。`/info` 的 `twfido.environment` 列出目前的環境。

## 4. 金鑰與信任

- 簽章金鑰：P-256，用來簽查驗請求（`x5c`）與網頁證明回執。憑證鏈為自簽根加葉憑證（葉憑證的 dNSName 為 `public_url` 的主機），效期 20 年；整份存成一個 PEM（私鑰後接葉憑證與根）。
- 來源，依優先順序：
  - `signing_key_pem`：整份 PEM 放在設定或環境變數（Cloud Run 的 Secret Manager、Cloudflare 的 secret）。
  - `signing_key_file`：指定的檔案（預設 `data_dir/signing.pem`）。
  - 自動產生：檔案不存在且 `signing_generate=true`（預設）時，第一次啟動產生並寫入（權限 0600），之後沿用；磁碟不保存的平台要設 `false`，沒有金鑰時拒絕啟動。
- `diw-server keygen <檔案> <public_url>` 預先產生簽章金鑰，加 `verifier` 產生網頁查驗端金鑰（`verifier_key_pem` 或 `verifier_key_dir`）。
- 存取權杖：設定只存 SHA-256（`diw-server hash-token` 由標準輸入讀權杖，至少 16 字元），比對採固定時間比較；可列多個以便輪換。
- 金鑰、權杖與憑證從不寫入 log。

## 5. 部署

自架主機時 TLS 由 Caddy 自動處理：自動申請與更新 Let's Encrypt 憑證、自動轉址 HTTPS、代理 WebSocket。不需要互動式 console。部署方式有四種：容器、不用容器、Cloud Run、Cloudflare Containers。

### 5.1 容器（建議）

- CI 建出映像 `ghcr.io/<owner>/diw-wallet-server:<version>`：Ubuntu 24.04 基底，內含 jlink 精簡的 Java 21 執行環境與原生程式庫，以非 root 使用者執行。目前只有 amd64：錢包的原生程式庫只提供 linux-x86_64。
- 映像建置時跑一次服務產生 AppCDS 類別快取（`JDK_JAVA_OPTIONS=-XX:SharedArchiveFile=...`）。實測從啟動到 `/healthz` 可回應由 1.5 秒降為 0.8 秒，啟動後 RSS 由 153 MB 降為 103 MB（4 核心開發機，只開中繼）。
- `compose.yaml` 包含 `server` 與 `caddy` 兩個服務：
  - `restart: unless-stopped`；
  - `healthcheck` 打 `/healthz`；
  - 資料目錄與 Caddy 憑證目錄各用一個 volume；
  - 只對外開 80 與 443。
- 使用者只需填 `.env`：網域、ACME 聯絡信箱、存取權杖。之後執行 `docker compose up -d`。

### 5.2 不用容器

- Release 附 `diw-server-<version>.tar`（`./gradlew distTar`）：jlink 精簡的 Java 21（`runtime/`，含 CDS 基礎快取）、程式 jar、原生程式庫與 `deploy/` 下所有範例。在 x86_64 Linux 建置時，發行檔（`installDist`、`distTar`）一律包含 `runtime/`，映像也直接取用；啟動腳本在未設 `JAVA_HOME` 時使用 `runtime/`。其他平台建置時不附執行環境。
- systemd 範例以 `-XX:+AutoCreateSharedArchive` 在資料目錄建立類別快取，升級後自動重建。
- 以 systemd 管理：`Restart=on-failure`、`MemoryMax`、`DynamicUser` 或專用帳號、`StateDirectory`，log 寫進 journald。
- Caddy 用發行版套件安裝，同樣自動處理憑證。

### 5.3 Cloud Run

- 用同一個映像，以 `deploy/cloudrun/service.yaml` 部署。Cloud Run 終止 TLS，不需要 Caddy。
- 平台以 `PORT` 告知監聽連接埠；設定檔與 `DIW_LISTEN_PORT` 都未指定時才採用。
- 執行個體上限 1：中繼與之後的查驗工作階段都在記憶體中，兩端必須連到同一個執行個體。下限 0，閒置不計費。
- 請求逾時設為 3600 秒（平台上限），涵蓋中繼房間的 15 分鐘。
- `trust_forwarded=true`：容器只能經由 Google 前端連入，`X-Forwarded-For` 可信。
- 資料目錄在 `/tmp`（記憶體檔案系統，計入記憶體），重啟即消失。簽章金鑰不能自動產生在這裡，否則每次冷啟動都換鑰：`service.yaml` 以 Secret Manager 的 `diw-signing-key` 提供 `DIW_SIGNING_KEY_PEM`，並設 `signing_generate=false`。
- 記憶體上限 1 GiB、`-Xmx384m`，涵蓋一次 zkTLS 查驗。
- 映像經 Artifact Registry 遠端存放區代理 ghcr.io。

### 5.4 Cloudflare Containers

- 用同一個映像。`deploy/cloudflare/` 的 Worker 把每個請求轉到名為 `main` 的單一容器執行個體（Durable Object 加 `@cloudflare/containers`，`max_instances` 為 1），WebSocket 直接轉送。
- Worker 以 `CF-Connecting-IP` 覆寫 `X-Forwarded-For`（不附加，避免用戶端自選位址），容器設 `trust_forwarded=true`。
- 磁碟不保存：金鑰由 secret 傳入（`SIGNING_KEY_PEM`、`VERIFIER_KEY_PEM`、`VERIFIER_ACCESS_TOKEN`、`TWFIDO_AES_KEY`），`signing_generate=false`；版型包每次冷啟動重新下載。
- 執行個體類型預設 `standard-1`（4 GiB、1/2 vCPU），只開中繼可用 `basic`。閒置 `SLEEP_AFTER`（預設 20 分鐘）後休眠。
- 費用：Workers Paid 每月 5 美元，含 25 GiB-小時記憶體與 375 vCPU-分鐘，休眠時不計。
- 純 Workers（不用容器）不能執行 JVM 與原生程式庫；以 Durable Object 重寫中繼的方案已放棄（2026-10-25 使用者決定）。

### 5.5 設定

`key=value` 設定檔（預設 `/etc/diw-server/server.conf`，或以 `DIW_CONFIG` 指定），每個鍵都可用環境變數 `DIW_<鍵的大寫>` 覆寫。設定檔中的未知鍵會讓程式拒絕啟動；其他 `DIW_` 開頭的環境變數不視為設定。

| 鍵 | 預設 | 說明 |
|---|---|---|
| `public_url` | 必填 | 對外網址，例如 `https://diw.example.org`。查驗連結、中繼與網頁查驗的網址都由它產生，必須是錢包實際連得到的名稱；`/info` 會回報它，名稱無法解析時啟動記錄會警告 |
| `listen_host`、`listen_port` | `127.0.0.1`、`8080` | 由 Caddy 代理；容器內為 `0.0.0.0`；未指定連接埠時採用 `PORT` |
| `trust_forwarded` | 監聽 loopback 時為 true | 是否採用代理轉送的用戶端位址（每個位址的連線上限依此計算） |
| `features` | `relay` | 啟用的功能，以逗號分隔：`relay`、`zktls`、`bonds-age`、`verifier`、`twfido` |
| `data_dir` | `/var/lib/diw-server` | 資料目錄（原生程式庫解壓、之後的金鑰） |
| `relay_max_rooms` 等 | 見 3.1 | 中繼限制 |
| `bonds_age_max_sessions`、`bonds_age_per_address` | `64`、`8` | 年齡查驗的同時請求數與每個位址的上限 |
| `verifier_name` | `DIW 網頁查驗` | 持卡人同意畫面上的查驗方名稱 |
| `verifier_access_token` | 無 | 設定後建立查驗需輸入這個存取碼（至少 8 字元）；未設定時任何人都可使用 |
| `verifier_key_dir` | 資料目錄下的 `verifier/` | 查驗端金鑰的位置；Cloud Run 上請掛載 Secret，否則每次冷啟動 DID 都會改變 |
| `verifier_max_sessions`、`verifier_per_address` | `64`、`8` | 同時查驗數與每個位址的上限 |
| `bonds_age_verify` | `false` | 在主機驗證年齡證明（需原生程式庫與約 450 MB 的驗證金鑰，記憶體建議 2 GB 以上，未實測） |

| `zktls_token_sha256` | 無（啟用 `zktls` 時必填） | 存取權杖的 SHA-256，以逗號分隔可列多個 |
| `zktls_max_sessions`、`zktls_per_address`、`zktls_concurrent` | `16`、`4`、`1` | 同時工作階段、每個位址的上限、同時查驗數 |
| `zktls_templates_file` | 無 | 本機的簽章範本包，代替從錢包 Release 下載（仍以釘選公鑰驗證） |
| `signing_key_file` | 資料目錄下的 `signing.pem` | 簽章金鑰檔 |
| `signing_key_pem` | 無 | 簽章金鑰的整份 PEM（優先於檔案）；設定檔中可用 `\n` 表示換行 |
| `signing_generate` | `true` | 金鑰檔不存在時自動產生；磁碟不保存的平台設 `false` |
| `verifier_key_pem` | 無 | 網頁查驗端金鑰的整份 PEM（優先於 `verifier_key_dir`） |
| `twfido_environment` | `production` | 行動自然人憑證服務的環境：`production` 或 `uat` |
| `twfido_service_id`、`twfido_aes_key` | 無（啟用 `twfido` 時必填） | 內政部核發的 SP 服務代碼與 AES 金鑰（base64，32 位元組）；金鑰請以 secret 提供 |
| `twfido_token_sha256` | `zktls_token_sha256` 的值 | 簽署用存取權杖的 SHA-256，以逗號分隔可列多個；兩者都未設定時拒絕啟動 |
| `twfido_max_sessions`、`twfido_per_address`、`twfido_per_id` | `16`、`4`、`2` | 同時請求、每個位址與每個身分證號的上限 |

## 6. 1 GB 主機的資源配置

- JVM：`-Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m`。S1 只開中繼時，閒置 RSS 實測約 144 MB。
- zkTLS 查驗同時只跑 1 個（可設定），其餘排隊或回 503。
- 建議加 1 GB swap。
- 已測：主機與持有人在同一個測試 JVM 完成一次真實的 TLSNotary 查驗，程序峰值 RSS（VmHWM）約 345 MB，耗時約 3 秒（4 核心）。主機單獨的峰值應更低，為推論。
- 未測：在 `MemoryMax=900M` 的 cgroup 中以容器為主機跑完整的持有人查驗流程。部署驗收時執行並記錄峰值；超出時降低工作階段上限，或只開中繼。

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
| S3 | CI 映像、release tarball、部署文件（含 Cloud Run） | 在乾淨 VPS 依文件部署，Caddy 取得憑證，手機可連線；Cloud Run 部署後手機可連線 |
| S4 | zkPDF 證明器（等 M3c） | 在大記憶體主機產生證明，手機驗證後使用 |
| TWFIDO SP | 行動自然人憑證的 SP 金鑰只放在服務端（錢包可選用服務端、編譯時設定的 SP 或手動設定） | 手機透過服務端推播簽署並自行驗證簽章與 MOICA 憑證鏈（需內政部 SP 帳號，由持有帳號者執行） |

S1 至 S3 與 TWFIDO SP 的程式與自動測試已完成（zkTLS 查驗主機以原生 TLSNotary 端對端測試通過；TWFIDO SP 以本機模擬的內政部服務測試，含 `idp_checksum` 偽造與頻率限制）。S2 的 cgroup 實測、S3 的乾淨 VPS、Cloud Run 與 Cloudflare 實機部署由使用者執行。

## 9. 之後的方向

- Native 化：Kotlin/Native 不可行（錢包 `core` 依賴 Multipaz JVM、BouncyCastle、JNA）。GraalVM native-image 需把 Netty 換成 CIO（Ktor 的 native image 只支援 CIO），並補 JNA、BouncyCastle 的設定；先以 AppCDS 縮短啟動，GraalVM 映像列為實驗性選項，JVM 映像保留。
