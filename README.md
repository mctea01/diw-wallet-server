# diw-wallet-server

[DIW MY WALLET](https://github.com/mctea01/my-Bonds-fork-android) 的正式環境服務端。設計與里程碑見 [docs/DESIGN.md](docs/DESIGN.md)。

目前功能（S1）：

- **中繼**（`/zktls/relay/{room}`）：讓不在同一網路的兩個裝置連線。只轉送端對端加密的資料，不儲存任何內容。
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

### 設定

所有設定與預設值見 [docs/DESIGN.md](docs/DESIGN.md) 第 5.3 節。每個設定都可以寫在設定檔，也可以用環境變數 `DIW_<鍵的大寫>` 指定，例如 `DIW_PUBLIC_URL`。

### 錢包端

在錢包的設定中，把查驗主機設為 `https://<你的網域>`。錢包會使用其中的中繼（`/zktls/relay`）。

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
