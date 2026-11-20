#!/usr/bin/env python3
"""Writes the body of a stable server release (v* tag) in Markdown.

The download guide comes once, at the top: GitHub lists the files themselves below the body, so a reader sees which
file to take, then the changes, then the file list. The ci workflow creates the release with the tar; its native job
and the prover workflow attach the other files later, so the guide names them by their fixed names and says that a
missing one means its build failed. The changes are the commit subjects since the previous v* tag, with dependency
updates folded away.

  python3 -I tools/release-notes.py --version 0.5.3 --tag v0.5.3 > notes.md
"""
import argparse
import os
import subprocess
import sys

MAX_CHANGES = 100
BOTS = ("renovate[bot]", "dependabot[bot]", "github-actions[bot]")


def git(*args):
    run = subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8")
    return run.stdout.strip() if run.returncode == 0 else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "mctea01/diw-wallet-server"))
    a = ap.parse_args()
    v, repo = a.version, a.repo
    url = f"https://github.com/{repo}/releases/download/{a.tag}/"
    image = f"ghcr.io/{repo.lower()}"

    def link(name):
        return f"[{name}]({url}{name})"

    out = [
        f"**diw-server {v}** 穩定版（commit `{git('rev-parse', '--short', 'HEAD')}`）。部署步驟見 "
        f"[README](https://github.com/{repo}#部署)，所有方式讀同一份 `server.conf`。",
        "",
        "## 下載哪個檔案",
        "",
        "| 用途 | 下載 | 說明 |",
        "|---|---|---|",
        f"| 容器部署（建議） | `{image}:{v}` | x86_64 映像，`compose.yaml` 加 Caddy；Cloud Run 與 Cloudflare Containers 也用這個映像 |",
        f"| 不用容器（systemd） | {link(f'diw-server-{v}.tar')} | x86_64 Linux，glibc 2.28 以上，附 Java 執行環境 |",
        f"| 臨時證明器，任何系統 | {link(f'diw-server-{v}-all.jar')} | 需要 Java 21，`java -jar 檔名 prover` |",
        f"| 臨時證明器，Linux x86_64 | {link(f'diw-server-{v}-linux-x86_64.tar.gz')} | 原生執行檔，不需要 Java |",
        f"| 臨時證明器，Linux ARM64 | {link(f'diw-server-{v}-linux-aarch64.tar.gz')} | 同上 |",
        f"| 臨時證明器，Windows | {link(f'diw-server-{v}-windows-x86_64.zip')} | 同上 |",
        f"| 臨時證明器，macOS（Apple 晶片） | {link(f'diw-server-{v}-macos-aarch64.tar.gz')} | 未經公證，第一次執行前 `xattr -d com.apple.quarantine diw-server` |",
        "",
        "<details><summary>實驗版</summary>",
        "",
        "| 檔案 | 說明 |",
        "|---|---|",
        f"| {link(f'diw-server-{v}-native')} | GraalVM 原生執行檔，x86_64 Linux，glibc 2.34 以上 |",
        f"| `{image}:{v}-native` | 原生執行檔的映像 |",
        "",
        "</details>",
        "",
        "臨時證明器與實驗版由另外的建置在幾分鐘到一小時後附加；建置失敗時該檔案不會出現。"
        "每個檔案的 SHA-256 列在本頁最下方的檔案清單。",
        "",
    ]

    previous = git("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", "HEAD^")
    rev_range = f"{previous}..HEAD" if previous else "HEAD"
    features, deps = [], []
    for entry in git("log", "--no-merges", "--format=%an%x1f%s", rev_range).splitlines():
        author, _, subject = entry.partition("\x1f")
        (deps if author in BOTS else features).append(f"- {subject}")
    out += [f"## {previous} 以來的變更" if previous else "## 變更", ""]
    out += features[:MAX_CHANGES] or ["- 沒有功能變更"]
    if len(features) > MAX_CHANGES:
        out.append(f"- 只列出最近 {MAX_CHANGES} 項")
    if deps:
        out += ["", f"<details><summary>相依套件更新（{len(deps)} 項）</summary>", "", *deps, "", "</details>"]
    if previous:
        out += ["", f"完整差異：[{previous}...{a.tag}](https://github.com/{repo}/compare/{previous}...{a.tag})"]
    out.append("")
    sys.stdout.write("\n".join(out))


if __name__ == "__main__":
    main()
