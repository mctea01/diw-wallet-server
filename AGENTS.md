# Agent notes

- Production server for DIW MY WALLET; design and milestones in docs/DESIGN.md. Keep it independent: protocol code comes from the wallet's `core` (submodule `wallet/`, composite build), never copied or reimplemented here.
- Endpoints must stay wire-compatible with what the wallet app calls (see docs/DESIGN.md section 3).
- Build: JDK 21 and Android SDK (`ANDROID_HOME`); `DIW_ZK_NATIVE=skip` when no native proof backend is needed. `./gradlew test installDist`.
- Code, comments and commit messages in English, no emoji; user-facing docs in Traditional Chinese.
- Sized for a 1 GB host: keep memory bounded, never log request contents, disclosed values, tokens or keys.
- Agents cannot push `.github/workflows/`; keep the workflow draft in docs/ci/ci.yml.
- Tests use `runBlocking<Unit>` / `testApplication`; a test function must return Unit or JUnit skips it.
