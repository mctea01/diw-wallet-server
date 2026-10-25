# Agent notes

- Production server for DIW MY WALLET; design and milestones in docs/DESIGN.md. Keep it independent: protocol code comes from the wallet's `core` (submodule `wallet/`, composite build), never copied or reimplemented here.
- Endpoints must stay wire-compatible with what the wallet app calls (see docs/DESIGN.md section 3).
- Build: JDK 21 and Android SDK (`ANDROID_HOME`); `DIW_ZK_NATIVE=skip` when no native proof backend is needed (the zktls host needs `DIW_ZK_NATIVE=require DIW_ZK_FEATURES=tlsn`). `./gradlew test installDist distTar`; on linux-x86_64 the distribution always carries a jlink runtime in `runtime/` (task `runtimeImage`), which the image uses; other hosts skip it.
- The start script prefers `APP_HOME/runtime` when JAVA_HOME is unset; the image and the systemd unit use AppCDS via `JDK_JAVA_OPTIONS`. Keep `runtimeModules` in build.gradle.kts in step with `jdeps --print-module-deps` when dependencies change.
- Deploy files: deploy/caddy, deploy/systemd, deploy/cloudrun, deploy/cloudflare (Worker routing to one container instance; `npx tsc --noEmit` there checks it). Platforms without a persistent disk take keys from secrets (`signing_key_pem`, `verifier_key_pem`) with `signing_generate=false`.
- Code, comments and commit messages in English, no emoji; user-facing docs in Traditional Chinese.
- `twfido` signs with this server's mobile citizen certificate SP account through the wallet's `TwFidoClient`; ID numbers, hints and signed bytes stay in memory and are never logged or echoed back.
- Sized for a 1 GB host: keep memory bounded, never log request contents, disclosed values, tokens or keys.
- Agents cannot push `.github/workflows/`; propose workflow changes as a full file for the user to paste into .github/workflows/docker-image.yml.
- Tests use `runBlocking<Unit>` / `testApplication`; a test function must return Unit or JUnit skips it.
