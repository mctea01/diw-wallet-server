# 服務端現況

最後更新：2026-11-27（`tools/windows-sign.ps1` 改為簽署 Windows 原生證明器資料夾內（含子資料夾）所有未簽章的 .exe 與 .dll，簽完確認沒有遺漏；main 到 PR #48）。

這一頁是服務端狀態的唯一來源：功能做到哪裡、還缺什麼、哪些等實機驗收。README 只寫功能與部署步驟，[DESIGN.md](DESIGN.md) 只寫設計與理由，兩者都不記錄進度。錢包本身的狀態見錢包 repo 的 [docs/STATUS.md](https://github.com/mctea01/my-Bonds-fork-android/blob/main/docs/STATUS.md)。

**維護規則：** 會改變狀態的提交或 PR（完成或新增功能、留下未完成項目、部署方式或相依改變）要在同一個 PR 內更新本頁與「最後更新」；規則見 [AGENTS.md](../AGENTS.md)。Fork 之後的設定見 [fork-setup.md](fork-setup.md)。

## 1. 一眼看完

- 版本線 `0.5`（[version.properties](../version.properties)，與錢包相同的規則：修訂號是之後的 commit 數）。上一個標籤是 `v0.4.1`（2026-11-15，當時發行檔仍叫 `diw-server-0.1.0.tar`）；之後的穩定版由 `Stable release` workflow 發佈，發行檔名與標籤一致，見 [fork-setup.md](fork-setup.md) 第 3 節；workflow 已在 main，還沒有發過 0.5 的穩定版。
- 里程碑 S1 到 S3（中繼、zkTLS 查驗主機、發行檔與四種部署方式）的程式與自動測試都已完成；S4（zkPDF 證明器）沒有做。
- S3 之後加入的可選功能都已在 main：Bonds 年齡查驗主機、網頁查驗端、行動自然人憑證簽署、信任聯盟、本專案 OPRF、自己的 zkPassport 證明器、臨時證明器、GraalVM 原生執行檔（實驗）；OpenID4VP 轉送信箱在錢包 P5 的 PR 中（見第 4 節）。
- 沒有任何一種部署方式有實機驗收紀錄，見第 4 節。
- 錢包以 git submodule 釘選在 `wallet/`，釘的是錢包 main 上的 commit（錢包 PR #173 的工具鏈升級：錢包改用 AGP 9.5 與 Gradle 9，服務端引入錢包建置，所以兩邊的 Gradle 要一起升到 9.x）。
- 原生證明程式庫只在 `zktls`、`oprf`、`zkpassport`、`bonds_age_verify` 啟用時載入。Linux 另附相容版（不含 Barretenberg、glibc 2.28、任何 x86_64 CPU），完整版因 CPU 或 glibc 不能用時自動改用；`diw-server native-check` 顯示結果與原因。

## 2. 功能狀態

狀態用語：**可用**（已在 main、有自動測試）、**實驗**（可用但不是預設）、**未做**。

| 功能 | `features` | 狀態 | 測試 | 備註 |
|---|---|---|---|---|
| 中繼 `/zktls/relay/{room}` | `relay`（預設） | 可用 | `ServerTest` | 只轉送端對端加密的資料；超過 `relay_max_frame_kb` 很多的訊框（Ktor 讀取上限）也以「Room limit」結束（2026-11-21 修正，之前誤報「Room expired」） |
| zkTLS 查驗主機與網頁證明回執 | `zktls` | 可用 | `ZkTlsHostTest`（原生 TLSNotary 端對端） | 需要含 `tlsn` 的原生程式庫 |
| Bonds 年齡查驗主機 `/api/zkp/*` | `bonds-age` | 可用 | `BondsAgeTest` | 預設只轉送；主機驗證需載入 432 MB 驗證金鑰，不適合 1 GB 主機 |
| 網頁查驗端 `/verify` | `verifier` | 可用 | `VerifierTest` | 以官方查驗端的方言請求卡片，也可查驗 Bonds 自發卡 |
| 行動自然人憑證簽署 `/twfido/sign` | `twfido` | 可用；沒有以真實 SP 帳號驗收 | `TwFidoHostTest`（本機模擬的內政部服務） | 需要內政部核發的 SP 帳號 |
| 信任聯盟（OpenID Federation 1.0） | `federation` | 可用 | `FederationTest` | 可當下級，也可當上級 |
| 本專案 OPRF `/oprf/v1/*` | `oprf` | 可用 | `OprfHostTest` | 需要含 `oprf` 的原生程式庫 |
| 自己的 zkPassport 證明器 `/zkpassport/v1/jobs` | `zkpassport` | 可用 | `ZkPassportHostTest`、`ZkPassportClientInteropTest` | 需要含 `zkpdf` 的原生程式庫；1 GB 主機可能不夠；本專案 FaceMatch（手機比對並簽署後轉送）的金鑰證明版電路由錢包 release `zkpassport-facematch-v1` 提供（2026-11-15 已發佈） |
| OpenID4VP 轉送信箱 `/oid4vp/v1/*` | `oid4vp` | 可用 | `Oid4vpMailboxTest`（以錢包 core 的用戶端對路由） | 手機查驗方的線上查驗；只轉存加密回應。不含在 `relay` 裡，P5 以前設定的服務端要在 `features` 加上 `oid4vp`（需要 `oid4vp_token_sha256` 或 `zktls_token_sha256`），否則錢包的「經服務端轉送」會被拒（錢包現在會先讀 `/info` 並說明） |
| 臨時證明器 `diw-server prover` | 指令 | 可用 | `ZkPassportPairingTest` | 內網限定、一次性 QR 配對 |
| 健康檢查與版本資訊 `/healthz`、`/info` | 固定 | 可用 | `ServerTest`、`ConfigTest` | |
| 原生程式庫按需載入與 Linux 相容版 | `native_library` | 可用；在 Debian 10、11、12 與 Ubuntu 22.04 容器實測改用相容版並載入 TLSNotary，zkTLS 端對端測試以相容版通過；沒有在不支援 x86-64-v2 的實體 CPU 上驗證 | `ServerTest`、`ZkTlsHostTest` | 相容版沒有 zkPDF 與 zkPassport；完整版需要 glibc 2.38 與 libstdc++ 13 |
| GraalVM 原生執行檔與 `-native` 映像 | | 實驗；v0.4.1 的 x86_64 版在 ubuntu-26.04 上建置，被標記需要 x86-64-v3，較舊的 CPU 啟動時出現「CPU ISA level is lower than required」（使用者 VPS 實際遇到）。`nativeCompile` 之後的 `checkNativeIsa` 現在擋下這種執行檔。建置參數含 `--enable-native-access=ALL-UNNAMED`，JNA 呼叫 `System.load` 時不再出現 GraalVM 25 的受限方法警告。JNA 原本把 libjnidispatch.so 解到 /tmp，/tmp 為 noexec 時載入失敗（「Could not initialize class com.sun.jna.NativeLibrary」，JVM 版也一樣），現在未設 `jna.tmpdir` 時改用 data_dir/native | zkTLS 端對端測試可改對原生執行檔執行；`checkNativeIsa` 以 v0.4.1 執行檔驗證會失敗 | JVM 版仍是預設；workflow 的 x86_64 Linux 原生工作已改用 ubuntu-24.04，CI 建出的原生執行檔可通過檢查 |
| zkPDF 證明器（S4） | | 未做 | | 錢包的「送到自架主機產生」目前用錢包 repo 的 `tools/zk-prover-server` |

## 3. 部署與發佈

| 方式 | 檔案 | 狀態 |
|---|---|---|
| 容器（compose 加 Caddy） | `compose.yaml`、`Dockerfile`、`deploy/caddy` | 檔案與文件齊備；映像只有 amd64 |
| 不用容器（systemd） | `deploy/systemd`、Release 的 tar（附 jlink 執行環境，x86_64 Linux，glibc 2.28 以上） | 檔案與文件齊備；原生執行檔需要 glibc 2.34 以上 |
| Google Cloud Run | `deploy/cloudrun/service.yaml` | 檔案與文件齊備 |
| Cloudflare Containers | `deploy/cloudflare` | 檔案與文件齊備 |
| 臨時證明器下載（jar 與各系統的原生版） | `.github/workflows/prover.yml` | workflow 已在 main；推標籤時附加到 Release，也可手動執行取得 Artifact |
| 穩定版發佈 | `tools/stable-release.sh`、`tools/release-notes.py`、`.github/workflows/stable-release.yml` | 腳本與 workflow 都在 main，尚未實際發佈；換版本線需要 secret `RELEASE_TOKEN` |
| Windows 程式碼簽章 | `tools/windows-sign.ps1` | 在本機以 YubiKey 簽署 Release 的 Windows 原生證明器（資料夾內含子資料夾的所有 .exe、.dll，簽完確認沒有遺漏）與 jar 內的 Windows 原生程式庫並傳回；尚未在 Windows 實際執行過 |

所有方式讀同一份 `server.conf`（`deploy/server.conf.example` 由程式產生，測試會比對）。CI 在每次推送與 PR 執行測試，`v*` 標籤時才發佈映像與 Release；Release 內文最上方列出各部署方式該用的檔案，下面是變更。

## 4. 未完成與待驗收

未完成：

- S4 zkPDF 證明器。
- 待合併的相依更新：GitHub Action 的版本與 digest（PR #31、#32、#39、#40），屬 workflow 檔，需由使用者合併。
- 穩定版發佈 workflow 貼上後的第一次實際發佈（尚未執行過）。
- 完整版 Linux 程式庫改以 Zig 與靜態 libc++ 建置（錢包 `DIW_ZK_LINUX_LIBCXX=1`），讓 zkPassport 等完整功能也能在舊系統使用；預設尚未開啟，見錢包 STATUS。
- GraalVM 原生執行檔的 glibc 下限（2.34）沒有降低；舊系統請用 JVM 版。
- `docker-image.yml` 的 `native` 與 `prover.yml` 的 `linux-x86_64`（`library`、`native`）要從 `ubuntu-latest` 改成 `ubuntu-24.04`：ubuntu-26.04 runner 用 amd64v3 套件，C 啟動檔把執行檔標成 x86-64-v3，程式庫也會連到 glibc 2.43。Workflow 已由使用者貼上。
- OpenID4VP 轉送信箱：新增路由後的 GraalVM 原生中繼資料（`tools/native-metadata.sh`，需 GraalVM 21）尚未重新產生，原生執行檔版可能缺少這組路由用到的反射設定。

待實機驗收（由部署的人執行，沒有紀錄）：

- 在 `MemoryMax=900M` 的 cgroup 中以容器為主機跑完整的 zkTLS 查驗，記錄記憶體峰值。
- 乾淨 VPS 依 README 部署：Caddy 取得憑證、手機可連線。
- Cloud Run 與 Cloudflare Containers 的實際部署。
- 以真實的內政部 SP 帳號完成行動自然人憑證簽署。
- 自己的 zkPassport 證明器與臨時證明器搭配實體護照與手機，含 FaceMatch 的真實金鑰證明憑證鏈。
- 兩台手機透過 OpenID4VP 轉送信箱完成線上查驗。
- 臨時證明器在真正的 Windows、macOS 與 Linux ARM64 上執行。
- 相容版程式庫在使用者 1 GB VPS（以 `diw-server native-check` 確認）與不支援 x86-64-v2 的 CPU 上執行。

## 5. 外部條件

- 行動自然人憑證簽署需要內政部核發的 SP 帳號；開發者沒有。
- Cloudflare Containers 需要 Workers Paid 方案。
- Agent 不能推送 `.github/workflows/`，workflow 的修改要由有權限的人貼上。

## 6. 相依版本

| 項目 | 版本 |
|---|---|
| Kotlin、Ktor（CIO）、coroutines、serialization | 2.4.21、3.6.2、1.11.0、1.13.0 |
| Gradle、GraalVM 建置外掛 | 9.9.2（錢包的 AGP 9.5 需要 Gradle 9）、0.11.5 |
| Bouncy Castle | 1.87（與錢包相同） |
| JDK | 21（jlink 執行環境）；原生執行檔用 GraalVM Community 25 |
| 錢包 `core` 與原生程式庫 | submodule `wallet/` 釘選的 commit |
| CI 建置的原生功能 | 映像：`openac,tlsn,oprf,zkpdf,bb5`；臨時證明器：`zkpdf,bb5`；Linux 相容版去掉 `zkpdf,bb5`（保留 `oprf`） |
| Linux 相容版的建置工具 | Zig 0.14.1（錢包 `zkpdf-prover/tools/zig.py` 釘選雜湊）、cargo-zigbuild 0.20.1 |

版本以 `gradle/libs.versions.toml` 與 submodule 指標為準；升級由 Renovate 開 PR。
