# diw-wallet-server

[DIW MY WALLET](https://github.com/mctea01/my-Bonds-fork-android) 的正式環境服務端。設計與里程碑見 [docs/DESIGN.md](docs/DESIGN.md)。

目前功能（S1）：

- **中繼**（`/zktls/relay/{room}`）：讓不在同一網路的兩個裝置連線。只轉送端對端加密的資料，不儲存任何內容。
- **Bonds 年齡查驗主機**（`/api/zkp/*`，可選，`features` 加 `bonds-age`）：與 Bonds 查驗網站相同的 API。預設只轉送證明包，由查驗方的手機驗證；設 `bonds_age_verify=true` 時改在主機驗證（需較大記憶體，不適合 1 GB 主機）。
- **網頁查驗端**（`/verify`，可選，`features` 加 `verifier`）：查驗方在網頁輸入卡片類型與欄位，持卡人以政府數位憑證皮夾掃描 QR Code 出示，頁面顯示判定與要求的欄位；也可查驗 Bonds 的自然人憑證自發卡。可設 `verifier_access_token` 限制只有知道存取碼的人能建立查驗。
- **健康檢查**（`/healthz`）與**版本資訊**（`/info`）。

zkTLS 查驗主機與網頁證明回執（S2）、zkPDF 證明器（S4）尚未實作。

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

### 方式二：不用容器（systemd）

1. 安裝 Java 21 執行環境（例如 `apt install openjdk-21-jre-headless`）與 [Caddy](https://caddyserver.com/docs/install)。
2. 把 Release 的 `diw-server-<版本>.tar` 解壓到 `/opt/diw-server`（解壓後應有 `/opt/diw-server/bin/diw-server`）。
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

服務當掉時，systemd 會自動重啟它；紀錄寫進 journald（`journalctl -u diw-server`）。

### 方式三：Google Cloud Run（不需主機）

Cloud Run 自己處理 TLS，不用 Caddy；沒有連線時縮到 0 個執行個體，只在使用時計費，個人用量通常在免費額度內。

1. 映像：Cloud Run 不能直接拉 ghcr.io。建立一個 Artifact Registry 遠端存放區代理 ghcr.io：
   ```sh
   gcloud artifacts repositories create ghcr --repository-format=docker --location=asia-east1 \
     --mode=remote-repository --remote-docker-repo=https://ghcr.io
   ```
   映像位址為 `asia-east1-docker.pkg.dev/<專案>/ghcr/mctea01/diw-wallet-server:<版本>`。
2. 編輯 `deploy/cloudrun/service.yaml`：把 `IMAGE` 換成上面的映像位址，`PUBLIC_URL` 換成對外網址。
3. 部署並開放公開存取：
   ```sh
   gcloud run services replace deploy/cloudrun/service.yaml --region asia-east1
   gcloud run services add-iam-policy-binding diw-wallet-server --region asia-east1 \
     --member=allUsers --role=roles/run.invoker
   ```
4. 對外網址：可直接用部署後顯示的 `https://...run.app`，或以 `gcloud beta run domain-mappings create` 綁定自己的網域。改網址後要同步更新 `PUBLIC_URL` 並重新部署。

注意：

- 執行個體上限固定為 1。中繼的兩端必須連到同一個執行個體，不可調高。
- 每條 WebSocket 連線最長 60 分鐘，中繼房間的上限是 15 分鐘，不受影響。
- 閒置後第一次連線需要冷啟動（數秒）。要避免可把 `minScale` 改為 1，但會持續計費。

### Cloudflare Workers

Workers 不能執行 JVM，之後的 zkTLS 查驗主機與證明器也無法在上面執行。只有中繼可以用 Durable Objects 另外重寫，目前不提供，評估見 [docs/DESIGN.md](docs/DESIGN.md) 第 5.4 節。

### 設定

所有設定與預設值見 [docs/DESIGN.md](docs/DESIGN.md) 第 5.5 節。每個設定都可以寫在設定檔，也可以用環境變數 `DIW_<鍵的大寫>` 指定，例如 `DIW_PUBLIC_URL`。

### 錢包端

在錢包的設定中，把查驗主機設為 `https://<你的網域>`。錢包會使用其中的中繼（`/zktls/relay`）。啟用 `bonds-age` 時，把「年齡查驗主機」也設為同一網址。

## 開發

錢包 repo 以 git submodule 釘選在 `wallet/`，服務端透過 composite build 使用其中的 `core`。建置需要 JDK 21 與 Android SDK；只開中繼時不需要原生程式庫。

```sh
git submodule update --init
export ANDROID_HOME=/path/to/android-sdk DIW_ZK_NATIVE=skip
./gradlew test installDist
docker build -t diw-wallet-server .   # 只打包 build/install/diw-server
```

- 升級錢包版本：在 `wallet/` checkout 新的 commit，提交 submodule 指標的變更，並確認 CI 通過。
- CI 工作流程的草稿在 [docs/ci/ci.yml](docs/ci/ci.yml)，需由有權限的人放到 `.github/workflows/`。

## 授權

Apache-2.0，見 [LICENSE](LICENSE)。
