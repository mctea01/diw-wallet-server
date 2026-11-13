# 服務端現況

最後更新：2026-11-13（main 到 PR #34）。

這一頁是服務端狀態的唯一來源：功能做到哪裡、還缺什麼、哪些等實機驗收。README 只寫功能與部署步驟，[DESIGN.md](DESIGN.md) 只寫設計與理由，兩者都不記錄進度。錢包本身的狀態見錢包 repo 的 [docs/STATUS.md](https://github.com/mctea01/my-Bonds-fork-android/blob/main/docs/STATUS.md)。

**維護規則：** 會改變狀態的提交或 PR（完成或新增功能、留下未完成項目、部署方式或相依改變）要在同一個 PR 內更新本頁與「最後更新」；規則見 [AGENTS.md](../AGENTS.md)。Fork 之後的設定見 [fork-setup.md](fork-setup.md)。

## 1. 一眼看完

- 版本 `0.1.0`（[version.txt](../version.txt)）。還沒有推送 `v*` 標籤，所以 ghcr.io 上沒有正式映像，也沒有 Release；要部署得先推標籤或自行建置。
- 里程碑 S1 到 S3（中繼、zkTLS 查驗主機、發行檔與四種部署方式）的程式與自動測試都已完成；S4（zkPDF 證明器）沒有做。
- S3 之後加入的可選功能都已在 main：Bonds 年齡查驗主機、網頁查驗端、行動自然人憑證簽署、信任聯盟、本專案 OPRF、自己的 zkPassport 證明器、臨時證明器、GraalVM 原生執行檔（實驗）。
- 沒有任何一種部署方式有實機驗收紀錄，見第 4 節。
- 錢包以 git submodule 釘選在 `wallet/`，釘的是錢包 main 上的 commit（錢包 PR #127 合併後，由 PR #33 更新）。

## 2. 功能狀態

狀態用語：**可用**（已在 main、有自動測試）、**實驗**（可用但不是預設）、**未做**。

| 功能 | `features` | 狀態 | 測試 | 備註 |
|---|---|---|---|---|
| 中繼 `/zktls/relay/{room}` | `relay`（預設） | 可用 | `ServerTest` | 只轉送端對端加密的資料 |
| zkTLS 查驗主機與網頁證明回執 | `zktls` | 可用 | `ZkTlsHostTest`（原生 TLSNotary 端對端） | 需要含 `tlsn` 的原生程式庫 |
| Bonds 年齡查驗主機 `/api/zkp/*` | `bonds-age` | 可用 | `BondsAgeTest` | 預設只轉送；主機驗證需載入 432 MB 驗證金鑰，不適合 1 GB 主機 |
| 網頁查驗端 `/verify` | `verifier` | 可用 | `VerifierTest` | 以官方查驗端的方言請求卡片，也可查驗 Bonds 自發卡 |
| 行動自然人憑證簽署 `/twfido/sign` | `twfido` | 可用；沒有以真實 SP 帳號驗收 | `TwFidoHostTest`（本機模擬的內政部服務） | 需要內政部核發的 SP 帳號 |
| 信任聯盟（OpenID Federation 1.0） | `federation` | 可用 | `FederationTest` | 可當下級，也可當上級 |
| 本專案 OPRF `/oprf/v1/*` | `oprf` | 可用 | `OprfHostTest` | 需要含 `oprf` 的原生程式庫 |
| 自己的 zkPassport 證明器 `/zkpassport/v1/jobs` | `zkpassport` | 可用 | `ZkPassportHostTest`、`ZkPassportClientInteropTest` | 需要含 `zkpdf` 的原生程式庫；1 GB 主機可能不夠 |
| 臨時證明器 `diw-server prover` | 指令 | 可用 | `ZkPassportPairingTest` | 內網限定、一次性 QR 配對 |
| 健康檢查與版本資訊 `/healthz`、`/info` | 固定 | 可用 | `ServerTest`、`ConfigTest` | |
| GraalVM 原生執行檔與 `-native` 映像 | | 實驗 | zkTLS 端對端測試可改對原生執行檔執行 | JVM 版仍是預設 |
| zkPDF 證明器（S4） | | 未做 | | 錢包的「送到自架主機產生」目前用錢包 repo 的 `tools/zk-prover-server` |

## 3. 部署與發佈

| 方式 | 檔案 | 狀態 |
|---|---|---|
| 容器（compose 加 Caddy） | `compose.yaml`、`Dockerfile`、`deploy/caddy` | 檔案與文件齊備；映像只有 amd64 |
| 不用容器（systemd） | `deploy/systemd`、Release 的 tar（附 jlink 執行環境，x86_64 Linux） | 檔案與文件齊備 |
| Google Cloud Run | `deploy/cloudrun/service.yaml` | 檔案與文件齊備 |
| Cloudflare Containers | `deploy/cloudflare` | 檔案與文件齊備 |
| 臨時證明器下載（jar 與各系統的原生版） | `.github/workflows/prover.yml` | workflow 已在 main；推標籤時附加到 Release，也可手動執行取得 Artifact |

所有方式讀同一份 `server.conf`（`deploy/server.conf.example` 由程式產生，測試會比對）。CI 在每次推送與 PR 執行測試，推送 `v*` 標籤時才發佈映像與 Release。

## 4. 未完成與待驗收

未完成：

- S4 zkPDF 證明器。
- 待合併的相依更新：GraalVM 建置外掛（PR #29）、兩個 GitHub Action 的 digest（PR #31、#32）。
- 第一個 `v*` 標籤與 Release。

待實機驗收（由部署的人執行，沒有紀錄）：

- 在 `MemoryMax=900M` 的 cgroup 中以容器為主機跑完整的 zkTLS 查驗，記錄記憶體峰值。
- 乾淨 VPS 依 README 部署：Caddy 取得憑證、手機可連線。
- Cloud Run 與 Cloudflare Containers 的實際部署。
- 以真實的內政部 SP 帳號完成行動自然人憑證簽署。
- 自己的 zkPassport 證明器與臨時證明器搭配實體護照與手機。
- 臨時證明器在真正的 Windows、macOS 與 Linux ARM64 上執行。

## 5. 外部條件

- 行動自然人憑證簽署需要內政部核發的 SP 帳號；開發者沒有。
- Cloudflare Containers 需要 Workers Paid 方案。
- Agent 不能推送 `.github/workflows/`，workflow 的修改要由有權限的人貼上。

## 6. 相依版本

| 項目 | 版本 |
|---|---|
| Kotlin、Ktor（CIO）、coroutines、serialization | 2.4.21、3.6.1、1.11.0、1.12.0 |
| JDK | 21（jlink 執行環境）；原生執行檔用 GraalVM Community 25 |
| 錢包 `core` 與原生程式庫 | submodule `wallet/` 釘選的 commit |
| CI 建置的原生功能 | 映像：`openac,tlsn,oprf,zkpdf,bb5`；臨時證明器：`zkpdf,bb5` |

版本以 `gradle/libs.versions.toml` 與 submodule 指標為準；升級由 Renovate 開 PR。
