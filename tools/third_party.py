#!/usr/bin/env python3
"""Third-party notices from the resolved dependency tree.

Writes a Markdown file (repository) and optionally a plain-text copy (shipped
in the APK as assets/licenses.txt). Rust crates come from `cargo metadata`
for one target; Kotlin/AndroidX libraries are a fixed list with versions
read from gradle/libs.versions.toml.

    tools/third_party.py --manifest rust/lichtkasten-mobile/Cargo.toml \
        --target aarch64-linux-android --android \
        --md THIRD_PARTY.md --txt android/app/src/main/assets/licenses.txt

    tools/third_party.py --manifest ../lichtkasten/Cargo.toml \
        --target x86_64-unknown-linux-gnu --md ../lichtkasten/THIRD_PARTY.md
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

# When a crate offers a choice, the first match is the one we accept.
PREFERENCE = [
    "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "Zlib", "0BSD",
    "Unlicense", "ISC", "CC0-1.0", "MPL-2.0", "Unicode-3.0", "Unicode-DFS-2016",
    "LGPL-2.1", "LGPL-2.1-or-later",
]

# Which file names carry which license text (checked in crate source dirs).
TEXT_FILES = {
    "MIT": ["LICENSE-MIT", "LICENSE-MIT.md", "LICENSE-MIT.txt"],
    "Apache-2.0": ["LICENSE-APACHE", "LICENSE-APACHE.md", "LICENSE-APACHE.txt"],
    "MPL-2.0": ["LICENSE", "LICENSE.md"],
    "Zlib": ["LICENSE-ZLIB", "LICENSE-ZLIB.md", "LICENSE"],
    "BSD-2-Clause": ["LICENSE-BSD", "LICENSE"],
    "BSD-3-Clause": ["LICENSE-BSD", "LICENSE-BSD-3-Clause", "LICENSE"],
    "0BSD": ["LICENSE-0BSD", "LICENSE"],
    "Unlicense": ["UNLICENSE", "LICENSE-UNLICENSE"],
    "ISC": ["LICENSE-ISC", "LICENSE"],
    "Unicode-3.0": ["LICENSE-UNICODE", "LICENSE"],
    "LGPL-2.1": ["LICENSE", "COPYING", "COPYING.LESSER"],
}

# Shipped Kotlin/AndroidX libraries (all Apache-2.0). (display name, versions key)
ANDROID_LIBS = [
    ("Kotlin standard library", "kotlin", "https://kotlinlang.org"),
    ("kotlinx.coroutines", "kotlinxCoroutines", "https://github.com/Kotlin/kotlinx.coroutines"),
    ("AndroidX Core", "coreKtx", "https://developer.android.com/jetpack/androidx"),
    ("AndroidX Activity Compose", "activityCompose", "https://developer.android.com/jetpack/androidx"),
    ("AndroidX Lifecycle", "lifecycle", "https://developer.android.com/jetpack/androidx"),
    ("Jetpack Compose (UI, Foundation, Material 3, Material Icons)", "composeBom", "https://developer.android.com/jetpack/compose"),
    ("AndroidX Media3 (ExoPlayer, UI)", "media3", "https://developer.android.com/media/media3"),
    ("Coil 3 (compose, video)", "coil", "https://coil-kt.github.io/coil/"),
    ("Java Native Access (JNA)", "jna", "https://github.com/java-native-access/jna"),
]

APACHE_NOTE = (
    "JNA is dual-licensed (LGPL-2.1-or-later or Apache-2.0); it is used under "
    "the Apache License 2.0."
)


def parse_expr(expr):
    """SPDX-ish expression -> list of alternatives, each a list of ids (ANDed)."""
    expr = (expr or "").replace("/", " OR ").replace("(", " ").replace(")", " ")
    alts = []
    for alt in re.split(r"\s+OR\s+", expr):
        ids = [t.strip() for t in re.split(r"\s+AND\s+", alt) if t.strip()]
        ids = [re.sub(r"\s+WITH\s+.*$", "", i) for i in ids]
        if ids:
            alts.append(ids)
    return alts


def choose(expr):
    alts = parse_expr(expr)
    if not alts:
        return []
    def rank(ids):
        return min((PREFERENCE.index(i) if i in PREFERENCE else len(PREFERENCE)) for i in ids)
    return sorted(alts, key=rank)[0]


def copyright_lines(path):
    out = []
    for name in ("LICENSE-MIT", "LICENSE", "LICENSE.md", "LICENSE.txt", "LICENSE-MIT.md", "COPYRIGHT", "LICENSE-APACHE"):
        f = path / name
        if f.is_file():
            try:
                text = f.read_text(errors="replace")
            except OSError:
                continue
            for line in text.splitlines()[:40]:
                s = line.strip()
                # "Copyright (c) 2015 Name" / "Copyright 2020 Name" / "© 2020 Name";
                # not the sentence fragments of the Apache text.
                if re.match(r"^(Copyright\s+(\(c\)|©|\d{4})|©\s*\d{4})", s) and len(s) < 160 and "[" not in s:
                    out.append(s)
            if out:
                break
    seen = set()
    return [c for c in out if not (c in seen or seen.add(c))]


def rust_packages(manifest, target):
    meta = json.loads(subprocess.check_output([
        "cargo", "metadata", "--format-version", "1", "--manifest-path", manifest,
        "--filter-platform", target,
    ]))
    by_id = {p["id"]: p for p in meta["packages"]}
    nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
    roots = meta["workspace_members"]
    seen = set()
    stack = list(roots)
    while stack:
        i = stack.pop()
        if i in seen:
            continue
        seen.add(i)
        for d in nodes[i]["deps"]:
            if any(k["kind"] in (None, "normal") for k in d["dep_kinds"]):
                stack.append(d["pkg"])
    pkgs = [by_id[i] for i in seen if i not in roots]
    return sorted(pkgs, key=lambda p: (p["name"], p["version"]))


def license_text(lic, pkgs):
    for p in pkgs:
        if lic not in parse_expr(p["license"])[0] and not any(lic in a for a in parse_expr(p["license"])):
            continue
        d = Path(p["manifest_path"]).parent
        for name in TEXT_FILES.get(lic, ["LICENSE"]):
            f = d / name
            if f.is_file():
                text = f.read_text(errors="replace").strip()
                # Plain "LICENSE" files are only trusted when the crate has one license.
                if name.startswith("LICENSE") and "-" not in name and len(parse_expr(p["license"])) != 1:
                    continue
                return text
    # Crates published without their license file (uniffi): canonical copy.
    fallback = Path(__file__).parent / "licenses" / f"{lic}.txt"
    if fallback.is_file():
        return fallback.read_text().strip()
    return None


def versions_toml(path):
    text = path.read_text()
    m = re.search(r"\[versions\](.*?)(\n\[|\Z)", text, re.S)
    out = {}
    for line in (m.group(1) if m else "").splitlines():
        kv = re.match(r"\s*([\w-]+)\s*=\s*\"([^\"]+)\"", line)
        if kv:
            out[kv.group(1)] = kv.group(2)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--target", required=True)
    ap.add_argument("--android", action="store_true", help="append the Kotlin/AndroidX list")
    ap.add_argument("--md", required=True)
    ap.add_argument("--txt")
    args = ap.parse_args()

    pkgs = rust_packages(args.manifest, args.target)
    chosen = {}
    used = set()
    for p in pkgs:
        ids = choose(p["license"])
        chosen[p["id"]] = ids
        used.update(ids)
    if args.android:
        used.add("Apache-2.0")

    md = []
    txt = []

    def both(line=""):
        md.append(line)
        txt.append(line)

    both("# Third-party software")
    both()
    both("This file lists the software compiled into the application and the")
    both("license each is used under. Full license texts follow the list.")
    both()
    both(f"## Rust crates ({args.target})")
    both()
    for p in pkgs:
        ids = chosen[p["id"]]
        lic = " AND ".join(ids)
        holders = copyright_lines(Path(p["manifest_path"]).parent)
        repo = p.get("repository") or ""
        md.append(f"- **{p['name']}** {p['version']} — {lic}" + (f" — <{repo}>" if repo else ""))
        txt.append(f"{p['name']} {p['version']} — {lic}" + (f" — {repo}" if repo else ""))
        for h in holders[:3]:
            md.append(f"  {h}")
            txt.append(f"    {h}")
    both()

    if args.android:
        versions = versions_toml(Path(args.manifest).parent.parent.parent / "android" / "gradle" / "libs.versions.toml")
        both("## Kotlin and Android libraries")
        both()
        both("All under the Apache License 2.0.")
        both()
        for name, key, url in ANDROID_LIBS:
            v = versions.get(key, "")
            md.append(f"- **{name}** {v} — Apache-2.0 — <{url}>")
            txt.append(f"{name} {v} — Apache-2.0 — {url}")
        both()
        both(APACHE_NOTE)
        both()

    both("## License texts")
    both()
    for lic in [l for l in PREFERENCE if l in used] + sorted(used - set(PREFERENCE)):
        text = license_text(lic, pkgs)
        if text is None:
            print(f"warning: no text found for {lic}", file=sys.stderr)
            continue
        both(f"### {lic}")
        both()
        if lic == "MIT":
            both("Applies to each crate listed above as MIT, with that crate's copyright holder(s).")
            both()
        md.append("```")
        md.append(text)
        md.append("```")
        txt.append(text)
        both()

    Path(args.md).write_text("\n".join(md).rstrip() + "\n")
    if args.txt:
        Path(args.txt).parent.mkdir(parents=True, exist_ok=True)
        Path(args.txt).write_text("\n".join(txt).rstrip() + "\n")
    print(f"{len(pkgs)} crates, licenses: {', '.join(sorted(used))}")


if __name__ == "__main__":
    main()
