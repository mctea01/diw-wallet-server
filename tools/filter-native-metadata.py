#!/usr/bin/env python3
"""Drops test-only entries from the tracing agent's output (run after ./gradlew -Pagent test metadataCopy)."""
import json
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/META-INF/native-image/diw.server/diw-server"
TEST = re.compile(
    r"(org\.junit|org\.opentest4j|io\.ktor\.server\.testing|io\.ktor\.test|kotlinx\.coroutines\.test|kotlin\.test|"
    r"diw\.server\.[A-Za-z]+Test|diw\.testservers|org\.gradle|worker\.org\.gradle|junit-platform|TestEngine|"
    r"LauncherDiscoveryListener|LauncherSessionListener|PostDiscoveryFilter|TestExecutionListener|AsserterContributor)"
)


def keep(entry) -> bool:
    text = json.dumps(entry)
    return not TEST.search(text)


def main() -> int:
    for name in ("reflect-config.json", "jni-config.json", "serialization-config.json", "proxy-config.json"):
        path = ROOT / name
        if not path.exists():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(data, list):
            data = [e for e in data if keep(e)]
        elif isinstance(data, dict):
            for key, value in data.items():
                if isinstance(value, list):
                    data[key] = [e for e in value if keep(e)]
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    resources = ROOT / "resource-config.json"
    if resources.exists():
        data = json.loads(resources.read_text(encoding="utf-8"))
        includes = data.get("resources", {}).get("includes", [])
        data["resources"]["includes"] = [e for e in includes if keep(e)]
        resources.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    # Classes the agent saw defined at runtime by tests; the server defines none. The empty file stays because
    # native-image-configure expects it when merging the next agent run.
    shutil.rmtree(ROOT / "agent-extracted-predefined-classes", ignore_errors=True)
    (ROOT / "predefined-classes-config.json").write_text(
        '[\n  {\n    "type": "agent-extracted",\n    "classes": []\n  }\n]\n', encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
