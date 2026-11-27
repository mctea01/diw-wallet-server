# 新 Fork 的配置指南

性質：現行。對象是把這個 repo fork 出去、要自己建置或發佈映像的人。只想部署上游發佈的映像時不需要 fork，照 [README](../README.md) 的「部署」即可。錢包那一邊的對應文件是錢包 repo 的 [docs/guides/fork-setup.md](https://github.com/mctea01/my-Bonds-fork-android/blob/main/docs/guides/fork-setup.md)。

## 1. 讓 CI 跑起來

這個 repo 的 workflow 不需要任何自訂的 secret，只用 GitHub 內建的 `GITHUB_TOKEN`；只有穩定版發佈要開新版本線時需要 `RELEASE_TOKEN`（見第 3 節）。

1. **啟用 Actions。** Fork 的 Actions 預設關閉，到 Actions 頁按啟用；排程的 `codeql.yml` 還要到該 workflow 的頁面按一次 Enable。
2. **Settings > General > Pull Requests。** 只留 Squash merge。
3. **Settings > Code security。** Code scanning 不要開「Default setup」，本專案用自己的 `codeql.yml`。
4. **Renovate（可選）。** 安裝 [Renovate GitHub App](https://github.com/apps/renovate) 並選這個 repo，它會讀根目錄的 `renovate.json`。
5. **取得 submodule。** `git submodule update --init`；CI 的 checkout 已設 `submodules: true`。

每次推送與 PR 會跑測試並建出發行檔（Artifact）。映像與 Release 只在推送 `v*` 標籤時發佈。

## 2. 要改成自己的地方

| 項目 | 位置 | 說明 |
|---|---|---|
| 映像名稱 | 不必改 workflow | CI 推到 `ghcr.io/<你的帳號>/<repo 名稱>`（取自 `github.repository`） |
| 部署檔引用的映像 | `compose.yaml` 的 `image`、`deploy/cloudflare/Dockerfile` 的 `FROM`、`deploy/cloudrun/service.yaml` 的 `IMAGE` | 改成自己的映像；不改就會拉上游的映像 |
| Renovate 對映像的規則 | `renovate.json` 中的 `ghcr.io/mctea01/diw-wallet-server` | 改成自己的映像名稱 |
| 錢包 submodule | `.gitmodules` 的 `url` | 只用上游的錢包時不必改。錢包也 fork 了，就指到自己的錢包 fork，再 `git submodule sync && git submodule update --init --remote`，提交 submodule 指標 |
| README 的網址與指令 | `README.md` 中的 `ghcr.io/mctea01/...` 與 clone 網址 | 對外提供部署說明時一併改 |
| 版本 | `version.properties` | 只記主版號與次版號，修訂號是之後的 commit 數；Fork 的 commit 數與上游不同，要區分時改版本線 |

第一次推送映像後，GitHub 的套件預設是私有的。要讓主機不登入就能拉映像，到帳號的 Packages 頁把該套件的可見度改成 Public。

## 3. 發佈

版本與錢包同一套規則：`version.properties` 只記 `version=主版號.次版號`（目前 `0.5`），建置時補上修訂號，即最後一次修改 `version=` 那一行之後的 commit 數；PR 建置加 `-pr<編號>`，本機加 `-dev`。CI 以 `tools/app-version.sh` 算出版本，在 `v*` 標籤上要求標籤與算出的版本相同。沒有 git 歷史的原始碼可用 `-Pdiw.version=<版本>` 指定。

在 Actions 頁選 `Stable release`，在 main 上按 Run workflow：

| 輸入 | 結果 |
|---|---|
| `bump: keep`（預設） | 發佈目前的版本線，例如 `v0.5.12`；只推標籤 |
| `bump: minor` | `version.properties` 改成下一個次版號並發佈 `v0.6.0` |
| `bump: major` | 改成下一個主版號並發佈 `v1.0.0` |
| `version: 主.次`（選填） | 直接指定新的版本線，優先於 `bump`，不可低於目前的版本線 |
| `dry_run` | 只顯示會發佈的版本 |

`tools/stable-release.sh` 決定版本並把標籤（與換版本線時的 commit）一次推送；workflow 自己的權杖推的標籤不會觸發其他 workflow，所以它再以 workflow_dispatch 在標籤上啟動 `ci` 與 `prover`。換版本線要直接推 commit 到 main，有分支規則時請建立 secret `RELEASE_TOKEN`（repo 管理員的 fine-grained personal access token，只選這個 repo，Contents 讀寫），或改用 PR 修改 `version.properties`、合併後以 `keep` 發佈。手動推標籤（`git tag -a v0.5.12 -m ... && git push origin v0.5.12`）也可以，但號碼必須等於該 commit 算出的版本。

Release 頁的內文由 `tools/release-notes.py` 產生：最上方一次列出各部署方式該用的檔案與映像，下面是上一個標籤以來的變更（相依套件更新摺疊在一起）。

- `ci` workflow 發佈 JVM 映像、`-native` 映像、Release 的 tar 與原生執行檔。
- `prover` workflow 把臨時證明器的 jar 與各系統的原生版附加到同一個 Release；也可以在 Actions 頁手動執行，只產生 Artifact。
- Agent 不能推送 `.github/workflows/`，workflow 的修改要由有權限的人貼上。

### Windows 程式碼簽章

CI 產生的 Windows 檔案沒有 Authenticode 簽章。程式碼簽章憑證只存在 YubiKey（或其他智慧卡）時，等 `prover` workflow 把檔案附加到 Release 之後，在自己的 Windows 電腦上執行（先 `chcp 65001`）：

```powershell
.\tools\windows-sign.ps1 -Tag v0.5.3
```

- `diw-server-<版本>-windows-x86_64.zip`：簽署 `diw-server.exe` 與同一資料夾（含子資料夾）內所有尚未簽章的 .exe、.dll，確認沒有遺漏後以相同的資料夾結構重新打包。零知識程式庫是編進執行檔的資源，事後無法個別簽章；執行檔只在它的 SHA-256 與執行檔內釘選的值相同時才解出載入。
- `diw-server-<版本>-all.jar`：簽署其中的 `zk/native/windows-x86_64/` 程式庫並改寫對應的 `.sha256`（`ZkNative` 解出時核對它）。
- 兩個檔案以 `--clobber` 取代同名檔案，GitHub 的檔案清單隨即顯示新的 SHA-256；Release 內文的下載指引之後加一行說明（`-NoNotes` 不加，`-NoUpload` 只在本機產生）。
- 需要 Windows SDK 的 `signtool`、YubiKey Smart Card Minidriver（簽署時 Windows 詢問 PIV PIN）與已登入的 GitHub CLI；有多張程式碼簽章憑證時以 `-Thumbprint` 指定。
- 不用 signtool 時以 `-SignCommand` 改用自己的簽章命令，例如 `-SignCommand C:\tools\jsign-yubikey.cmd,"{file}"`：第一個元素是程式，其餘是參數，`{file}` 換成要簽的檔案（沒有時接在最後）；命令就地簽署並以 0 結束，時戳由命令自己處理，腳本之後確認檔案帶有 Authenticode 簽章。
- 容器映像與 Linux、macOS 的檔案不在範圍內。

## 4. 每台服務端自己的金鑰與設定

這些不屬於 repo，每個部署各自產生，放在 `server.conf` 或平台的 secret，不要提交：

- 簽章金鑰（`zktls`）、查驗端金鑰（`verifier`）、聯盟金鑰（`federation`）、OPRF 金鑰（`oprf`）。
- 存取權杖的 SHA-256（`DIW_ZKTLS_TOKEN_SHA256`、`DIW_ZKPASSPORT_TOKEN_SHA256` 等）。
- 行動自然人憑證的 SP 帳號（`twfido`）：向內政部申請自己的，不可使用他人的。

產生方式見 README 的「金鑰與權杖」。`.gitignore` 已排除根目錄的 `server.conf`、`*.pem` 與 `.env`。

服務端查驗用的 zkTLS 版型包來自錢包專案的 Release，並以錢包 `core` 內建的公鑰驗證。錢包 fork 換了版型金鑰與來源時（錢包指南 3.3），服務端跟著 submodule 一起改變，不必另外設定；要用本機檔案時設 `DIW_ZKTLS_TEMPLATES_FILE`，簽章檢查不變。

## 5. 與上游同步

```sh
git remote add upstream https://github.com/mctea01/diw-wallet-server
git fetch upstream
git merge upstream/main
```

- 合併後確認 submodule 指標仍指向你要的錢包 commit（上游的指標指向上游錢包的 commit；錢包 fork 沒有那個 commit 時 checkout 會失敗）。
- 協定程式都在錢包的 `core`，不要複製到這個 repo。要改協定就改錢包，再更新 submodule 指標。
- 開發規則在 [AGENTS.md](../AGENTS.md)，狀態在 [STATUS.md](STATUS.md)；fork 之後把 STATUS.md 的「最後更新」改成自己的基準點，之後照同樣的規則維護。

## 6. 授權與標示

保留 `LICENSE` 與 `NOTICE`（Apache-2.0 第 4 條），修改過的檔案標明已修改；可以在 `NOTICE` 開頭加上自己的著作權行。新增、移除或更換相依時，同一個 PR 更新 `NOTICE`。
