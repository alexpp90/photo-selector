#!/usr/bin/env python3
"""Verification records: which machine ran which gates of scripts/run_tests.sh, on which tree.

Every pull request states where it was developed and how far it was tested locally
(CONTRIBUTING.md, "Verification record"). The native products make this matter: the macOS
gates only run on a Mac, the Linux Desktop gates only with GTK 4, the Android instrumented
tests only with a device. A record makes visible which of them actually ran, on what, for
exactly which state of the code - including runs by agents in sandboxes with limited tooling.

Subcommands:
  write   Called by scripts/run_tests.sh at the end of every run. Stores a JSON record in
          <git-common-dir>/pst-verification/ - inside .git, never tracked, shared by all
          worktrees of the checkout.
  render  Print this machine's PR block for the current working tree.
  pr      Add or replace this machine's block in the current branch's PR description (gh).
          Run it on every machine that tested the PR; each machine owns one block.
  check   CI (pr-verification.yml): compare the records in the PR description with the PR
          head and the products it touches. Advisory - it warns, it does not fail.

Privacy: the repository is public. A record holds the hardware model, OS, toolchain versions
and attached-device models only. Hostnames, user names, serial numbers and local paths are
never collected, and are scrubbed from gate names and skip reasons before anything is stored.

Python 3.9+ (macOS Command Line Tools ship 3.9), standard library only.
"""

from __future__ import annotations

import argparse
import datetime as dt
import getpass
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

SCHEMA = 1
RECORD_DIR = "pst-verification"

BEGIN = "<!-- pst-verification:records:begin -->"
END = "<!-- pst-verification:records:end -->"
PLACEHOLDER_RE = re.compile(r"^.*<!-- pst-verification:placeholder -->.*(?:\n|$)", re.M)
DATA_RE = re.compile(r"<!-- pst-verification:data (\{.*?\}) -->")

# Gate groups, in the order the PR block lists them.
GROUPS: List[Tuple[str, str]] = [
    ("android", "Android (PhotoTok, Android Desktop)"),
    ("macos", "macOS Desktop"),
    ("linux", "Linux Desktop"),
    ("desktop", "Desktop (legacy)"),
    ("repo", "Repository hygiene"),
    ("overlay", "Maintainer framework"),
]
GROUP_LABEL = dict(GROUPS)
PRODUCT_GROUPS = ("android", "macos", "linux", "desktop")

# Which gate group a changed path obliges. products/android/core/ and products/android/saf/ affect both
# Android products.
PATH_GROUPS = [
    ("products/android/", "android"),
    ("products/macos-desktop/", "macos"),
    ("products/linux-desktop/", "linux"),
    ("products/desktop/", "desktop"),
]
WHERE_IT_RUNS = {
    "android": "needs JDK 17 + the Android SDK; instrumented tests need a device or emulator",
    "macos": "needs a Mac",
    "linux": "needs Linux with GTK 4 + Libadwaita (Debian 13)",
    "desktop": "runs anywhere with Poetry",
}

STATUS_ICON = {"PASS": "✔", "WARN": "!", "FAIL": "✘", "SKIP": "⊘"}
GROUP_ICON = {
    "passed": "✅ passed",
    "partial": "⚠️ partial",
    "failed": "❌ failed",
    "not run": "⊘ not run here",
    "not in scope": "— not in scope",
}
# Skip reasons run_tests.sh gives for gates that had nothing to do (not a risk).
OUT_OF_SCOPE = ("no changes under", "no private/ checkout")
RANK = {"passed": 3, "partial": 2, "failed": 1, "not run": 0, "not in scope": 0}


# ── small helpers ────────────────────────────────────────────────────────────


def run(cmd: List[str], cwd: Optional[Path] = None, env: Optional[Dict[str, str]] = None,
        timeout: int = 20) -> Optional[str]:
    """Run a command; return its output (stdout, else stderr) or None if it failed."""
    try:
        proc = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True,
                              timeout=timeout)
    except (OSError, subprocess.SubprocessError):
        return None
    if proc.returncode != 0:
        return None
    return (proc.stdout.strip() or proc.stderr.strip())


def run_reporting(cmd: List[str], cwd: Optional[Path] = None,
                  timeout: int = 60) -> Tuple[bool, str]:
    """Run a command; return (True, stdout) or (False, the reason it failed).

    For the calls whose failure is reported to the user (gh), where run()'s bare None would
    hide what went wrong.
    """
    try:
        proc = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as exc:
        return False, str(exc)
    if proc.returncode != 0:
        return False, (proc.stderr.strip() or proc.stdout.strip()
                       or f"exit status {proc.returncode}")
    return True, proc.stdout.strip()


def update_pr_body(root: Path, pr: Dict[str, Any], body_file: str) -> Optional[str]:
    """Write body_file into the PR description; return None on success, else why it failed.

    `gh pr edit` fails on older gh releases (2.46 in Debian 13) with "GraphQL: Projects
    (classic) is being deprecated" because it queries projectCards; the REST endpoint does not.
    """
    ok, edit_err = run_reporting(["gh", "pr", "edit", str(pr["number"]), "--body-file", body_file],
                                 cwd=root)
    if ok:
        return None
    m = re.match(r"https://[^/]+/([^/]+/[^/]+)/pull/\d+", pr.get("url") or "")
    repo = m.group(1) if m else "{owner}/{repo}"
    ok, api_err = run_reporting(["gh", "api", "--silent", "-X", "PATCH",
                                 f"repos/{repo}/pulls/{pr['number']}", "-F", f"body=@{body_file}"],
                                cwd=root)
    if ok:
        print("Note: gh pr edit failed, so the description was updated through the REST API.\n"
              f"  gh pr edit: {first_line(edit_err)}")
        return None
    return f"gh pr edit: {edit_err}\ngh api PATCH fallback: {api_err}"


def git(root: Path, *args: str, env: Optional[Dict[str, str]] = None,
        timeout: int = 60) -> Optional[str]:
    return run(["git", *args], cwd=root, env=env, timeout=timeout)


def first_line(text: Optional[str]) -> Optional[str]:
    if not text:
        return None
    return text.strip().splitlines()[0].strip() or None


def version_in(text: Optional[str], pattern: str) -> Optional[str]:
    if not text:
        return None
    m = re.search(pattern, text)
    return m.group(1) if m else None


def slugify(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-") or "unknown"


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def read_text(path: Path) -> Optional[str]:
    try:
        value = path.read_text(encoding="utf-8", errors="replace").strip()
    except OSError:
        return None
    return value or None


# ── privacy ──────────────────────────────────────────────────────────────────


def private_tokens(root: Optional[Path]) -> List[Tuple[str, str]]:
    """Strings that identify the person or the machine, each with its replacement."""
    tokens: List[Tuple[str, str]] = []
    if root is not None:
        tokens.append((str(root), "<repo>"))
    home = os.path.expanduser("~")
    if home and home != "/":
        tokens.append((home, "~"))
    names = set()
    try:
        host = socket.gethostname()
        names.update({host, host.split(".")[0]})
    except OSError:
        pass
    if platform.system() == "Darwin":
        for key in ("ComputerName", "LocalHostName", "HostName"):
            names.add(run(["scutil", "--get", key], timeout=5) or "")
    for name in names:
        if name and len(name) >= 3:
            tokens.append((name, "<host>"))
    try:
        user = getpass.getuser()
    except Exception:  # getpass raises a bare Exception subclass on some platforms
        user = ""
    if user and len(user) >= 3:
        tokens.append((user, "<user>"))
    # Longest first, so a path is replaced before the user name inside it.
    return sorted(tokens, key=lambda t: -len(t[0]))


def scrub(value: Any, tokens: List[Tuple[str, str]]) -> Any:
    if isinstance(value, str):
        for token, replacement in tokens:
            value = re.sub(re.escape(token), replacement, value, flags=re.I)
        return value
    if isinstance(value, list):
        return [scrub(v, tokens) for v in value]
    if isinstance(value, dict):
        return {k: scrub(v, tokens) for k, v in value.items()}
    return value


# ── what ran ─────────────────────────────────────────────────────────────────


def classify(name: str, ci: str) -> str:
    """Map a run_tests.sh gate to its group, by CI reference first, then by name."""
    ci = ci or ""
    for prefix, group in (("desktop.yml", "desktop"), ("android.yml", "android"),
                          ("macos.yml", "macos"), ("linux.yml", "linux"),
                          ("repo-hygiene.yml", "repo"), ("framework.yml", "overlay"),
                          ("format-corpus", "repo")):
        if ci.startswith(prefix):
            return group
    n = name.lower()
    if "linux" in n or "debian" in n:
        return "linux"
    if "macos" in n or "swift" in n:
        return "macos"
    if "android" in n or "gradlew" in n or "instrumented" in n:
        return "android"
    if "overlay gates" in n:
        return "overlay"
    if any(k in n for k in ("python gates", "flake8", "pytest", "visual")):
        return "desktop"
    return "repo"


def parse_results(text: str) -> List[Dict[str, Any]]:
    """Parse run_tests.sh's results file: STATUS|name|detail-or-ci|ci|unrunnable."""
    gates = []
    for line in text.splitlines():
        if not line.strip():
            continue
        parts = line.split("|")
        parts += [""] * (5 - len(parts))
        status, name, third, fourth, fifth = parts[:5]
        if status not in STATUS_ICON:
            continue
        if status == "SKIP":
            detail, ci, unrunnable = third, fourth, fifth != "false"
        else:
            detail, ci, unrunnable = "", fourth or third, False
        gates.append({"status": status, "name": name, "detail": detail, "ci": ci,
                      "unrunnable": unrunnable, "group": classify(name, ci)})
    return gates


def merge_gates(records: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Combine several runs of one machine on one tree (e.g. --android, then --android-device).

    Per gate name the latest result that actually ran wins; a later skip never hides an
    earlier pass or failure. "Not in scope" umbrella skips are dropped from a group that has
    real results.
    """
    merged: Dict[str, Dict[str, Any]] = {}
    for record in sorted(records, key=lambda r: r.get("recorded_at", "")):
        for gate in record.get("gates", []):
            prev = merged.get(gate["name"])
            if prev is None or gate["status"] != "SKIP" or prev["status"] == "SKIP":
                merged[gate["name"]] = gate
    gates = list(merged.values())
    ran = {g["group"] for g in gates if g["status"] != "SKIP"}
    return [g for g in gates
            if not (g["status"] == "SKIP" and not g["unrunnable"] and g["group"] in ran
                    and g["detail"].startswith(OUT_OF_SCOPE))]


def group_status(gates: List[Dict[str, Any]]) -> str:
    if not gates:
        return "not in scope"
    if any(g["status"] == "FAIL" for g in gates):
        return "failed"
    ran = [g for g in gates if g["status"] in ("PASS", "WARN")]
    skipped = [g for g in gates if g["status"] == "SKIP"]
    if ran:
        partial = any(g["unrunnable"] for g in skipped) or any("partial" in g["ci"] for g in ran)
        return "partial" if partial else "passed"
    if all(not g["unrunnable"] and g["detail"].startswith(OUT_OF_SCOPE) for g in skipped):
        return "not in scope"
    return "not run"


def statuses(gates: List[Dict[str, Any]]) -> Dict[str, str]:
    return {key: group_status([g for g in gates if g["group"] == key])
            for key, _ in GROUPS if any(g["group"] == key for g in gates)}


# ── where it ran ─────────────────────────────────────────────────────────────

_PLACEHOLDER_DMI = {"", "none", "default string", "to be filled by o.e.m.", "not specified",
                    "system product name", "system version"}


def machine_info() -> Dict[str, Any]:
    system = platform.system()
    info: Dict[str, Any] = {"os": f"{system} {platform.release()}", "os_family": system.lower(),
                            "model": None, "cpu": None, "arch": platform.machine(),
                            "virtualized": None}
    if system == "Darwin":
        version = run(["sw_vers", "-productVersion"], timeout=5)
        build = run(["sw_vers", "-buildVersion"], timeout=5)
        info["os"] = f"macOS {version}" + (f" ({build})" if build else "") if version else "macOS"
        info["os_family"] = "macos"
        # Only the model and chip lines are read; the serial number and UUIDs in the same
        # output are never kept.
        hw = run(["system_profiler", "SPHardwareDataType"], timeout=30) or ""
        model = version_in(hw, r"Model Name:\s*(.+)")
        ident = version_in(hw, r"Model Identifier:\s*(.+)")
        info["model"] = f"{model} ({ident.strip()})" if model and ident else (model or ident)
        info["cpu"] = (version_in(hw, r"Chip:\s*(.+)") or version_in(hw, r"Processor Name:\s*(.+)")
                       or run(["sysctl", "-n", "machdep.cpu.brand_string"], timeout=5))
        if run(["sysctl", "-n", "kern.hv_vmm_present"], timeout=5) == "1":
            info["virtualized"] = "virtual machine"
    elif system == "Linux":
        os_release = read_text(Path("/etc/os-release")) or ""
        info["os"] = version_in(os_release, r'PRETTY_NAME="?([^"\n]+)') or info["os"]
        info["os_family"] = version_in(os_release, r'(?m)^ID="?([^"\n]+)') or "linux"
        dmi = Path("/sys/devices/virtual/dmi/id")
        vendor = read_text(dmi / "sys_vendor") or ""
        name = read_text(dmi / "product_name") or ""
        version = read_text(dmi / "product_version") or ""
        # Lenovo keeps the marketing name ("ThinkPad T14 Gen 1") in product_version and a
        # machine-type number in product_name.
        lenovo = "thinkpad" in version.lower() or vendor.lower().startswith("lenovo")
        if version.lower() not in _PLACEHOLDER_DMI and lenovo:
            model = version
        elif name.lower() not in _PLACEHOLDER_DMI:
            model = name
        else:
            model = ""
        if model and vendor and vendor.lower() not in _PLACEHOLDER_DMI \
                and vendor.split()[0].lower() not in model.lower():
            model = f"{vendor.split()[0].title()} {model}"
        info["model"] = model or None
        info["cpu"] = version_in(read_text(Path("/proc/cpuinfo")) or "", r"model name\s*:\s*(.+)")
        virt = run(["systemd-detect-virt"], timeout=5)
        if virt and virt != "none":
            info["virtualized"] = virt
        elif "microsoft" in platform.release().lower():
            info["virtualized"] = "wsl"
    info["os_family"] = (info["os_family"] or "unknown").split()[0]
    return info


def machine_slug(machine: Dict[str, Any]) -> str:
    parts = [machine.get("model") or ("virtual machine" if machine.get("virtualized") else "unknown machine")]
    if machine.get("os_family") == "macos" and machine.get("cpu"):
        parts = [re.sub(r"\s*\(.*\)", "", parts[0]), machine["cpu"]]
    parts.append(machine.get("os_family") or "")
    if machine.get("virtualized"):
        parts.append("vm")
    return slugify(" ".join(parts))[:60]


def machine_label(machine: Dict[str, Any]) -> str:
    model = machine.get("model") or ("Virtual machine" if machine.get("virtualized") else "Unknown machine")
    cpu = machine.get("cpu")
    head = f"{model}, {cpu}" if cpu and cpu not in model else model
    bits = [head, machine.get("os") or "", machine.get("arch") or ""]
    if machine.get("virtualized"):
        bits.append(f"virtualized ({machine['virtualized']})")
    return " · ".join(b for b in bits if b)


def environment() -> Dict[str, str]:
    """Best-effort: which agent host (if any) the run happened in."""
    e = os.environ
    if e.get("PST_EXECUTOR"):
        return {"kind": e["PST_EXECUTOR"], "source": "PST_EXECUTOR"}
    term = e.get("TERM_PROGRAM", "").lower()
    if e.get("GITHUB_ACTIONS") == "true":
        kind = "GitHub Actions runner"
    elif e.get("CLAUDECODE") == "1":
        kind = "Claude Code (cloud)" if e.get("CLAUDE_CODE_REMOTE") in ("1", "true") else "Claude Code"
    elif e.get("SANDBOX_RUNTIME") and e.get("CLAUDE_TMPDIR"):
        kind = "Claude Cowork sandbox"
    elif e.get("GEMINI_CLI") == "1":
        kind = "Gemini CLI"
    elif "antigravity" in term or any(k.upper().startswith("ANTIGRAVITY") for k in e):
        kind = "Antigravity"
    elif e.get("CODESPACES") == "true":
        kind = "GitHub Codespaces"
    elif "cursor" in term or e.get("CURSOR_TRACE_ID"):
        kind = "Cursor"
    else:
        return {"kind": "no agent detected", "source": "detected"}
    return {"kind": kind, "source": "detected"}


def toolchains(root: Path) -> Dict[str, str]:
    tools: Dict[str, Optional[str]] = {"Python": platform.python_version()}
    tools["Poetry"] = version_in(run(["poetry", "--version"]), r"(\d+\.\d+(?:\.\d+)?)")
    tools["JDK"] = version_in(run(["java", "-version"]), r'version "([^"]+)"')
    wrapper = read_text(root / "products/android/gradle/wrapper/gradle-wrapper.properties")
    tools["Gradle wrapper"] = version_in(wrapper, r"gradle-([0-9][^-/]*)-(?:bin|all)\.zip")
    if shutil.which("swift"):
        tools["Swift"] = version_in(run(["swift", "--version"]), r"Swift version (\S+)")
    if platform.system() == "Darwin":
        tools["Xcode"] = version_in(run(["xcodebuild", "-version"]), r"Xcode (\S+)")
    if platform.system() == "Linux":
        probe = ("import gi; gi.require_version('Gtk', '4.0'); gi.require_version('Adw', '1');"
                 "from gi.repository import Gtk, Adw;"
                 "print(f'{Gtk.get_major_version()}.{Gtk.get_minor_version()}.{Gtk.get_micro_version()} '"
                 "f'{Adw.get_major_version()}.{Adw.get_minor_version()}.{Adw.get_micro_version()}')")
        out = first_line(run(["python3", "-c", probe], timeout=20))
        if out and " " in out:
            tools["GTK"], tools["Libadwaita"] = out.split(" ", 1)
    return {k: v for k, v in tools.items() if v}


def android_devices() -> List[Dict[str, Any]]:
    """Attached Android devices: model and OS version only - never the serial.

    When PST_TESTED_SERIALS (comma-separated) or ANDROID_SERIAL names the devices the tests
    ran on (run_tests.sh sets both), only those devices are reported; other attached devices
    took no part in the run.
    """
    listing = run(["adb", "devices"], timeout=20) if shutil.which("adb") else None
    under_test = {s.strip() for s in (os.environ.get("PST_TESTED_SERIALS", "").strip()
                                      or os.environ.get("ANDROID_SERIAL", "")).split(",") if s.strip()}
    devices = []
    for line in (listing or "").splitlines()[1:]:
        fields = line.split()
        if len(fields) < 2 or fields[1] != "device":
            continue
        serial = fields[0]
        if under_test and serial not in under_test:
            continue

        def prop(key: str) -> Optional[str]:
            return run(["adb", "-s", serial, "shell", "getprop", key], timeout=15)

        devices.append({
            "model": prop("ro.product.model") or "unknown",
            "android": prop("ro.build.version.release"),
            "api": prop("ro.build.version.sdk"),
            "emulator": serial.startswith("emulator-") or prop("ro.kernel.qemu") == "1",
        })
    return devices


# ── which code ───────────────────────────────────────────────────────────────


def record_dir(root: Path) -> Path:
    common = git(root, "rev-parse", "--git-common-dir") or ".git"
    return (root / common).resolve() / RECORD_DIR


def tree_state(root: Path) -> Dict[str, Any]:
    """The exact tested state: the tree hash of the working tree, untracked files included.

    The CI mirror runs before the commit, so HEAD alone does not identify what was tested.
    Hashing the working tree through a throwaway index (the real index and the working tree
    are not touched) gives the tree the commit will have if exactly these files are committed;
    CI compares it with the PR head's tree.

    The throwaway index starts from HEAD's tree with no cached stat data, so every file is
    hashed. Copying the real index instead is faster but wrong: its stat cache can match a file
    rewritten in the same second with the same size, and the stale blob ends up in the tree.
    """
    head = git(root, "rev-parse", "HEAD")
    state: Dict[str, Any] = {
        "head": head,
        "head_tree": git(root, "rev-parse", "HEAD^{tree}") if head else None,
        "branch": git(root, "rev-parse", "--abbrev-ref", "HEAD"),
        "tree": None,
    }
    with tempfile.TemporaryDirectory() as tmp:
        env = dict(os.environ, GIT_INDEX_FILE=os.path.join(tmp, "index"))
        seeded = not head or git(root, "read-tree", "HEAD", env=env) is not None
        if seeded and git(root, "add", "-A", "--", ".", env=env, timeout=300) is not None:
            state["tree"] = git(root, "write-tree", env=env)
    state["dirty"] = bool(state["tree"] and state["tree"] != state["head_tree"])
    return state


def load_records(root: Path) -> List[Dict[str, Any]]:
    records = []
    directory = record_dir(root)
    if not directory.is_dir():
        return records
    for path in directory.glob("*.json"):
        try:
            records.append(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            continue
    return sorted(records, key=lambda r: r.get("recorded_at", ""))


# ── rendering ────────────────────────────────────────────────────────────────


def _gate_cell(gate: Dict[str, Any]) -> str:
    text = f"{STATUS_ICON[gate['status']]} {gate['name']}"
    if gate["status"] == "SKIP" and gate["detail"]:
        text += f" ({gate['detail']})"
    return text.replace("|", "/")


def _duration(seconds: Optional[int]) -> str:
    if not seconds:
        return ""
    minutes, secs = divmod(int(seconds), 60)
    return f"{minutes} min {secs:02d} s" if minutes else f"{secs} s"


def render_block(records: List[Dict[str, Any]]) -> str:
    """The PR description block for one machine and one tested tree."""
    records = sorted(records, key=lambda r: r.get("recorded_at", ""))
    latest = records[-1]
    machine = latest["machine"]
    gates = merge_gates(records)
    groups = statuses(gates)
    state = latest.get("git", {})
    tree, head = state.get("tree") or "", state.get("head") or ""
    if tree and tree == state.get("head_tree"):
        tested = f"commit `{head[:10]}` (clean tree)"
    elif tree:
        tested = f"tree `{tree[:10]}` = commit `{head[:10]}` + uncommitted changes"
    else:
        tested = f"commit `{head[:10]}` (tree hash unavailable)"
    env = latest.get("environment", {})
    env_text = env.get("kind", "unknown")
    if env.get("source") == "detected":
        env_text += " (detected)"

    lines = [
        f"#### {machine_label(machine)}",
        "",
        f"Run from: {env_text} · tested: {tested} · {latest.get('recorded_at', '')}",
        "",
        "| Gates | Result | Details |",
        "|---|---|---|",
    ]
    for key, label in GROUPS:
        if key not in groups or (key == "overlay" and groups[key] == "not in scope"):
            continue
        group = [g for g in gates if g["group"] == key]
        if key == "overlay":  # private framework: count only, never gate names
            ran = sum(1 for g in group if g["status"] != "SKIP")
            details = f"{ran} of {len(group)} gates ran"
        else:
            details = "<br>".join(_gate_cell(g) for g in group)
            if key == "android" and not any("instrumented" in g["name"] for g in group) \
                    and groups[key] != "not in scope":
                details += "<br>⊘ instrumented tests (not requested: run on demand with `--android-device`)"
        lines.append(f"| {label} | {GROUP_ICON[groups[key]]} | {details} |")

    tools = latest.get("toolchains") or {}
    if tools:
        lines += ["", "Toolchains: " + " · ".join(f"{k} {v}" for k, v in tools.items())]
    devices = [d for r in records for d in r.get("android_devices", [])]
    if devices:
        seen = []
        for d in devices:
            text = f"{d['model']} (Android {d.get('android') or '?'}, API {d.get('api') or '?'}" \
                   + (", emulator)" if d.get("emulator") else ")")
            if text not in seen:
                seen.append(text)
        lines += ["", "Android devices attached: " + " · ".join(seen)]
    runs = []
    for r in records:
        args = " ".join(r.get("args") or []) or "(auto-detected products)"
        runs.append(f"`run_tests.sh {args}` → exit {r.get('exit_code')}"
                    + (f", {_duration(r.get('duration_s'))}" if r.get("duration_s") else ""))
    lines += ["", "Runs: " + " · ".join(runs)]

    data = {
        "v": SCHEMA, "slug": machine_slug(machine), "label": machine_label(machine),
        "tree": tree, "head": head, "env": env.get("kind"),
        "virt": machine.get("virtualized"), "at": latest.get("recorded_at"), "groups": groups,
    }
    lines += ["", f"<!-- pst-verification:data {json.dumps(data, separators=(',', ':'))} -->"]
    return "\n".join(lines)


def wrap_block(slug: str, block: str) -> str:
    return f"<!-- pst-verification:machine={slug} -->\n{block}\n<!-- /pst-verification:machine={slug} -->"


def template_section(root: Optional[Path]) -> Optional[str]:
    """The template's '## Verification' section, for descriptions made without the template."""
    text = read_text(root / ".github/pull_request_template.md") if root else None
    if not text or BEGIN not in text:
        return None
    m = re.search(r"^## Verification\b.*?(?=^## |\Z)", text, re.M | re.S)
    return m.group(0).strip() if m else None


def upsert_block(body: str, slug: str, block: str, root: Optional[Path] = None) -> str:
    """Add or replace one machine's block; everything else in the description is kept.

    A description without the record markers (e.g. `gh pr create --fill`) gets the template's
    whole Verification section appended, so the who / not-tested / manual-testing questions
    are asked there too.
    """
    body = body or ""
    if not (BEGIN in body and END in body):
        section = template_section(root) or f"## Verification\n\n{BEGIN}\n{END}"
        body = body.rstrip() + "\n\n" + section + "\n"
    wrapped = wrap_block(slug, block)
    existing = re.compile(
        r"<!-- pst-verification:machine=" + re.escape(slug) + r" -->.*?"
        r"<!-- /pst-verification:machine=" + re.escape(slug) + r" -->", re.S)
    if existing.search(body):
        return existing.sub(lambda _: wrapped, body, count=1)
    if BEGIN in body and END in body and body.index(BEGIN) < body.index(END):
        start = body.index(BEGIN) + len(BEGIN)
        end = body.index(END)
        inner = PLACEHOLDER_RE.sub("", body[start:end]).strip()
        inner = f"\n{inner}\n\n{wrapped}\n" if inner else f"\n{wrapped}\n"
        return body[:start] + inner + body[end:]
    return body.rstrip() + f"\n\n{BEGIN}\n{wrapped}\n{END}\n"


# ── CI check ─────────────────────────────────────────────────────────────────


def touched_groups(paths: Iterable[str]) -> List[str]:
    found = []
    for path in paths:
        for prefix, group in PATH_GROUPS:
            if path.startswith(prefix) and group not in found:
                found.append(group)
    return [g for g in PRODUCT_GROUPS if g in found]


def manual_rows(body: str) -> int:
    """Filled rows of the template's manual-testing table."""
    m = re.search(r"^###\s+Manual testing.*?$(.*?)(?=^##|\Z)", body, re.M | re.S)
    if not m:
        return 0
    rows = [ln for ln in m.group(1).splitlines() if ln.strip().startswith("|")]
    filled = [r for r in rows[2:] if re.sub(r"[|\s.…-]", "", r)]
    return len(filled)


def evaluate(body: str, head_tree: Optional[str], changed: Optional[List[str]],
             author_is_bot: bool) -> List[Tuple[str, str, str]]:
    """Findings (level, title, message) for a PR. Pure, so it is unit-tested directly."""
    if author_is_bot:
        return [("notice", "Bot PR", "Opened by a bot: CI only, no local verification expected.")]
    findings: List[Tuple[str, str, str]] = []
    records = []
    for raw in DATA_RE.findall(body or ""):
        try:
            records.append(json.loads(raw))
        except ValueError:
            findings.append(("warning", "Unreadable record", "A verification block's data line is not valid JSON."))
    if not records:
        findings.append(("warning", "No verification record",
                         "The PR description has no local verification record. Run ./scripts/run_tests.sh, "
                         "then python3 scripts/verification_record.py pr - or state under 'Verification' "
                         "what was tested where and what was not tested."))
        return findings

    current = [r for r in records if head_tree and r.get("tree") == head_tree]
    for r in records:
        if head_tree and r.get("tree") != head_tree:
            findings.append(("warning" if not current else "notice", "Stale record",
                             f"{r.get('label')}: tested tree {str(r.get('tree'))[:10]} differs from the PR head "
                             f"tree {head_tree[:10]} - re-run on that machine after the last push, or the "
                             "committed files differ from the tested ones."))
    if head_tree is None:
        current = records
        findings.append(("notice", "Head tree unknown", "Could not resolve the PR head tree; records not compared."))

    for r in current:
        failed = [GROUP_LABEL.get(g, g) for g, s in (r.get("groups") or {}).items() if s == "failed"]
        if failed:
            findings.append(("warning", "Failing gates", f"{r.get('label')}: {', '.join(failed)} failed locally."))

    if changed is None:
        findings.append(("notice", "Changed files unknown",
                         "Could not list the PR's files; product coverage not checked."))
        return findings

    for group in touched_groups(changed):
        best = max(((RANK[(r.get("groups") or {}).get(group, "not run")], r) for r in current),
                   key=lambda t: t[0], default=(0, None))
        label = GROUP_LABEL[group]
        if best[0] == RANK["passed"]:
            continue
        if best[0] == RANK["partial"]:
            findings.append(("notice", f"{label}: partial",
                             f"{label} changed; its gates ran only partially ({best[1].get('label')})."))
        else:
            findings.append(("warning", f"{label}: not verified",
                             f"{label} changed, but no record of the PR head ran its gates ({WHERE_IT_RUNS[group]})."))

    if current and all(r.get("virt") or "sandbox" in str(r.get("env", "")).lower()
                       or "cloud" in str(r.get("env", "")).lower() for r in current):
        findings.append(("notice", "Sandbox only",
                         "Every current record comes from a VM or agent sandbox; nothing was run on a "
                         "physical developer machine."))
    native = [g for g in touched_groups(changed) if g in ("android", "macos", "linux")]
    if native and manual_rows(body) == 0:
        findings.append(("notice", "No manual testing",
                         "Native product code changed and the 'Manual testing' table is empty - say so "
                         "explicitly if it was not tried on real hardware."))
    return findings


# ── subcommands ──────────────────────────────────────────────────────────────


def repo_root(arg: Optional[str]) -> Path:
    if arg:
        return Path(arg).resolve()
    top = run(["git", "rev-parse", "--show-toplevel"])
    if not top:
        sys.exit("verification_record.py: not inside a git checkout")
    return Path(top).resolve()


def cmd_write(args: argparse.Namespace) -> int:
    root = repo_root(args.root)
    gates = parse_results(Path(args.results).read_text(encoding="utf-8", errors="replace"))
    machine = machine_info()
    # adb only when Android gates ran: querying it starts the adb server.
    wants_devices = any(g["group"] == "android" and g["status"] != "SKIP" for g in gates) \
        or any("instrumented" in g["name"] for g in gates)
    record = {
        "schema": SCHEMA,
        "recorded_at": utc_now(),
        "duration_s": int(time.time() - args.started) if args.started else None,
        "args": args.args.split(),
        "exit_code": args.exit_code,
        "machine": machine,
        "environment": environment(),
        "toolchains": toolchains(root),
        "android_devices": android_devices() if wants_devices else [],
        "git": tree_state(root),
        "gates": gates,
    }
    # Scrub what the local system reported; our own labels and the git hashes are left alone
    # (a hostname such as "claude" must not mangle "Claude Code").
    tokens = private_tokens(root)
    for key in ("machine", "toolchains", "android_devices", "gates", "args"):
        record[key] = scrub(record[key], tokens)
    slug = machine_slug(record["machine"])
    record["machine"]["slug"] = slug
    directory = record_dir(root)
    directory.mkdir(parents=True, exist_ok=True)
    key = (record["git"].get("tree") or record["git"].get("head") or "unknown")[:12]
    stamp = record["recorded_at"].replace(":", "").replace("-", "")
    (directory / f"{key}-{slug}-{stamp}.json").write_text(json.dumps(record, indent=2) + "\n",
                                                          encoding="utf-8")
    print(f"Verification record saved for {machine_label(record['machine'])}.")
    print("Add it to the PR description: python3 scripts/verification_record.py pr")
    return 0


def records_for_here(root: Path, allow_stale: bool) -> Tuple[str, List[Dict[str, Any]], bool]:
    slug = machine_slug(scrub(machine_info(), private_tokens(root)))
    mine = [r for r in load_records(root) if r.get("machine", {}).get("slug") == slug]
    if not mine:
        sys.exit(f"No verification record for this machine ({slug}). Run ./scripts/run_tests.sh first.")
    tree = tree_state(root).get("tree")
    matching = [r for r in mine if tree and r.get("git", {}).get("tree") == tree]
    if matching:
        return slug, matching, True
    if not allow_stale:
        sys.exit("The working tree changed since the last ./scripts/run_tests.sh on this machine. "
                 "Re-run it, or pass --allow-stale to publish the latest record as it is.")
    last_tree = mine[-1].get("git", {}).get("tree")
    return slug, [r for r in mine if r.get("git", {}).get("tree") == last_tree], False


def cmd_render(args: argparse.Namespace) -> int:
    root = repo_root(args.root)
    slug, records, _ = records_for_here(root, args.allow_stale)
    print(wrap_block(slug, render_block(records)))
    return 0


def cmd_pr(args: argparse.Namespace) -> int:
    root = repo_root(args.root)
    if not shutil.which("gh"):
        sys.exit("gh not found. Paste the output of `python3 scripts/verification_record.py render` "
                 "into the PR description under 'Verification' instead.")
    slug, records, current = records_for_here(root, args.allow_stale)
    view = ["gh", "pr", "view"] + ([str(args.number)] if args.number else []) + \
        ["--json", "number,body,url,headRefOid"]
    ok, raw = run_reporting(view, cwd=root)
    if not ok or not raw:
        sys.exit(f"No PR found for this branch (gh pr view failed: {raw or 'no output'}). "
                 "Open the PR first, or pass --number.")
    pr = json.loads(raw)
    body = upsert_block(pr.get("body") or "", slug, render_block(records), root)
    if args.dry_run:
        print(body)
        return 0
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as fh:
        fh.write(body)
        body_file = fh.name
    try:
        error = update_pr_body(root, pr, body_file)
        if error:
            sys.exit(f"Could not update the PR description.\n{error}")
    finally:
        os.unlink(body_file)
    tested = records[-1].get("git", {}).get("tree")
    head_tree = git(root, "rev-parse", f"{pr.get('headRefOid')}^{{tree}}")
    print(f"Updated the verification block for {slug} in {pr.get('url')}.")
    if not current:
        print("Note: published with --allow-stale - the block shows the tree it actually tested.")
    if head_tree and tested and head_tree != tested:
        print("Note: the PR head differs from the tested tree (push pending, or not every tested "
              "file was committed). The PR check will flag it until both match.")
    return 0


def emit(level: str, title: str, message: str, in_actions: bool) -> None:
    if in_actions:
        print(f"::{level} title={title}::{message}")
    else:
        print(f"{level.upper()}: {title} - {message}")


def cmd_check(args: argparse.Namespace) -> int:
    root = repo_root(args.root)
    in_actions = os.environ.get("GITHUB_ACTIONS") == "true"
    event_path = args.event or os.environ.get("GITHUB_EVENT_PATH")
    if args.body_file:
        body = Path(args.body_file).read_text(encoding="utf-8")
        head = args.head or "HEAD"
        base = args.base
        is_bot = False
    elif event_path:
        event = json.loads(Path(event_path).read_text(encoding="utf-8"))
        pr = event.get("pull_request") or {}
        body = pr.get("body") or ""
        head = pr.get("head", {}).get("sha") or "HEAD"
        base = pr.get("base", {}).get("sha")
        is_bot = (pr.get("user") or {}).get("type") == "Bot"
    else:
        sys.exit("check needs --body-file or a GitHub pull_request event (GITHUB_EVENT_PATH).")
    head_tree = git(root, "rev-parse", f"{head}^{{tree}}")
    changed = None
    if base:
        diff = git(root, "diff", "--name-only", f"{base}...{head}")
        changed = diff.splitlines() if diff is not None else None
    findings = evaluate(body, head_tree, changed, is_bot)

    for level, title, message in findings:
        emit(level, title, message, in_actions)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        lines = ["## Verification record", ""]
        if not findings:
            lines.append("✅ The PR head was verified locally for every product it touches.")
        for level, title, message in findings:
            lines.append(f"- {'⚠️' if level == 'warning' else 'ℹ️'} **{title}** - {message}")
        lines += ["", "_Advisory: this check never fails the PR. See CONTRIBUTING.md, "
                  "\"Verification record\"._"]
        with open(summary_path, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
    if not findings:
        print("Verification record: the PR head was verified locally for every product it touches.")
    warnings = sum(1 for f in findings if f[0] == "warning")
    return 1 if args.strict and warnings else 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", help="repository root (default: git rev-parse --show-toplevel)")
    sub = parser.add_subparsers(dest="cmd", required=True)

    w = sub.add_parser("write", help="store a record (called by scripts/run_tests.sh)")
    w.add_argument("--results", required=True, help="run_tests.sh results file")
    w.add_argument("--args", default="",
                   help="the arguments run_tests.sh was called with; pass as --args=\"...\"")
    w.add_argument("--exit-code", type=int, default=0)
    w.add_argument("--started", type=int, default=0, help="start time, epoch seconds")
    w.set_defaults(func=cmd_write)

    for name, func, text in (("render", cmd_render, "print this machine's PR block"),
                             ("pr", cmd_pr, "add/replace this machine's block in the PR description")):
        p = sub.add_parser(name, help=text)
        p.add_argument("--allow-stale", action="store_true",
                       help="use the latest record even if the working tree changed since")
        if name == "pr":
            p.add_argument("--number", type=int, help="PR number (default: the current branch's PR)")
            p.add_argument("--dry-run", action="store_true", help="print the new description only")
        p.set_defaults(func=func)

    c = sub.add_parser("check", help="CI: compare the PR description's records with the PR head")
    c.add_argument("--event", help="pull_request event JSON (default: $GITHUB_EVENT_PATH)")
    c.add_argument("--body-file", help="check a description file instead of an event")
    c.add_argument("--head", help="with --body-file: the head commit (default HEAD)")
    c.add_argument("--base", help="with --body-file: the base commit, for the changed-files check")
    c.add_argument("--strict", action="store_true", help="exit 1 on warnings")
    c.set_defaults(func=cmd_check)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
