# DIW 正式服務端設計（diw-wallet-server）

狀態：S1 至 S3 已實作（中繼、zkTLS 查驗主機與回執、存取權杖、金鑰管理、附執行環境的發行檔、容器、systemd、Cloud Run、Cloudflare Containers），Bonds 年齡查驗主機、網頁查驗端與行動自然人憑證簽署（TWFIDO SP）已實作（可選功能）；S3 的實機部署驗收待使用者執行。2026-11-05 加入：所有部署共用一份設定檔與設定摘要（5.5）、OpenID Federation（4.1）、GraalVM 原生執行檔（實驗，5.6）。S4（zkPDF 證明器）為設計。

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
| 本專案 OPRF | v1（可選） | `/oprf/v1/key`、`/oprf/v1/evaluate`，zkPassport 加鹽唯一識別碼的單一金鑰 OPRF，金鑰不放進 App | 可（每次評估為一次純量乘法與證明） |
| 自己的 zkPassport 證明器 | v1（可選） | `/zkpassport/v1/jobs`，替使用者自己的錢包產生 zkPassport 回應證明；主機會看到 SOD 與 DG1（不含照片） | 不保證，記憶體高峰視 DSC 簽章演算法而定 |
| 臨時證明器 | v1（可選） | `diw-server prover`，內網限定、一次性 QR 配對、釘選憑證的 TLS 加上封裝，不需設定檔與權杖 | 同上 |
| 信任聯盟 | v1（可選） | `/.well-known/openid-federation`，OpenID Federation 1.0 的 Entity Configuration；可當上級（`/federation/fetch`、`/federation/list`） | 可 |

## 2. 技術選擇

- Kotlin 2.2.21、Ktor 3.3.3（CIO 引擎）、JVM 21，與錢包一致。2026-11-05 由 Netty 改為 CIO：Ktor 在 GraalVM native image 只支援 CIO，兩種執行方式因此共用同一份程式。
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
- `GET /info`：版本、啟用的功能、原生程式庫的後端，以及 `signing`（根憑證主體、`rootSha256` 與根憑證 PEM，供使用者在錢包內比對後加入信任）與 `templatesVersion`。另有 `settingsSha256`（設定摘要，5.5）、`platform`，啟用 `federation` 時有 `federation`（`entityId`、`authorityHints`、`superior`）。

### 3.4 錢包端的修改（在錢包 repo，已實作）

- 設定改為一個「正式服務端」網址，同時用於中繼、zkTLS 查驗主機與年齡查驗主機（`zkVerifierUrl`），不必分別填寫。
- 選填的存取權杖，存在加密儲存，只送到該主機。
- 加入服務端時一併加入信任（2026-10-25 使用者要求）：錢包讀取 `/info` 的簽章根憑證與 SHA-256 指紋，顯示給使用者確認後，以正式環境、zkTLS 查驗方與網頁證明回執發行者的角色加入自訂信任（與使用者匯入的根相同，T2）。之後主機簽的查驗請求與回執經 `TrustRegistry.decide` 判定為 T2。未加入信任時以 T3 強提示，仍可使用（沿用現有信任模型）。
- 同一次確認也信任網頁查驗端的 DID（`/info` 的 `verifierDid`）：加入錢包的使用者信任 DID（只有查驗方角色，T2），持卡人出示給這台服務端時不再顯示 T3。DID 只由使用者輸入的網址與 TLS 確認，沒有另外的指紋。
- 服務端換鑰或換查驗端 DID 時，錢包不自動接受，需使用者重新確認。
- 實作：錢包的 `ServerModel`（讀取 `/info`、自行計算指紋並與服務端回報的比對、以 `server:<host>` 來源加入查驗方與發行者兩個角色的使用者錨點，換鑰時移除舊錨點）、設定的「零知識證明」頁「正式服務端」區塊（網址、權杖、加入信任與換鑰警示），`ZkTlsFlow` 只對設定的服務端帶 `Authorization: Bearer`。
- 設定分享（2026-11-05）：`diw-server://v1?c=<base64url JSON>`，鍵為 `url`、`name`、`root`（簽章根憑證 SHA-256）、`did`、`fed`（Entity Identifier）、`features`、`token`（只在分享者勾選時）。錢包以 QR Code 或連結分享（`core` 的 `ServerShare`），匯入後讀 `/info`，根憑證或 DID 與分享的不同就拒絕，相同才詢問是否信任。
- 信任聯盟（2026-11-05）：服務端 `/info` 有 `federation.entityId` 時，錢包以使用者加入的 OpenID Federation Trust Anchor 解析信任鏈（實體類型 `diw_wallet_server`），中繼資料的 `signing_root_sha256` 與 `verifier_did` 都和服務端回報的相同時，在確認畫面註明由聯盟確認；不同時拒絕。
- 兩方的服務端（2026-11-05）：只需一方有服務端。網站查驗的查驗方在每個中繼開一個房間（自己的與掃描到的對方服務端，最多 3 個，`ZkTlsEngine.open(lan, relayUrls)`），請求列出所有房間；出示方依設定 `serverPolicy`（`prefer_own` 預設、`prefer_peer`、`own_only`）排序，`own_only` 不走其他服務端的中繼。出示方可在同意畫面顯示自己的服務端 QR Code（不含權杖），查驗方掃描後以同樣的欄位重新產生請求。年齡查驗的查驗方依同一設定在兩台中選一台當主機。對方的服務端只用在當次查驗，不加入信任、不儲存。

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

### 3.8 本專案 OPRF（`oprf`）

2026-11-05 使用者決定（錢包 `docs/design/zkpassport.md` 5.5）：zkPassport 的加鹽唯一識別碼改用本專案的 OPRF，金鑰放在服務端 secret，不放進 APK（任何人都能從 APK 取出金鑰，等於沒有加鹽）。

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/oprf/v1/key` | `{key_id, pk: {x, y, hash}}`；`hash` 是 zkPassport 電路輸出的 `oprf_pk_hash`，查驗端以它判斷接受的金鑰 |
| POST | `/oprf/v1/evaluate` | 需 `Authorization: Bearer <權杖>`；JSON `{key_id, blinded_query: {x, y}}`，回 `{key_id, pk, response_blinded, dlog_e, dlog_s}` |

- 計算沿用錢包原生程式庫的 `oprf_evaluate`（TACEO `taceo-oprf-core`）：回應為金鑰乘以盲化點，附 Chaum-Pedersen 證明；錢包以 `/oprf/v1/key` 的公鑰核對證明，再解盲成電路的 `nullifier_secret`。zkPassport 的揭露電路已實測接受此證明。
- 服務端只看到盲化點（隨機純量乘上雜湊到曲線的私密 nullifier），看不到 nullifier、護照資料或結果；請求不寫入 log、不保存。
- 授權（第一版）：存取權杖（只存 SHA-256，`oprf_token_sha256` 未設定時沿用 `zktls_token_sha256`）加每個位址每小時 `oprf_per_address`（預設 20）、全部 `oprf_total`（預設 2000）次。第二版加入本專案 FaceMatch 證明（需要 Play Integrity 金鑰），之前的識別碼在錢包與查驗端標示「未經人臉比對」。
- 金鑰：`oprf_key`（BabyJubJub 純量，`diw-server oprf-keygen` 產生，secret），`oprf_key_id`（預設 `1`）隨公鑰公開。換鑰時產生新金鑰並改 `oprf_key_id`；舊識別碼全部失效，錢包與查驗端以新公鑰雜湊為準。
- 原生程式庫要含 `oprf`；`DIW_ZK_FEATURES` 未含時 `/oprf/*` 與 `/healthz` 回 503。

### 3.9 自己的 zkPassport 證明器（`zkpassport`）

2026-11-12 使用者決定：zkPassport 的回應證明可以交給使用者自己的服務端產生，手機只負責讀晶片、連 bridge 與送出。護照資料會離開手機，所以只用於使用者自己設定（有存取權杖）的服務端，不是公開服務。

| 方法 | 路徑 | 說明 |
|---|---|---|
| POST | `/zkpassport/v1/jobs` | 需 `Authorization: Bearer <權杖>`；JSON `{sod, dg1, query, domain, scope, dev_mode, circuit_version}`（`sod`、`dg1` 為 base64；證明日期取服務端時鐘，請求不能指定），回 202 `{id}` |
| GET | `/zkpassport/v1/jobs/{id}` | 需權杖；`{state: queued\|registry\|proving, index, total, name}`，完成時 `{state: done, result: {proofs, messages, query_result}}`，失敗時 `{state: failed, code}` |
| DELETE | `/zkpassport/v1/jobs/{id}` | 需權杖；取消並刪除 |

- 證明沿用錢包 core 的 `ZkPassportProver`（官方 Rust 輸入產生與 driver，電路逐一核對 vkey 雜湊後才執行），與手機上完全相同；不需要 DG2，DG2 雜湊取自 SOD。
- 晶片資料與結果只存在記憶體中的工作物件，不寫入磁碟或 log；工作識別碼為 128 位元亂數。完成或失敗的結果被讀取一次後刪除，未讀取的工作 10 分鐘後刪除。
- 一次只跑一個工作，等待中的工作最多 `zkpassport_queue`（預設 2），超過回 429。權杖只存 SHA-256，必須設定專用的 `zkpassport_token_sha256`，且不可與 `zktls_token_sha256` 相同（啟動失敗）：zkTLS 權杖可能隨分享連結給了其他錢包，護照資料只能送進擁有者自己的證明器。
- 以太坊節點由服務端設定（`zkpassport_rpc`、`zkpassport_sepolia_rpc`，未設定用錢包內建的公開節點），不接受請求指定網址。不使用 OPRF（回應時不加鹽，與手機相同）。
- 需要原生程式庫的 UltraHonk（`DIW_ZK_FEATURES` 含 `zkpdf`）與 CRS；啟動時下載 CRS 到資料目錄。缺少時 `/zkpassport/*` 與 `/healthz` 回 503。
- 資源：證明期間的記憶體高峰取決於 DSC 的簽章演算法（RSA-4096 的電路最大），1 GB 主機可能不夠；`/info` 的 `zkpassport` 只表示功能可用，不保證記憶體足夠。
- 部署建議：與給其他錢包共用的正式服務端分開，另跑一個只開 `zkpassport` 的實例（同一映像，各自一份 `server.conf`），放在記憶體較大或只在需要時啟動的主機（家用電腦、Cloud Run）。理由：正式服務端的權杖與網址會分享給他人，護照資料不應送到多人使用的主機；證明的記憶體高峰可能讓同一主機的中繼、zkTLS 與 OPRF 被系統終止；OPRF 金鑰等秘密也不必與證明器放在同一台。錢包端把證明器設成獨立的服務端項目，不沿用正式服務端的設定。

### 3.10 臨時證明器與一次性配對（`diw-server prover`）

2026-11-12 使用者提出：多數人不會設定服務端，只是在家用電腦臨時開一個證明器、在內網直接使用，也不一定會設定權杖。所以有 `diw-server prover`，以 QR Code 承載一次性的配對碼，不需要設定檔與權杖。

- 啟動：`diw-server prover [--port 8090] [--address <內網 IP>]`。不讀 `server.conf`，只開 `zkpassport`；位址自動選內網 IPv4（優先 192.168.x、10.x，略過 docker 等虛擬網卡）。`DIW_DATA_DIR`（預設 `~/.diw-server`）保存 CRS，`DIW_ZKPASSPORT_RPC`、`DIW_ZKPASSPORT_SEPOLIA_RPC`、`DIW_ZKPASSPORT_QUEUE` 照常適用。終端機印出 QR Code 與連結（不寫入 log），按 Enter 產生新的，Ctrl+C 結束時所有工作階段與工作一併消失。配對只能透過這個指令；一般部署仍用權杖（3.9）。
- TLS：Android 正式版的網路安全設定不允許對內網位址使用明文 HTTP，所以證明器以 TLS 對外。每次啟動產生新的 P-256 金鑰與自簽憑證（只在記憶體），配對碼帶憑證的 SHA-256，錢包只接受這張憑證（釘選，不看主機名稱與 CA）。TLS 前端聽所有介面，只接受私有位址的連線再轉給只聽 loopback 的服務，同時最多 16 條連線。
- 私有位址：loopback、RFC 1918、連結本地、IPv6 ULA（fc00::/7）與 100.64.0.0/10（Tailscale 等），以連線本身的位址判斷。路由器即使把埠轉發出去，網際網路上的連線也一律關閉。
- 配對碼：`diw-prover://v1?u=<https 網址>&p=<32 bytes base64url>&c=<憑證 SHA-256 base64url>`，10 分鐘內有效，用過一次即失效，新的配對碼會取代未用的舊碼。配對秘密不會在網路上傳送：
  1. 錢包 `POST /zkpassport/v1/pair` `{nonce, mac}`，`nonce` 為 16 bytes，`mac = HMAC-SHA256(p, "diw-prover pair v1" || nonce)`。
  2. 服務端核對後回 `{session, nonce: 服務端 nonce}`，配對碼作廢；雙方各自算出 `K = HMAC-SHA256(p, "diw-prover session v1" || 錢包 nonce || 服務端 nonce)`。配對碼錯誤回 401，已用過或過期回 410。
- 之後每個請求：`Authorization: DIW-Session <session>.<seal>`，`seal` 是以 K 做 AES-256-GCM 封裝的 `{n, m, p}`（遞增計數、方法、路徑，AAD `"diw-prover auth v1 <session>"`）；計數必須大於上一個，方法與路徑必須相符，所以截到的請求無法重送或改用到別的路徑。請求與回應本文為 `{sealed}`，AAD 分別為 `"<session> <n> request"` 與 `"<session> <n> response"`。TLS 之外再封裝一層，配對與工作都不依賴 TLS 才安全。
- 工作階段只在記憶體，閒置 30 分鐘或程序結束即消失；`DELETE /zkpassport/v1/pair` 由錢包主動結束。工作與 3.9 共用同一個佇列與結果規則（讀一次即刪）。
- 錢包端：掃描配對碼後把證明器當成臨時的證明位置，只保留工作階段，不存配對碼；同意畫面標示資料會送到哪個位址。工作階段失效時請使用者重新掃描。
- 發佈：Windows、macOS、Linux 各有 jar 版（附 Java 執行環境）與原生執行檔，原生證明程式庫需含 `zkpdf`（UltraHonk）。

## 4. 金鑰與信任

- 簽章金鑰：P-256，用來簽查驗請求（`x5c`）與網頁證明回執。憑證鏈為自簽根加葉憑證（葉憑證的 dNSName 為 `public_url` 的主機），效期 20 年；整份存成一個 PEM（私鑰後接葉憑證與根）。
- 來源，依優先順序：
  - `signing_key_pem`：整份 PEM 放在設定或環境變數（Cloud Run 的 Secret Manager、Cloudflare 的 secret）。
  - `signing_key_file`：指定的檔案（預設 `data_dir/signing.pem`）。
  - 自動產生：檔案不存在且 `signing_generate=true`（預設）時，第一次啟動產生並寫入（權限 0600），之後沿用；磁碟不保存的平台要設 `false`，沒有金鑰時拒絕啟動。
- `diw-server keygen <檔案> <public_url>` 預先產生簽章金鑰，加 `verifier` 產生網頁查驗端金鑰（`verifier_key_pem` 或 `verifier_key_dir`）。
- 存取權杖：設定只存 SHA-256（`diw-server hash-token` 由標準輸入讀權杖，至少 16 字元），比對採固定時間比較；可列多個以便輪換。
- 金鑰、權杖與憑證從不寫入 log。

### 4.1 信任聯盟（`federation`）

- 依 OpenID Federation 1.0：`/.well-known/openid-federation` 回應以聯盟金鑰（P-256，ES256）簽署的 Entity Configuration（`typ` 為 `entity-statement+jwt`），效期 `federation_lifetime_hours`（預設 24 小時）。
- 中繼資料：`federation_entity`（`organization_name`、`homepage_uri`，當上級時加 `federation_fetch_endpoint` 與 `federation_list_endpoint`）與自訂實體類型 `diw_wallet_server`（`signing_root_sha256`、`verifier_did`、`features`、`settings_sha256`）。`authority_hints` 與 `trust_marks` 由設定提供。
- 聯盟金鑰與簽章金鑰分開：`federation_key_pem`、`federation_key_file`（預設 `data_dir/federation.pem`），或依 `signing_generate` 自動產生；`diw-server federation-jwks` 印出公鑰交給上級。
- 上級：`federation_subordinates_file`（JSON，每個下級的 `entity_id`、`jwks`、可選的 `metadata_policy`、`metadata`、`constraints`、`entity_types`），檔案修改時間改變就重新讀取。`/federation/fetch?sub=` 回應下級的 Subordinate Statement，`/federation/list` 列出下級（可依 `entity_type` 過濾）。`diw-server federation-enroll <entity_id>` 讀取並驗證下級的 Entity Configuration，印出要加入的項目。
- 錢包端的解析沿用 `core` 的 `FederationResolver`（與 OpenID4VP 的 `openid_federation:` 查驗方相同）；測試以兩台服務端（下級與 Trust Anchor）互相解析。

## 5. 部署

自架主機時 TLS 由 Caddy 自動處理：自動申請與更新 Let's Encrypt 憑證、自動轉址 HTTPS、代理 WebSocket。不需要互動式 console。部署方式有四種：容器、不用容器、Cloud Run、Cloudflare Containers。

### 5.1 容器（建議）

- CI 建出映像 `ghcr.io/<owner>/diw-wallet-server:<version>`：Ubuntu 24.04 基底，內含 jlink 精簡的 Java 21 執行環境與原生程式庫，以非 root 使用者執行，`DIW_PLATFORM=container`。目前只有 amd64：錢包的原生程式庫只提供 linux-x86_64。
- 映像建置時跑一次服務產生 AppCDS 類別快取（`JDK_JAVA_OPTIONS=-XX:SharedArchiveFile=...`）。實測從啟動到 `/healthz` 可回應由 1.5 秒降為 0.8 秒，啟動後 RSS 由 153 MB 降為 103 MB（4 核心開發機，只開中繼）。
- `compose.yaml` 包含 `server` 與 `caddy` 兩個服務，兩者都以 `env_file` 讀 `./server.conf`（Caddy 只用 `DIW_PUBLIC_URL` 與 `DIW_ACME_EMAIL`）；`restart: unless-stopped`、`healthcheck` 打 `/healthz`、資料目錄與 Caddy 憑證目錄各用一個 volume、只對外開 80 與 443。`.env` 只剩映像版本、記憶體上限與 `JAVA_OPTS`。

### 5.2 不用容器

- Release 附 `diw-server-<version>.tar`（`./gradlew distTar`）：jlink 精簡的 Java 21（`runtime/`，含 CDS 基礎快取）、程式 jar、原生程式庫與 `deploy/` 下所有範例。在 x86_64 Linux 建置時，發行檔（`installDist`、`distTar`）一律包含 `runtime/`，映像也直接取用；啟動腳本在未設 `JAVA_HOME` 時使用 `runtime/`。其他平台建置時不附執行環境。
- systemd 範例以 `-XX:+AutoCreateSharedArchive` 在資料目錄建立類別快取，升級後自動重建。設定讀 `/etc/diw-server/server.conf`，Caddy 以 `EnvironmentFile` 讀同一份。
- 以 systemd 管理：`Restart=on-failure`、`MemoryMax`、`DynamicUser`、`StateDirectory`、`ConfigurationDirectory`，log 寫進 journald。

### 5.3 Cloud Run

- 用同一個映像，以 `deploy/cloudrun/service.yaml` 部署，`DIW_PLATFORM=cloudrun`。Cloud Run 終止 TLS，不需要 Caddy。
- 設定：整份 `server.conf` 存成 Secret Manager 的 `diw-server-conf`，掛載為 `/etc/diw-server/server.conf`。金鑰以 `DIW_SIGNING_KEY_PEM=...`（一行，`\n` 表示換行）寫在檔內。
- 平台以 `PORT` 告知監聽連接埠；設定檔與 `DIW_LISTEN_PORT` 都未指定時才採用。
- 執行個體上限 1：中繼與查驗工作階段都在記憶體中，兩端必須連到同一個執行個體。下限 0，閒置不計費。請求逾時 3600 秒（平台上限），涵蓋中繼房間的 15 分鐘。
- 平台預設：監聽 `0.0.0.0`、`trust_forwarded=true`（容器只能經由 Google 前端連入）、資料目錄 `/tmp/diw-server`（記憶體檔案系統，重啟即消失）、`signing_generate=false`（否則每次冷啟動都換鑰）。
- 記憶體上限 1 GiB、`-Xmx384m`，涵蓋一次 zkTLS 查驗。映像經 Artifact Registry 遠端存放區代理 ghcr.io。

### 5.4 Cloudflare Containers

- 用同一個映像，`DIW_PLATFORM=cloudflare`。`deploy/cloudflare/` 的 Worker 把每個請求轉到名為 `main` 的單一容器執行個體（Durable Object 加 `@cloudflare/containers`，`max_instances` 為 1），WebSocket 直接轉送。
- 設定：整份 `server.conf` 存成 secret `SERVER_CONF`，Worker 以 `DIW_CONFIG_TEXT` 交給容器；名為 `DIW_<鍵>` 的 var 或 secret 原樣傳入，覆寫檔內設定。
- Worker 以 `CF-Connecting-IP` 覆寫 `X-Forwarded-For`（不附加，避免用戶端自選位址），平台預設 `trust_forwarded=true`。
- 磁碟不保存：平台預設 `signing_generate=false`、資料目錄 `/tmp/diw-server`；金鑰寫在設定中；版型包每次冷啟動重新下載。
- 執行個體類型預設 `standard-1`（4 GiB、1/2 vCPU），只開中繼可用 `basic`。閒置 `SLEEP_AFTER`（預設 20 分鐘）後休眠。
- 費用：Workers Paid 每月 5 美元，含 25 GiB-小時記憶體與 375 vCPU-分鐘，休眠時不計。
- 純 Workers（不用容器）不能執行 JVM 與原生程式庫；以 Durable Object 重寫中繼的方案已放棄（2026-10-25 使用者決定）。

### 5.5 設定

2026-11-05 起，手動安裝與所有映像讀同一份設定（使用者要求「手動配置與所有鏡像所配置的內容應完全一致」）：

- 所有設定列在 `ConfigKeys.kt`（名稱、預設值、說明、是否秘密、是否部署相關）。解析、`diw-server config-template`（產生 `deploy/server.conf.example`）與各部署檔都以它為準；測試檢查範本與程式一致、範本可解析、各部署檔只用已知的 `DIW_` 鍵、各映像與服務檔只設定 `DIW_PLATFORM` 而不設服務設定。
- 格式：每行 `DIW_<鍵>=值`（也接受小寫 `key=value`、`export `、引號與 `#` 註解），同時是 systemd EnvironmentFile、compose `env_file`、Cloud Run 掛載的 Secret 與 Cloudflare 的 secret。設定檔中的未知鍵會讓程式拒絕啟動。
- 來源優先順序：平台預設（`DIW_PLATFORM`：`host`、`container`、`compose`、`cloudrun`、`cloudflare`，只影響 `listen_host`、`data_dir`、`trust_forwarded`、`signing_generate`）、設定內容（`DIW_CONFIG_TEXT`，否則 `DIW_CONFIG` 指定的檔案或 `/etc/diw-server/server.conf`）、環境變數 `DIW_<鍵>`，最後 `PORT`（只在未設定 `listen_port` 時）。
- 設定摘要：`settingsSha256` 是所有非部署、非秘密設定的實際值（含預設值）的 SHA-256，出現在 `/info`、啟動紀錄與 `diw-server config`。同一份設定部署到不同平台，摘要相同。
- 各鍵的說明與預設值見 `deploy/server.conf.example`（由程式產生，不另外維護表格）。

### 5.6 原生執行檔（實驗）

- GraalVM native-image（`org.graalvm.buildtools.native` 0.10.6，`./gradlew nativeCompile`），需 GraalVM 25：21.0.2 編譯 Kotlin 協程時出現 `Frame states being merged are incompatible`。參數：`--no-fallback`、`-march=compatibility`、`--enable-url-protocols=http,https`、`-Dfile.encoding=UTF-8`、`-H:IncludeLocales=en,zh`。
- 反射、資源（含 `zk/native/linux-x86_64/libdiw_zk.so`）、JNI 與序列化設定由 tracing agent 跑完整測試產生（`tools/native-metadata.sh`），`tools/filter-native-metadata.py` 去掉只屬於測試的項目。
- 原生程式庫（tlsn）照常以 JNA 從資源解壓後載入；`./gradlew test -Pdiw.nativeServer=build/native/nativeCompile/diw-server` 讓 zkTLS 端對端測試改對原生執行檔執行（持有人在測試 JVM，服務端是原生程序），已通過。
- 實測（只開中繼，4 核心開發機）：啟動到 `/healthz` 可回應約 0.17 秒，RSS 約 79 MB；執行檔約 95 MB。
- 發佈：Release 的 `diw-server-<version>-native` 與映像 `<version>-native`（`Dockerfile.native`）。JVM 版仍是預設；原生版的設定、資料目錄與指令都相同，可隨時切換。

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

- Native 化：Kotlin/Native 不可行（錢包 `core` 依賴 Multipaz JVM、BouncyCastle、JNA）。GraalVM native-image 已可用（5.6，實驗）；實機部署穩定後再考慮改為預設映像。
