# diw-wallet-server

[DIW MY WALLET](https://github.com/mctea01/my-Bonds-fork-android) 的正式環境服務端。

> 狀態：版本 0.1.0，功能都有自動測試，還沒有發佈過正式映像，也沒有實機部署的驗收紀錄。做到哪裡、還缺什麼見 [docs/STATUS.md](docs/STATUS.md)。

| 想知道 | 讀這份 |
|---|---|
| 目前狀態、未完成與待驗收 | [docs/STATUS.md](docs/STATUS.md) |
| 介面、金鑰與信任、部署的設計與理由 | [docs/DESIGN.md](docs/DESIGN.md) |
| 開發規則 | [AGENTS.md](AGENTS.md) |
| Fork 之後要設定什麼 | [docs/fork-setup.md](docs/fork-setup.md) |
| 第三方元件與授權 | [NOTICE](NOTICE)、[LICENSE](LICENSE) |

## 功能

- **中繼**（`/zktls/relay/{room}`）：讓不在同一網路的兩個裝置連線。只轉送端對端加密的資料，不儲存任何內容。
- **zkTLS 查驗主機**（`/zktls/*`，`features` 加 `zktls`）：以 TLSNotary 的 proxy 模式查驗持有人對政府查驗頁的網頁證明，並簽發網頁證明回執。只查驗已安裝簽章版型包中的頁面；建立查驗需要存取權杖，主機只存權杖的 SHA-256。簽章金鑰的根憑證指紋列在 `/info`，錢包加入服務端時讓使用者比對後加入信任。
- **Bonds 年齡查驗主機**（`/api/zkp/*`，可選，`features` 加 `bonds-age`）：與 Bonds 查驗網站相同的 API。預設只轉送證明包，由查驗方的手機驗證；設 `bonds_age_verify=true` 時改在主機驗證（需較大記憶體，不適合 1 GB 主機）。
- **網頁查驗端**（`/verify`，可選，`features` 加 `verifier`）：查驗方在網頁輸入卡片類型與欄位，持卡人以政府數位憑證皮夾掃描 QR Code 出示，頁面顯示判定與要求的欄位；也可查驗 Bonds 的自然人憑證自發卡。可設 `verifier_access_token` 限制只有知道存取碼的人能建立查驗。
- **行動自然人憑證簽署**（`/twfido/sign`，可選，`features` 加 `twfido`）：以這台服務端的 SP 帳號向內政部要求行動自然人憑證簽署，SP 金鑰只放在服務端，不必放進 App。需要內政部核發的 SP 帳號；建立簽署需要存取權杖，避免被用來對任意身分證號推播。服務端看得到身分證號與要簽的位元組（不含文件），只放在記憶體、不寫入 log；簽章由錢包自行驗證。
- **信任聯盟**（`/.well-known/openid-federation`，可選，`features` 加 `federation`）：以 OpenID Federation 1.0 發佈這台服務端的 Entity Configuration，在 `diw_wallet_server` 中繼資料中列出簽章根憑證指紋、網頁查驗端 DID、功能與設定摘要。錢包信任該聯盟的 Trust Anchor 時，加入服務端就不必再逐字比對指紋。設定 `federation_subordinates_file` 時，這台服務端也能當上級（Intermediate 或 Trust Anchor），提供 `/federation/fetch` 與 `/federation/list`。
- **本專案 OPRF**（`/oprf/v1/*`，可選，`features` 加 `oprf`）：錢包以 zkPassport 電路產生「加鹽唯一識別碼」時使用的單一金鑰 OPRF（錢包 `docs/design/zkpassport.md` 5.5）。服務端只看到盲化後的點，看不到護照資料或私密 nullifier；金鑰只放在 secret，公鑰列在 `/oprf/v1/key` 與 `/info`。回答需要存取權杖，每個位址每小時有次數上限。需要含 `oprf` 的原生程式庫（錢包原生建置的 `zkpdf` 會一併開啟），否則 `/healthz` 回 503。
- **自己的 zkPassport 證明器**（`/zkpassport/v1/jobs`，可選，`features` 加 `zkpassport`）：手機較慢或記憶體不足時，錢包把護照晶片的 SOD 與 DG1（不含照片 DG2）送到使用者自己的服務端產生 zkPassport 回應證明，再由手機送出。只接受持有專用權杖的請求（`zkpassport_token_sha256`，不沿用也不可等於可能分享出去的 zkTLS 權杖）；晶片資料與結果只放在記憶體，結果讀取一次即刪除，未讀取的 10 分鐘後刪除。一次證明一個，需要含 `zkpdf` 的原生程式庫（UltraHonk），否則 `/healthz` 回 503；記憶體高峰視護照簽章演算法而定，1 GB 主機可能不夠。建議與分享給他人的正式服務端分開，另跑一個只開 `zkpassport` 的實例。
- **臨時證明器**（`diw-server prover`）：不需設定檔與權杖，在家用電腦執行後印出一次性的 QR Code，錢包掃描即可在同一個內網使用上面的證明器。只回應內網位址，以每次啟動新產生、由配對碼釘選的憑證走 TLS（手機正式版不允許內網明文 HTTP），配對碼用過一次即失效，之後的請求另以配對時導出的金鑰封裝並防止重送；關閉程式即結束。
- **健康檢查**（`/healthz`）與**版本資訊**（`/info`，含設定摘要 `settingsSha256`）。

沒有 zkPDF 證明器：錢包的「送到自架主機產生」用的是錢包 repo 的 `tools/zk-prover-server`。

## 部署

需求：

- 一個網域，DNS 已指向主機（Cloud Run、Cloudflare 可用平台提供的網址）。
- 對外開放 80 與 443 連接埠（自架主機）。
- 1 GB 記憶體即可，建議另加 1 GB swap。

TLS 憑證由 Caddy 自動向 Let's Encrypt 申請與更新，不需要手動操作。

### 一份設定檔，所有部署方式共用

所有部署方式都讀同一份 `server.conf`，格式是 `DIW_<鍵>=值`，同時是 systemd 的 EnvironmentFile、compose 的 `env_file`、Cloud Run 的 Secret 與 Cloudflare 的 secret。範本是 [deploy/server.conf.example](deploy/server.conf.example)，由程式的設定清單產生（`diw-server config-template`），測試會檢查範本、各部署檔與程式一致。

```sh
cp deploy/server.conf.example server.conf   # 至少改 DIW_PUBLIC_URL
chmod 600 server.conf                       # 含金鑰或權杖時
```

- 部署方式之間只有「在哪裡監聽、資料放哪裡」的預設不同，由各映像或服務檔設定的 `DIW_PLATFORM` 決定（`host`、`container`、`compose`、`cloudrun`、`cloudflare`），不必改設定檔。例如 Cloud Run 與 Cloudflare 的磁碟不保存，預設 `signing_generate=false`，資料放在 `/tmp/diw-server`。
- 同名的環境變數會覆寫設定檔。設定檔中的未知鍵會讓服務拒絕啟動，打錯字不會被默默忽略。
- `diw-server config` 印出實際生效的設定（金鑰與權杖只顯示 `<set>`）與設定摘要；`/info` 的 `settingsSha256` 是同一個摘要，不含部署相關的鍵與秘密。把同一份設定部署到不同平台後，摘要相同就代表設定完全一致。

從舊版升級：舊的 `/etc/diw-server/server.conf`（小寫 `key=value`）照樣可讀。compose 不再從 `.env` 讀服務設定，請把設定移到 `server.conf`：`DIW_DOMAIN=diw.example.org` 改為 `DIW_PUBLIC_URL=https://diw.example.org`，其他鍵照抄（`DIW_` 前綴、大寫）；`.env` 只剩映像版本與記憶體上限。Cloud Run 與 Cloudflare 原本分開的環境變數與 secret，改為整份 `server.conf` 一個 secret。

### 方式一：容器（建議）

```sh
git clone https://github.com/mctea01/diw-wallet-server && cd diw-wallet-server
cp deploy/server.conf.example server.conf   # 填入 DIW_PUBLIC_URL 與 DIW_ACME_EMAIL
docker compose up -d
```

- 映像由 CI 在推送 `v*` 標籤時發佈到 `ghcr.io/mctea01/diw-wallet-server`。目前還沒有發佈過標籤，見 [docs/STATUS.md](docs/STATUS.md)；在那之前要自行建置（見「開發」）。
- 兩個容器都讀 `server.conf`：服務讀全部設定，Caddy 只用 `DIW_PUBLIC_URL` 與 `DIW_ACME_EMAIL`。服務的健康檢查通過後，Caddy 才會啟動。
- 查看狀態：`docker compose ps`。查看紀錄：`docker compose logs`。更新：`docker compose pull && docker compose up -d`。
- 啟用 zkTLS 查驗主機：在 `server.conf` 設 `DIW_FEATURES=relay,zktls,bonds-age`，執行 `docker compose run --rm server hash-token` 輸入自訂的權杖（至少 16 字元），把印出的值填進 `DIW_ZKTLS_TOKEN_SHA256`，再 `docker compose up -d`。簽章金鑰會自動產生在 `server-data` volume，請備份其中的 `signing.pem`（`docker compose cp server:/var/lib/diw-server/signing.pem .`）。
- 原生映像（實驗）：在 `.env` 設 `DIW_VERSION=<版本>-native`，其他不變，見下方「原生執行檔」。

### 方式二：不用容器（systemd）

1. 安裝 [Caddy](https://caddyserver.com/docs/install)。不需要另外安裝 Java：Release 的 `diw-server-<版本>.tar` 附精簡的 Java 執行環境（`runtime/`）。只有 x86_64 Linux 主機可用。
2. 解壓到 `/opt/diw-server`：
   ```sh
   mkdir -p /opt/diw-server
   tar xf diw-server-<版本>.tar -C /opt/diw-server --strip-components=1
   ```
   解壓後應有 `/opt/diw-server/bin/diw-server`、`runtime/` 與 `deploy/`。想改用自己的 Java 21 時，設定 `JAVA_HOME` 即可。
3. 複製設定與服務檔：
   ```sh
   mkdir -p /etc/diw-server
   cp deploy/server.conf.example /etc/diw-server/server.conf   # 修改 DIW_PUBLIC_URL
   cp deploy/systemd/diw-server.service /etc/systemd/system/
   systemctl daemon-reload && systemctl enable --now diw-server
   ```
4. 設定 Caddy：
   - 把 `deploy/caddy/Caddyfile` 複製到 `/etc/caddy/Caddyfile`。
   - 以 `systemctl edit caddy` 讓 Caddy 讀同一份設定：
     ```
     [Service]
     EnvironmentFile=/etc/diw-server/server.conf
     Environment=DIW_UPSTREAM=127.0.0.1:8080
     ```
   - 執行 `systemctl restart caddy`。

服務當掉時，systemd 會自動重啟它；紀錄寫進 journald（`journalctl -u diw-server`）。第一次啟動時會在資料目錄建立類別快取（`diw-server.jsa`），之後啟動時間約減半。

### 方式三：Google Cloud Run（不需主機）

Cloud Run 自己處理 TLS，不用 Caddy；沒有連線時縮到 0 個執行個體，只在使用時計費，個人用量通常在免費額度內。

1. 映像：Cloud Run 不能直接拉 ghcr.io。建立一個 Artifact Registry 遠端存放區代理 ghcr.io：
   ```sh
   gcloud artifacts repositories create ghcr --repository-format=docker --location=asia-east1 \
     --mode=remote-repository --remote-docker-repo=https://ghcr.io
   ```
   映像位址為 `asia-east1-docker.pkg.dev/<專案>/ghcr/mctea01/diw-wallet-server:<版本>`。
2. 產生簽章金鑰（Cloud Run 的磁碟在冷啟動後消失，不能讓服務自己產生）：
   ```sh
   docker run --rm -v "$PWD:/out" --user "$(id -u)" --entrypoint /opt/diw-server/bin/diw-server \
     ghcr.io/mctea01/diw-wallet-server:<版本> keygen /out/signing.pem https://<對外網址>
   ```
   使用網頁查驗端時另以 `keygen /out/verifier.pem https://<對外網址> verifier` 產生查驗端金鑰，信任聯盟用 `federation`。
3. 存取權杖：自訂一個至少 16 字元的權杖，以 `printf '%s\n' "<權杖>" | docker run --rm -i --entrypoint /opt/diw-server/bin/diw-server ghcr.io/mctea01/diw-wallet-server:<版本> hash-token` 取得 SHA-256。
4. 編輯 `server.conf`：`DIW_PUBLIC_URL`、`DIW_FEATURES`、`DIW_ZKTLS_TOKEN_SHA256`，並把金鑰放成一行（換行寫成 `\n`）：
   ```sh
   printf 'DIW_SIGNING_KEY_PEM=%s\n' "$(awk '{printf "%s\\n", $0}' signing.pem)" >> server.conf
   ```
5. 整份設定存進 Secret Manager，讓服務帳號可以讀取：
   ```sh
   gcloud secrets create diw-server-conf --data-file=server.conf
   gcloud secrets add-iam-policy-binding diw-server-conf \
     --member=serviceAccount:<專案編號>-compute@developer.gserviceaccount.com --role=roles/secretmanager.secretAccessor
   ```
   之後改設定：`gcloud secrets versions add diw-server-conf --data-file=server.conf`，再重新部署。保存好 `signing.pem` 後可從本機刪除。
6. 編輯 `deploy/cloudrun/service.yaml`：把 `IMAGE` 換成上面的映像位址。部署並開放公開存取：
   ```sh
   gcloud run services replace deploy/cloudrun/service.yaml --region asia-east1
   gcloud run services add-iam-policy-binding diw-wallet-server --region asia-east1 \
     --member=allUsers --role=roles/run.invoker
   ```
7. 對外網址：可直接用部署後顯示的 `https://...run.app`，或以 `gcloud beta run domain-mappings create` 綁定自己的網域。改網址後要同步更新 `DIW_PUBLIC_URL`。

注意：

- 執行個體上限固定為 1。中繼的兩端必須連到同一個執行個體，不可調高。
- 每條 WebSocket 連線最長 60 分鐘，中繼房間的上限是 15 分鐘，不受影響。
- 閒置後第一次連線需要冷啟動（JVM 映像數秒，原生映像不到 1 秒）。要避免可把 `minScale` 改為 1，但會持續計費。

### 方式四：Cloudflare Containers（不需主機）

Cloudflare Containers 以 Worker 加 Durable Object 轉送到容器，跑的是同一個映像。需要 Workers Paid 方案（每月 5 美元，含 25 GiB-小時記憶體與 375 vCPU-分鐘），閒置後容器休眠，不計執行費用。部署檔在 `deploy/cloudflare/`。

1. 需要 Node.js 20 以上與 Docker（wrangler 以 Docker 重新包裝映像，再推到 Cloudflare 的映像庫）。
2. 編輯 `deploy/cloudflare/Dockerfile`：把 `latest` 換成要部署的版本（或 `<版本>-native`）。
3. 準備 `server.conf`，作法同 Cloud Run 第 2 至 4 步；`DIW_PUBLIC_URL` 為 `https://diw-wallet-server.<帳號子網域>.workers.dev` 或綁定到這個 Worker 的自訂網域。
4. 放入設定並部署（容器的磁碟在重啟後消失，金鑰一定要放在設定裡）：
   ```sh
   cd deploy/cloudflare
   npm install
   npx wrangler login
   npx wrangler secret put SERVER_CONF < ../../server.conf
   npx wrangler deploy
   ```
   名為 `DIW_<鍵>` 的 var 或 secret 會覆寫設定檔中的同名設定。
5. 部署後打開 `<DIW_PUBLIC_URL>/info` 確認功能、`signing.rootSha256` 與 `settingsSha256`。

注意：

- 所有請求都轉到同一個名為 `main` 的執行個體（`max_instances` 為 1）。中繼的兩端必須連到同一個執行個體，不可改成多個。
- 執行個體類型預設 `standard-1`（4 GiB、1/2 vCPU）。TLSNotary 查驗吃 CPU，只開中繼時可改 `basic`（1 GiB、1/4 vCPU）省額度。
- 閒置 `SLEEP_AFTER`（預設 20 分鐘）後休眠，下一個連線需冷啟動（容器 1 至 3 秒，加上 JVM 約 1 秒；原生映像約 0.2 秒）。
- 版型包每次冷啟動都重新下載；台灣出口流量每 GB 0.05 美元（含 500 GB），個人用量通常用不完。

### 臨時證明器下載與執行

Release 附有 `diw-server prover` 可直接執行的版本，原生證明程式庫已含 zkPassport 需要的 UltraHonk：

| 檔案 | 系統 | 需求 |
| --- | --- | --- |
| `diw-server-<版本>-all.jar` | 任何系統（Windows x86_64、macOS Apple silicon、Linux x86_64 與 ARM64） | Java 21 以上 |
| `diw-server-<版本>-windows-x86_64.zip` | Windows 10 以上 | 無 |
| `diw-server-<版本>-macos-aarch64.tar.gz` | macOS 14 以上（Apple silicon） | 無 |
| `diw-server-<版本>-linux-x86_64.tar.gz`、`-linux-aarch64.tar.gz` | Linux | 無 |

```sh
java -jar diw-server-<版本>-all.jar prover   # jar 版
./diw-server prover                          # 原生版（Windows 為 diw-server.exe prover）
```

- 電腦與手機要在同一個內網（或同一個 Tailscale 網路）。程式自動選內網位址，選錯時加 `--address <IP>`；埠預設 8090，可用 `--port` 改。
- 第一次執行時 Windows 防火牆與 macOS 會詢問是否允許連入，請只允許私人網路。
- macOS 從瀏覽器下載的原生版未經 Apple 公證，第一次執行前需 `xattr -d com.apple.quarantine diw-server`，或改用 jar 版。
- Windows Terminal（Windows 11 預設）會直接畫出 QR Code；傳統主控台改把 QR Code 存成資料目錄中的 `pairing-code.svg` 並印出路徑，用瀏覽器開啟後掃描，程式結束時刪除。
- 啟動後會下載約 130 MB 的 CRS 到 `DIW_DATA_DIR`（預設 `~/.diw-server`），之後沿用；證明時的記憶體高峰視護照簽章演算法而定，RSA 約 1 GB，P-521 等可到 2 GB。

### 原生執行檔（實驗）

CI 另外以 GraalVM native-image 建出原生執行檔：Release 的 `diw-server-<版本>-native`（x86_64 Linux）與映像 `ghcr.io/mctea01/diw-wallet-server:<版本>-native`。功能、設定、資料目錄與指令都和 JVM 版相同，只換掉執行方式：

- 實測（4 核心開發機，只開中繼）：從啟動到 `/healthz` 可回應約 0.17 秒，啟動後 RSS 約 79 MB；JVM 版加類別快取為 0.8 秒、103 MB。冷啟動計費的 Cloud Run、Cloudflare 與 1 GB 主機最有感。
- zkTLS 查驗主機的原生程式庫（tlsn）同樣以 JNA 載入，端對端測試（持有人透過原生服務端完成網頁證明）通過。
- 不用 JVM 時，`JAVA_OPTS` 與類別快取設定沒有作用。systemd 使用時把 `bin/diw-server` 換成原生執行檔即可。
- JVM 版仍是預設，原生版遇到問題時改回同版本的 JVM 映像即可，設定不必改。

### 信任聯盟（OpenID Federation）

1. 產生聯盟金鑰：自架主機在 `features` 加 `federation` 後會自動產生 `data_dir/federation.pem`；不保存磁碟的平台用 `keygen <檔案> <對外網址> federation` 產生，放進 `DIW_FEDERATION_KEY_PEM`。
2. 設定 `DIW_FEDERATION_AUTHORITY_HINTS` 為上級（Trust Anchor 或 Intermediate）的 Entity Identifier；上級核發的 Trust Mark 放進 `DIW_FEDERATION_TRUST_MARKS`。
3. 把 `diw-server federation-jwks` 印出的公鑰交給上級登錄。
4. 自己當上級：準備 `federation_subordinates_file`（JSON：`{"subordinates":[{"entity_id":..., "jwks":{...}, "entity_types":[...]}]}`），每個下級可用 `diw-server federation-enroll <entity_id>` 讀取並檢查其 Entity Configuration，印出要加入的項目；加入前請和下級的業者比對金鑰指紋。檔案修改後不必重啟。
5. 在錢包「信任錨點」頁的「OpenID Federation 信任錨點」加入 Trust Anchor 後，加入這台服務端時，錢包會解析信任鏈，確認聯盟登錄的根憑證指紋與網頁查驗端 DID 和服務端回報的相同，就不必逐字比對；不同時拒絕信任。

### 金鑰與權杖

- 簽章金鑰（`zktls`）：簽查驗請求與網頁證明回執。自架主機第一次啟動時自動產生在 `data_dir/signing.pem`（權限 0600），之後沿用；也可以 `diw-server keygen <檔案> <對外網址>` 預先產生，再以 `DIW_SIGNING_KEY_FILE` 指定，或把整份 PEM 放進 `DIW_SIGNING_KEY_PEM`（Cloud Run 與 Cloudflare 用這個）。
- 換鑰會讓根憑證指紋改變，所有錢包都要重新確認信任，請備份 `signing.pem`。
- 存取權杖：設定只存 SHA-256（`DIW_ZKTLS_TOKEN_SHA256`，可用逗號列多個，方便輪換）。權杖本身交給要使用這台主機的人，填進錢包，或由錢包「分享設定」時勾選一併分享。
- 行動自然人憑證 SP（`twfido`）：`DIW_TWFIDO_SERVICE_ID` 與 `DIW_TWFIDO_AES_KEY`（內政部核發，AES 金鑰為 base64 的 32 位元組）、`DIW_TWFIDO_TOKEN_SHA256`（未設定時沿用 `DIW_ZKTLS_TOKEN_SHA256`；錢包只填一個權杖，兩者都要含錢包用的那個），測試帳號另設 `DIW_TWFIDO_ENVIRONMENT=uat`。AES 金鑰和其他秘密一樣只放在權限 0600 的 `server.conf`（或平台的 secret）。
- OPRF 金鑰（`oprf`）：`diw-server oprf-keygen` 印出新金鑰（`DIW_OPRF_KEY=0x…`，有原生 OPRF 時一併印出公鑰），和其他秘密一樣只放在權限 0600 的 `server.conf`（或平台的 secret）。`DIW_OPRF_TOKEN_SHA256` 未設定時沿用 `DIW_ZKTLS_TOKEN_SHA256`。換鑰時改 `DIW_OPRF_KEY_ID`；換鑰後所有加鹽識別碼都會改變，舊的不再對得上。
- 金鑰、權杖與憑證從不寫入 log。

### 錢包端

在錢包設定的「零知識證明」頁的「正式服務端」填入 `https://<你的網域>`，同一個網址用於中繼、zkTLS 查驗主機與年齡查驗主機。啟用 `zktls` 時：

1. 填入存取權杖並儲存。
2. 按「加入信任」，把畫面上的 SHA-256 與 `/info` 的 `signing.rootSha256`（或部署時 `keygen` 印出的值）逐字比對，相符再確認。之後這台主機簽的查驗請求與回執會以使用者匯入（T2）顯示；啟用 `verifier` 時，網頁查驗端的 DID 也一併加入信任，出示時顯示為使用者信任（T2）。服務端在信任聯盟中時，錢包會自動確認，不必比對。

其他人要用同一台服務端時，按「分享設定」顯示 QR Code 或複製 `diw-server://` 連結，對方掃描或貼上即可匯入；指紋隨設定一起分享，對方匯入時錢包會和服務端實際的根憑證比對，存取權杖只在勾選時才包含。

兩個錢包之間只需要一方有服務端：

- 網站查驗：出示方在同意畫面按「顯示我的服務端 QR Code」，查驗方在等待畫面按「使用對方的服務端」掃描，查驗方會改在對方服務端的中繼開房間。兩方都有服務端時，查驗方在兩台都開房間，出示方依「雙方都有服務端時」的選擇（優先自己、優先對方、只用自己）挑一台。
- 年齡查驗：查驗方沒有服務端時，同樣可掃描對方分享的服務端，請求改經對方的服務端轉送。
- 對方的服務端只用在這一次查驗，不會加入信任或儲存。

啟用 `twfido` 時，錢包以行動自然人憑證簽署時可選「正式服務端的 SP」（設定了正式服務端時為預設），使用同一個存取權杖。

## 開發

錢包 repo 以 git submodule 釘選在 `wallet/`，服務端透過 composite build 使用其中的 `core`。建置需要 JDK 21 與 Android SDK；只開中繼時不需要原生程式庫；zkTLS 查驗主機需要含 `tlsn` 的原生程式庫（`DIW_ZK_NATIVE=require DIW_ZK_FEATURES=tlsn`，需要 Rust 工具鏈），否則 `/healthz` 回 503。

```sh
git submodule update --init
export ANDROID_HOME=/path/to/android-sdk DIW_ZK_NATIVE=skip
./gradlew test installDist distTar   # 在 x86_64 Linux 上，發行檔附 jlink 執行環境（runtime/）
docker build -t diw-wallet-server .  # 只打包 build/install/diw-server
```

原生執行檔需要 GraalVM 25（`GRAALVM_HOME`；21 版編不過 Kotlin 協程）：

```sh
GRAALVM_HOME=/path/to/graalvm-25 ./gradlew nativeCompile   # build/native/nativeCompile/diw-server
docker build -f Dockerfile.native -t diw-wallet-server:native .
```

- 反射與資源設定在 `src/main/resources/META-INF/native-image/`，由 tracing agent 跑完整測試產生：`GRAALVM21_HOME=/path/to/graalvm-21 tools/native-metadata.sh`（agent 需和測試的 Java 21 同版）。新增路由或依賴後重跑並提交結果。
- `./gradlew test -Pdiw.nativeServer=build/native/nativeCompile/diw-server` 讓 zkTLS 端對端測試改對原生執行檔執行。
- 新增或修改設定時改 `ConfigKeys.kt`，再以 `diw-server config-template > deploy/server.conf.example` 重新產生範本；測試會比對兩者。

- 升級錢包版本：在 `wallet/` checkout 新的 commit，提交 submodule 指標的變更，並確認 CI 通過。
- CI 在 [.github/workflows/docker-image.yml](.github/workflows/docker-image.yml)：每次推送與 PR 執行測試，推送 `v*` 標籤時發佈映像與 Release。Agent 無法推送 workflow 檔，修改需由有權限的人貼上。

## 授權

Apache-2.0，見 [LICENSE](LICENSE)。第三方元件與各自的授權列在 [NOTICE](NOTICE)；錢包 `core` 與原生程式庫帶進來的元件列在錢包 repo 的 NOTICE。
