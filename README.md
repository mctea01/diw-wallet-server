# diw-wallet-server

[DIW MY WALLET](https://github.com/mctea01/my-Bonds-fork-android) 的正式環境服務端。設計與里程碑見 [docs/DESIGN.md](docs/DESIGN.md)。

目前功能：

- **中繼**（`/zktls/relay/{room}`）：讓不在同一網路的兩個裝置連線。只轉送端對端加密的資料，不儲存任何內容。
- **zkTLS 查驗主機**（`/zktls/*`，`features` 加 `zktls`）：以 TLSNotary 的 proxy 模式查驗持有人對政府查驗頁的網頁證明，並簽發網頁證明回執。只查驗已安裝簽章版型包中的頁面；建立查驗需要存取權杖，主機只存權杖的 SHA-256。簽章金鑰的根憑證指紋列在 `/info`，錢包加入服務端時讓使用者比對後加入信任。
- **Bonds 年齡查驗主機**（`/api/zkp/*`，可選，`features` 加 `bonds-age`）：與 Bonds 查驗網站相同的 API。預設只轉送證明包，由查驗方的手機驗證；設 `bonds_age_verify=true` 時改在主機驗證（需較大記憶體，不適合 1 GB 主機）。
- **網頁查驗端**（`/verify`，可選，`features` 加 `verifier`）：查驗方在網頁輸入卡片類型與欄位，持卡人以政府數位憑證皮夾掃描 QR Code 出示，頁面顯示判定與要求的欄位；也可查驗 Bonds 的自然人憑證自發卡。可設 `verifier_access_token` 限制只有知道存取碼的人能建立查驗。
- **健康檢查**（`/healthz`）與**版本資訊**（`/info`）。

zkPDF 證明器（S4）尚未實作。

## 部署

需求：

- 一個網域，DNS 已指向主機。
- 對外開放 80 與 443 連接埠。
- 1 GB 記憶體即可，建議另加 1 GB swap。

TLS 憑證由 Caddy 自動向 Let's Encrypt 申請與更新，不需要手動操作。

### 方式一：容器（建議）

```sh
git clone https://github.com/mctea01/diw-wallet-server && cd diw-wallet-server
cp .env.example .env    # 填入 DIW_DOMAIN 與 DIW_ACME_EMAIL
docker compose up -d
```

- 映像由 CI 在推送 `v*` 標籤時發佈到 `ghcr.io/mctea01/diw-wallet-server`。
- 兩個容器都設定自動重啟。服務的健康檢查通過後，Caddy 才會啟動。
- 查看狀態：`docker compose ps`。查看紀錄：`docker compose logs`。
- 更新：`docker compose pull && docker compose up -d`。
- 啟用 zkTLS 查驗主機：在 `.env` 設 `DIW_FEATURES=relay,zktls,bonds-age`，執行 `docker compose run --rm server hash-token` 輸入自訂的權杖（至少 16 字元），把印出的值填進 `DIW_ZKTLS_TOKEN_SHA256`，再 `docker compose up -d`。簽章金鑰會自動產生在 `server-data` volume，請備份其中的 `signing.pem`（`docker compose cp server:/var/lib/diw-server/signing.pem .`）。

### 方式二：不用容器（systemd）

1. 安裝 [Caddy](https://caddyserver.com/docs/install)。不需要另外安裝 Java：Release 的 `diw-server-<版本>-linux-x86_64.tar.gz` 附精簡的 Java 執行環境（`runtime/`）。只有 x86_64 Linux 主機可用。
2. 解壓到 `/opt/diw-server`：
   ```sh
   mkdir -p /opt/diw-server
   tar xzf diw-server-<版本>-linux-x86_64.tar.gz -C /opt/diw-server --strip-components=1
   ```
   解壓後應有 `/opt/diw-server/bin/diw-server` 與 `/opt/diw-server/deploy/`。只想用自己的 Java 21 時，可改用不含執行環境的 `diw-server-<版本>.tar`，並設定 `JAVA_HOME`。
3. 複製設定與服務檔：
   ```sh
   mkdir -p /etc/diw-server
   cp deploy/systemd/server.conf.example /etc/diw-server/server.conf   # 修改 public_url
   cp deploy/systemd/diw-server.service /etc/systemd/system/
   systemctl daemon-reload && systemctl enable --now diw-server
   ```
4. 設定 Caddy：
   - 把 `deploy/caddy/Caddyfile` 複製到 `/etc/caddy/Caddyfile`。
   - 以 `systemctl edit caddy` 加入下列環境變數：
     ```
     [Service]
     Environment=DIW_DOMAIN=diw.example.org DIW_ACME_EMAIL=admin@example.org DIW_UPSTREAM=127.0.0.1:8080
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
2. 簽章金鑰放進 Secret Manager（Cloud Run 的磁碟在冷啟動後消失，不能讓服務自己產生）：
   ```sh
   docker run --rm -v "$PWD:/out" --user "$(id -u)" --entrypoint /opt/diw-server/bin/diw-server \
     ghcr.io/mctea01/diw-wallet-server:<版本> keygen /out/signing.pem https://<對外網址>
   gcloud secrets create diw-signing-key --data-file=signing.pem
   gcloud secrets add-iam-policy-binding diw-signing-key \
     --member=serviceAccount:<專案編號>-compute@developer.gserviceaccount.com --role=roles/secretmanager.secretAccessor
   ```
   保存好 `signing.pem` 後可從本機刪除。使用網頁查驗端時，以 `keygen /out/verifier.pem https://<對外網址> verifier` 產生查驗端金鑰，同樣存成 `diw-verifier-key`，並取消 `service.yaml` 中 `DIW_VERIFIER_KEY_PEM` 的註解。
3. 存取權杖：自訂一個至少 16 字元的權杖，以 `printf '%s\n' "<權杖>" | docker run --rm -i --entrypoint /opt/diw-server/bin/diw-server ghcr.io/mctea01/diw-wallet-server:<版本> hash-token` 取得 SHA-256。
4. 編輯 `deploy/cloudrun/service.yaml`：把 `IMAGE` 換成上面的映像位址，`PUBLIC_URL` 換成對外網址，`TOKEN_SHA256` 換成上一步的值。
5. 部署並開放公開存取：
   ```sh
   gcloud run services replace deploy/cloudrun/service.yaml --region asia-east1
   gcloud run services add-iam-policy-binding diw-wallet-server --region asia-east1 \
     --member=allUsers --role=roles/run.invoker
   ```
6. 對外網址：可直接用部署後顯示的 `https://...run.app`，或以 `gcloud beta run domain-mappings create` 綁定自己的網域。改網址後要同步更新 `PUBLIC_URL` 並重新部署。

注意：

- 執行個體上限固定為 1。中繼的兩端必須連到同一個執行個體，不可調高。
- 每條 WebSocket 連線最長 60 分鐘，中繼房間的上限是 15 分鐘，不受影響。
- 閒置後第一次連線需要冷啟動（數秒）。要避免可把 `minScale` 改為 1，但會持續計費。

### 方式四：Cloudflare Containers（不需主機）

Cloudflare Containers 以 Worker 加 Durable Object 轉送到容器，跑的是同一個映像。需要 Workers Paid 方案（每月 5 美元，含 25 GiB-小時記憶體與 375 vCPU-分鐘），閒置後容器休眠，不計執行費用。部署檔在 `deploy/cloudflare/`。

1. 需要 Node.js 20 以上與 Docker（wrangler 以 Docker 重新包裝映像，再推到 Cloudflare 的映像庫）。
2. 編輯 `deploy/cloudflare/Dockerfile`：把 `latest` 換成要部署的版本。
3. 編輯 `deploy/cloudflare/wrangler.jsonc` 的 `vars`：
   - `PUBLIC_URL`：`https://diw-wallet-server.<帳號子網域>.workers.dev`，或綁定到這個 Worker 的自訂網域。
   - `FEATURES`：要啟用的功能。
   - `ZKTLS_TOKEN_SHA256`：存取權杖的 SHA-256（取得方式同 Cloud Run 第 3 步）。
4. 放入金鑰（容器的磁碟在重啟後消失，金鑰一定要用 secret）：
   ```sh
   cd deploy/cloudflare
   npm install
   npx wrangler login
   npx wrangler secret put SIGNING_KEY_PEM < signing.pem      # 產生方式同 Cloud Run 第 2 步
   npx wrangler secret put VERIFIER_KEY_PEM < verifier.pem    # 只有啟用 verifier 時需要
   npx wrangler deploy
   ```
5. 部署後打開 `<PUBLIC_URL>/info` 確認功能與 `signing.rootSha256`。

注意：

- 所有請求都轉到同一個名為 `main` 的執行個體（`max_instances` 為 1）。中繼的兩端必須連到同一個執行個體，不可改成多個。
- 執行個體類型預設 `standard-1`（4 GiB、1/2 vCPU）。TLSNotary 查驗吃 CPU，只開中繼時可改 `basic`（1 GiB、1/4 vCPU）省額度。
- 閒置 `SLEEP_AFTER`（預設 20 分鐘）後休眠，下一個連線需冷啟動（容器 1 至 3 秒，加上 JVM 約 1 秒）。
- 版型包每次冷啟動都重新下載；台灣出口流量每 GB 0.05 美元（含 500 GB），個人用量通常用不完。

### 設定

所有設定與預設值見 [docs/DESIGN.md](docs/DESIGN.md) 第 5.5 節。每個設定都可以寫在設定檔，也可以用環境變數 `DIW_<鍵的大寫>` 指定，例如 `DIW_PUBLIC_URL`。

### 金鑰與權杖

- 簽章金鑰（`zktls`）：簽查驗請求與網頁證明回執。自架主機第一次啟動時自動產生在 `data_dir/signing.pem`（權限 0600），之後沿用；也可以 `diw-server keygen <檔案> <對外網址>` 預先產生，再以 `signing_key_file` 指定，或把整份 PEM 放進 `signing_key_pem`（Cloud Run 與 Cloudflare 用這個）。
- 換鑰會讓根憑證指紋改變，所有錢包都要重新確認信任，請備份 `signing.pem`。
- 存取權杖：設定只存 SHA-256（`zktls_token_sha256`，可用逗號列多個，方便輪換）。權杖本身交給要使用這台主機的人，填進錢包。
- 金鑰、權杖與憑證從不寫入 log。

### 錢包端

在錢包設定的「零知識證明」頁的「正式服務端」填入 `https://<你的網域>`，同一個網址用於中繼、zkTLS 查驗主機與年齡查驗主機。啟用 `zktls` 時：

1. 填入存取權杖並儲存。
2. 按「加入信任」，把畫面上的 SHA-256 與 `/info` 的 `signing.rootSha256`（或部署時 `keygen` 印出的值）逐字比對，相符再確認。之後這台主機簽的查驗請求與回執會以使用者匯入（T2）顯示。

## 開發

錢包 repo 以 git submodule 釘選在 `wallet/`，服務端透過 composite build 使用其中的 `core`。建置需要 JDK 21 與 Android SDK；只開中繼時不需要原生程式庫；zkTLS 查驗主機需要含 `tlsn` 的原生程式庫（`DIW_ZK_NATIVE=require DIW_ZK_FEATURES=tlsn`，需要 Rust 工具鏈），否則 `/healthz` 回 503。

```sh
git submodule update --init
export ANDROID_HOME=/path/to/android-sdk DIW_ZK_NATIVE=skip
./gradlew test installDist runtimeImage
docker build -t diw-wallet-server .   # 只打包 build/install/diw-server 與 build/runtime
./gradlew bundleTar                   # 附執行環境的發行檔（只能在 x86_64 Linux 建置）
```

- 升級錢包版本：在 `wallet/` checkout 新的 commit，提交 submodule 指標的變更，並確認 CI 通過。
- CI 在 [.github/workflows/docker-image.yml](.github/workflows/docker-image.yml)：每次推送與 PR 執行測試，推送 `v*` 標籤時發佈映像與 Release。Agent 無法推送 workflow 檔，修改需由有權限的人貼上。

## 授權

Apache-2.0，見 [LICENSE](LICENSE)。
