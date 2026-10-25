"""Command-line interface dispatcher for scripts/lab/lab.py."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import _compat

_compat.install_fallbacks_if_needed()

import yaml  # noqa: E402


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="lab.py",
        description="Photo Selector Toolbox E2E Device Lab CLI",
    )
    sub = parser.add_subparsers(dest="subcommand")

    # preflight (C-00, C-32)
    p_pre = sub.add_parser("preflight", help="Check host and device toolchains for lab execution")
    p_pre.add_argument("--product", default=None, choices=["android-desktop", "phototok", "macos", "linux"])

    # fixtures (C-02)
    p_fix = sub.add_parser("fixtures", help="Generate deterministic synthetic fixture sets")
    p_fix.add_argument("--out", required=True, help="Target root directory (e.g. /tmp/pstlab)")
    p_fix.add_argument("--set", dest="only_set", default=None, help="Generate only the named fixture set")
    p_fix.add_argument("--seed", type=int, default=42, help="Deterministic RNG seed (default: 42)")

    # checkpoint (C-00, C-13)
    p_chk = sub.add_parser("checkpoint", help="Capture a live checkpoint from an Android device")
    p_chk.add_argument("--serial", required=True, help="ADB device serial")
    p_chk.add_argument("--name", required=True, help="Checkpoint name")
    p_chk.add_argument("--out", required=True, help="Output directory")

    # lint / layout-lint (C-03)
    for cmd_name in ("lint", "layout-lint"):
        p_lint = sub.add_parser(cmd_name, help="Run deterministic layout lint (R1-R8) on a tree.json")
        p_lint.add_argument("tree_json", help="Path to checkpoint .tree.json")
        p_lint.add_argument(
            "--product", default=None, help="Product name (e.g. android-desktop, phototok, macos, linux)"
        )
        p_lint.add_argument("--rules", default=None, help="Path to product rules YAML or product name")
        p_lint.add_argument("--checkpoint", default="checkpoint", help="Checkpoint name")
        p_lint.add_argument("--expect", default=None, help="Optional JSON string or YAML file with layout_expect")

    # oracle (C-04)
    p_ora = sub.add_parser("oracle", help="Run file-system oracle against a checkpoint fs.json")
    p_ora.add_argument("fs_json", help="Path to checkpoint .fs.json")
    p_ora.add_argument("--expect", required=True, help="JSON or YAML fs_expect specification")
    p_ora.add_argument("--initial", default=None, help="Optional path to 00_initial.fs.json")

    # report (C-06)
    p_rep = sub.add_parser("report", help="Render self-contained report.html for a run directory")
    p_rep.add_argument("run_dir", help="Path to lab run directory")

    # review-bundle (C-20)
    p_bun = sub.add_parser("review-bundle", help="Build AI review bundles from a lab run directory")
    p_bun.add_argument("run_dir", help="Path to lab run directory")
    p_bun.add_argument("--all", action="store_true", help="Include all checkpoints even if unchanged")
    p_bun.add_argument("--max-images", type=int, default=15, help="Max images per bundle (default: 15)")

    # review-ingest (C-21, C-22)
    p_ing = sub.add_parser("review-ingest", help="Extract or ingest AI review findings")
    p_ing.add_argument("run_dir", nargs="?", default=None, help="Path to lab run directory")
    p_ing.add_argument("--extract", default=None, help="Extract JSON array from raw LLM text file")
    p_ing.add_argument("--out", default=None, help="Output findings.json path when using --extract")

    # triage (C-22)
    p_tri = sub.add_parser("triage", help="Record an accepted finding ID in known_findings.yaml")
    p_tri.add_argument("run_dir", help="Path to lab run directory")
    p_tri.add_argument("finding_id", help="12-hex finding ID")
    p_tri.add_argument("--reason", required=True, help="Reason for accepting the finding")
    p_tri.add_argument("--until", default="2027-01-01", help="Expiry date YYYY-MM-DD")

    # run (C-60)
    p_run = sub.add_parser("run", help="Run E2E device lab journeys")
    p_run.add_argument("--all", action="store_true", help="Run all four products")
    p_run.add_argument("--product", default=None, choices=["android-desktop", "phototok", "macos", "linux"])
    p_run.add_argument("--journey", default=None, help="Filter by journey ID")
    p_run.add_argument("--config", default=None, help="Filter by config name")
    p_run.add_argument("--out", default=None, help="Explicit output run directory")
    p_run.add_argument("--installed", action="store_true", help="Use installed package binary (C-50)")
    p_run.add_argument(
        "--with-personal",
        action="store_true",
        help="Also use devices marked personal in devices.yaml (your phone, tablet, Mac)",
    )
    p_run.add_argument(
        "--device",
        action="append",
        default=None,
        help="Run Android products only on this inventory device (name or serial; repeatable; "
        "naming a personal device opts it in; 'mac' opts the Mac in)",
    )
    p_run.add_argument(
        "--no-build",
        action="store_true",
        help="Skip assembleLab: reuse the last lab build of this tree (installed where a device has "
        "another); refuses when products/android changed since that build",
    )

    # devices
    p_dev = sub.add_parser("devices", help="Show the device inventory and which devices are reachable now")
    p_dev.add_argument("--with-personal", action="store_true", help="Also probe personal devices")

    # pair
    p_pair = sub.add_parser("pair", help="Pair this host with a device showing a wireless-debugging pairing code")
    p_pair.add_argument("code", help="6-digit code from Wireless debugging -> Pair device with pairing code")
    p_pair.add_argument("--address", default=None, help="ip:port shown under the code (if mDNS does not find it)")
    p_pair.add_argument("--device", default=None, help="Inventory name, when several devices show a pairing dialog")

    # install-timer
    p_tim = sub.add_parser("install-timer", help="Install the weekly systemd user timer for this checkout")
    p_tim.add_argument("--dry-run", action="store_true", help="Print the unit files instead of installing")

    # usability panel — the human-perspective release gate (on demand, never in CI)
    p_ucap = sub.add_parser("usability-capture", help="Walk a product's usability scenarios on a device and capture every screen")
    p_ucap.add_argument("--product", default="phototok", choices=["phototok", "android-desktop", "macos", "linux"])
    p_ucap.add_argument("--serial", default=None, help="ADB serial of the test device (required for Android products on real hardware)")
    p_ucap.add_argument("--driver", default="auto", choices=["auto", "mock"], help="Driver mode: 'auto' (hardware/app) or 'mock' (offline test driver)")
    p_ucap.add_argument("--out", default=None, help="Run directory (default: a new private/lab-runs/<id>-usability)")
    p_ucap.add_argument("--scenario", action="append", default=None, help="Only this scenario ID (repeatable)")
    p_ucap.add_argument("--locale", default=None, help="App language for this walk, e.g. de-DE (per-app locale, restored after)")
    p_ucap.add_argument("--font-scale", type=float, default=None, help="System font scale for this walk, e.g. 2.0 (restored after)")
    p_upan = sub.add_parser("usability-panel", help="Build the persona and moderator packets for a captured run")
    p_upan.add_argument("run_dir")
    p_upan.add_argument("--variant", action="append", default=[], help="Another capture of the same walk (other locale / font scale); repeatable")
    p_utl = sub.add_parser("usability-textload", help="Text load per screen and control, from captured runs only (no personas)")
    p_utl.add_argument("run_dir")
    p_utl.add_argument("--variant", action="append", default=[], help="Another capture of the same walk; repeatable")
    p_uasm = sub.add_parser("usability-assemble", help="Collect each persona's per-screen replies into answers/<persona>.json")
    p_uasm.add_argument("run_dir")
    p_uchk = sub.add_parser("usability-check", help="Validate persona answers (and extract JSON from raw replies)")
    p_uchk.add_argument("run_dir")
    p_urep = sub.add_parser("usability-report", help="Render the decision report from persona answers and the moderator brief")
    p_urep.add_argument("run_dir")

    # usability-explore (R3, M4)
    p_uexp = sub.add_parser("usability-explore", help="Run opt-in live AI persona exploration loop")
    p_uexp.add_argument(
        "--product",
        default="phototok",
        choices=["phototok", "android-desktop", "macos", "linux"],
        help="Target product (default: phototok)",
    )
    p_uexp.add_argument(
        "--charter",
        required=True,
        help="Path to exploration charter markdown file or charter ID (e.g. commuter)",
    )
    p_uexp.add_argument(
        "--agent",
        default="mock",
        choices=["mock", "claude", "gemini"],
        help="Exploration agent type (default: mock)",
    )
    p_uexp.add_argument(
        "--budget",
        type=int,
        default=25,
        help="Step budget limit for exploration (default: 25)",
    )
    p_uexp.add_argument(
        "--driver",
        default="auto",
        choices=["auto", "mock"],
        help="Driver mode: 'auto' (hardware/app) or 'mock' (offline test driver)",
    )
    p_uexp.add_argument(
        "--serial",
        default=None,
        help="ADB serial of the test device (required for Android products on real hardware)",
    )
    p_uexp.add_argument(
        "--out",
        default=None,
        help="Run directory (default: a new private/lab-runs/<id>-explore)",
    )

    # explore (Feature 1, M3)
    p_exp = sub.add_parser(
        "explore",
        help="Time-boxed exploratory interaction testing across products",
        description="Run seeded, expectation-driven exploratory UI testing on target devices.",
    )
    p_exp.add_argument(
        "--product",
        choices=[
            "phototok",
            "jackpix",
            "android-desktop",
            "frameduel",
            "macos-desktop",
            "macos",
            "lensjet",
            "linux-desktop",
            "linux",
            "blitzlens",
        ],
        required=True,
        help="Target product (phototok, android-desktop, macos-desktop, linux-desktop, or aliases jackpix, frameduel, lensjet, blitzlens; legacy desktop excluded)",
    )
    p_exp.add_argument(
        "--duration",
        default="15m",
        help="Time budget for exploratory session (e.g. 15m, 300s, 1h, 45; default: 15m)",
    )
    p_exp.add_argument(
        "--seed",
        type=int,
        default=42,
        help="PRNG random seed for reproducible action selection (default: 42)",
    )
    p_exp.add_argument(
        "--device",
        default=None,
        help="Target device nickname or ADB serial (or comma-separated list; default: all eligible devices)",
    )
    p_exp.add_argument(
        "--config",
        default=None,
        help="Target config layout (e.g. phone-portrait, tab-landscape; default: all supported configs)",
    )
    p_exp.add_argument(
        "--ai-provider",
        choices=["claude", "antigravity", "packet", "none"],
        default="none",
        help="AI provider for pre-interaction expectation generation and judging (default: none)",
    )
    p_exp.add_argument(
        "--out",
        "--report-dir",
        dest="out",
        default=None,
        help="Explicit output run directory (default: new directory under private/lab-runs/ or /tmp/lab-runs/)",
    )
    p_exp.add_argument(
        "--with-personal",
        action="store_true",
        default=False,
        help="Allow running exploratory testing on personal devices (requires active user check)",
    )
    p_exp.add_argument(
        "--driver",
        choices=["auto", "direct", "mock"],
        default="auto",
        help="Driver execution mode: 'auto' (detect environment), 'direct' (hardware/app), or 'mock' (offline test driver)",
    )

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if not args.subcommand:
        parser.print_help()
        return 0

    if args.subcommand.startswith("usability-"):
        return _usability(args)

    if args.subcommand == "preflight":
        import shutil

        from .android.runner import maestro_binary

        maestro = maestro_binary()
        tools = {
            "python": sys.version.split()[0],
            "pyyaml": "real" if not getattr(yaml, "__lab_fallback__", False) else "fallback (apt install python3-yaml)",
            "adb": shutil.which("adb") or "missing",
            "maestro": maestro if Path(maestro).is_file() else "missing",
            "xdotool": shutil.which("xdotool") or "missing (apt install xdotool)",
            "Xvfb": shutil.which("Xvfb") or "missing (apt install xvfb)",
            "swift": shutil.which("swift") or "missing",
        }
        print(json.dumps(tools, indent=2))
        return 0

    if args.subcommand == "devices":
        from .inventory import load_inventory, resolve_android_device

        inv = load_inventory()
        if not inv.android and not inv.mac:
            print("No inventory at private/lab/devices.yaml", file=sys.stderr)
            return 2
        for dev in inv.android:
            tag = "personal" if dev.personal else "dedicated"
            if dev.personal and not args.with_personal:
                print(f"  -  {dev.name:<16} {dev.model:<24} {tag:<9} {','.join(dev.products):<26} (not probed)")
                continue
            ok, reason = resolve_android_device(dev)
            mark = "ok" if ok else "--"
            where = dev.adb_serial if ok else reason
            print(f"  {mark} {dev.name:<16} {dev.model:<24} {tag:<9} {','.join(dev.products):<26} {where}")
        if inv.mac:
            from .macos.remote import preflight_remote_mac

            if inv.mac.personal and not args.with_personal:
                print(f"  -  {'mac':<16} {inv.mac.host:<24} personal  macos                      (not probed)")
            else:
                ok, reason = preflight_remote_mac(inv.mac.host, user=inv.mac.user)
                print(f"  {'ok' if ok else '--'} {'mac':<16} {inv.mac.host:<24} {'personal':<9} {'macos':<26} {reason or ''}")
        return 0

    if args.subcommand == "pair":
        from .inventory import load_inventory, pair_advertised_device

        serial = None
        if args.device:
            dev = load_inventory().find(args.device)
            if dev is None:
                print(f"unknown device {args.device!r} in devices.yaml", file=sys.stderr)
                return 2
            serial = dev.serial
        ok, msg = pair_advertised_device(args.code, address=args.address, serial=serial)
        print(msg)
        return 0 if ok else 1

    if args.subcommand == "install-timer":
        from .timer import install_timer

        return install_timer(dry_run=bool(args.dry_run))

    if args.subcommand == "fixtures":
        from .fixtures import CorpusMissingError, generate_fixtures, load_spec

        out_root = Path(args.out).expanduser().resolve()
        if args.only_set:
            generate_fixtures(args.only_set, out_root / args.only_set, seed=int(args.seed))
        else:
            spec = load_spec()
            for set_name in spec.get("sets", {}):
                try:
                    generate_fixtures(set_name, out_root / set_name, seed=int(args.seed))
                except CorpusMissingError:
                    continue
        return 0

    if args.subcommand in ("lint", "layout-lint"):
        from .layout_lint import lint, load_rules
        from .model import Tree

        tree_path = Path(args.tree_json)
        tree_obj = Tree.from_dict(json.loads(tree_path.read_text(encoding="utf-8")))
        rule_spec = args.rules or args.product
        if not rule_spec:
            print("error: specify --product or --rules", file=sys.stderr)
            return 2
        rules_p = Path(rule_spec)
        if rules_p.is_file():
            rules_cfg = yaml.safe_load(rules_p.read_text(encoding="utf-8")) or {}
        else:
            rules_cfg = load_rules(rule_spec)
        expect_cfg = None
        if args.expect:
            exp_p = Path(args.expect)
            if exp_p.is_file():
                expect_cfg = yaml.safe_load(exp_p.read_text(encoding="utf-8"))
            else:
                expect_cfg = json.loads(args.expect)
        findings = lint(
            tree_obj,
            rules_cfg,
            expect_cfg,
            product=args.product or str(rule_spec),
            checkpoint=args.checkpoint,
        )
        print(json.dumps([f.to_dict() for f in findings], indent=2))
        return 1 if findings else 0

    if args.subcommand == "oracle":
        from .fs_oracle import evaluate_fs_oracle

        fs_data = json.loads(Path(args.fs_json).read_text(encoding="utf-8"))
        exp_p = Path(args.expect)
        exp_data = yaml.safe_load(exp_p.read_text(encoding="utf-8")) if exp_p.is_file() else yaml.safe_load(args.expect)
        init_data = json.loads(Path(args.initial).read_text(encoding="utf-8")) if args.initial else None
        findings = evaluate_fs_oracle(fs_data, exp_data, initial_fs=init_data)
        print(json.dumps([f.to_dict() for f in findings], indent=2))
        return 1 if findings else 0

    if args.subcommand == "checkpoint":
        from .android.adb import AdbClient
        from .android.runner import capture_android_checkpoint

        adb = AdbClient(serial=args.serial)
        capture_android_checkpoint(
            adb=adb,
            idx=1,
            name=args.name,
            out_dir=Path(args.out),
            device_fixture_root="/sdcard/Pictures/PSTLab/smoke",
        )
        return 0

    if args.subcommand == "report":
        from .report import generate_report

        _, html_path = generate_report(Path(args.run_dir))
        print(str(html_path))
        return 0

    if args.subcommand == "review-bundle":
        from .review_bundle import build_review_bundles

        bundles = build_review_bundles(
            Path(args.run_dir),
            include_all=bool(args.all),
            max_images=int(args.max_images),
        )
        for b in bundles:
            print(str(b))
        return 0

    if args.subcommand == "review-ingest":
        from .ingest import extract_findings_from_raw, ingest_review_bundles

        if args.extract:
            if not args.out:
                print("error: --out is required with --extract", file=sys.stderr)
                return 2
            extract_findings_from_raw(Path(args.extract), Path(args.out))
            return 0
        if not args.run_dir:
            print("error: run_dir is required unless --extract is used", file=sys.stderr)
            return 2
        ingest_review_bundles(Path(args.run_dir))
        return 0

    if args.subcommand == "triage":
        from .ingest import triage_finding

        triage_finding(Path(args.run_dir), args.finding_id, reason=args.reason, until=args.until)
        return 0

    if args.subcommand == "run":
        from .orchestrate import ALL_PRODUCTS, run_lab

        if args.all:
            products = list(ALL_PRODUCTS)
        elif args.product:
            products = [args.product]
        else:
            print("error: specify --all or --product <name>", file=sys.stderr)
            return 2
        run_lab(
            products=products,
            journey_filter=args.journey,
            config_filter=args.config,
            out_dir=Path(args.out) if args.out else None,
            installed=bool(args.installed),
            with_personal=bool(args.with_personal),
            only_devices=args.device,
            build=not bool(args.no_build),
        )
        return 0

    if args.subcommand == "explore":
        return _explore(args)

    return 2


def _explore(args: argparse.Namespace) -> int:
    """Execute time-boxed exploratory testing across target devices."""
    import subprocess

    from .explore.discovery import normalize_product_name
    from .explore.orchestrator import ExploratoryOrchestrator, parse_duration_string
    from .locks import DeviceBusy
    from .paths import new_run_dir, repo_root

    # 1. Parse duration
    try:
        duration_s = parse_duration_string(args.duration)
    except ValueError as exc:
        print(f"error: invalid --duration {args.duration!r}: {exc}", file=sys.stderr)
        return 2

    # 2. Normalize product name
    try:
        product = normalize_product_name(args.product)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    # 3. Resolve run output directory
    if args.out:
        out_dir = Path(args.out).expanduser().resolve()
        out_dir.mkdir(parents=True, exist_ok=True)
    elif args.driver == "mock":
        import tempfile

        from .paths import lab_tmp_root

        out_dir = Path(tempfile.mkdtemp(prefix=f"explore-mock-{product}-", dir=lab_tmp_root()))
    else:
        try:
            sha = subprocess.run(
                ["git", "-C", str(repo_root()), "rev-parse", "--short", "HEAD"],
                capture_output=True,
                text=True,
                check=False,
            ).stdout.strip() or "unknown"
        except Exception:
            sha = "unknown"
        out_dir = new_run_dir(sha, label=f"explore-{product}")

    # 4. Parse devices and configs
    devices = [d.strip() for d in args.device.split(",")] if args.device else None
    configs = [c.strip() for c in args.config.split(",")] if args.config else None

    # 5. Initialize orchestrator
    try:
        orchestrator = ExploratoryOrchestrator(
            product=product,
            duration=duration_s,
            seed=int(args.seed),
            out_dir=out_dir,
            device=devices,
            config=configs,
            ai_provider=args.ai_provider,
            driver=args.driver,
            with_personal=bool(args.with_personal),
        )
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    # 6. Run exploratory session
    try:
        summary = orchestrator.run()
    except DeviceBusy as exc:
        print(f"device is busy: {exc}", file=sys.stderr)
        return 3
    except KeyboardInterrupt:
        print("\n[lab] exploration interrupted by user (SIGINT)", file=sys.stderr)
        return 130
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except Exception as exc:
        print(f"error: exploratory session failed: {exc}", file=sys.stderr)
        return 1

    # Check for in-use or personal device skip errors
    for w in getattr(summary, "worker_results", []):
        if w.status in ("skipped_in_use", "skipped_personal"):
            print(f"error: {w.error}", file=sys.stderr)
            return 2

    # 7. Generate report and log to canonical backlog
    report_artifacts = {}
    try:
        from .explore.reporter import generate_explore_report, log_findings_to_canonical_backlog

        import tempfile

        tmp_root_str = str(Path(tempfile.gettempdir()).resolve())
        is_live_run = args.driver != "mock" and not str(out_dir).startswith(tmp_root_str)
        report_artifacts = generate_explore_report(
            summary,
            summary.run_dir,
            date_stage=is_live_run if not args.out else None,
        )
        if is_live_run:
            logged_count = log_findings_to_canonical_backlog(
                summary.findings,
                repo_path=None,
                date_str=None,
                seed=getattr(args, "seed", None),
            )
            if logged_count > 0:
                print(f"Logged {logged_count} blocker/major finding(s) to code_health.md")
    except Exception as exc:
        print(f"[lab] Warning: Report generation failed: {exc}", file=sys.stderr)

    # 8. Print summary and return exit code
    total = summary.total_steps
    passed = summary.passed_steps
    failed = summary.failed_steps
    findings_count = len(summary.findings)
    print(f"Exploration completed: {total} steps ({passed} passed, {failed} failed), {findings_count} findings")
    print(f"Run directory: {summary.run_dir}")
    if "report_md" in report_artifacts:
        print(f"Report: {report_artifacts['report_md']}")
    if "report_html" in report_artifacts:
        print(f"HTML report: {report_artifacts['report_html']}")

    # Check for worker fatal errors
    if any(w.status == "error" for w in getattr(summary, "worker_results", [])):
        return 1

    # Exit code: 1 if failures or blocker/major findings exist, otherwise 0
    blockers = [f for f in summary.findings if getattr(f, "severity", "") in ("blocker", "major")]
    return 1 if (failed > 0 or len(blockers) > 0) else 0


def _usability(args: argparse.Namespace) -> int:
    if args.subcommand == "usability-capture":
        import os
        import subprocess

        from .paths import new_run_dir, repo_root
        from .usability.capture import run_capture
        from .usability.driver import DeviceBusy

        # Refuse before creating a run folder: a refused capture left an empty one behind.
        serial = args.serial
        driver_mode = getattr(args, "driver", "auto")
        if args.product in ("phototok", "android-desktop") and driver_mode != "mock":
            if not serial:
                serial = os.environ.get("ANDROID_SERIAL")
            if not serial:
                print(f"error: --serial (or ANDROID_SERIAL env var) is required for Android product '{args.product}'", file=sys.stderr)
                return 2

        if args.out:
            out = Path(args.out)
        else:
            sha = subprocess.run(["git", "-C", str(repo_root()), "rev-parse", "HEAD"], capture_output=True, text=True).stdout
            from .usability.capture import variant_label

            label = variant_label(args.locale, args.font_scale).replace("@", "-fs")
            out = new_run_dir(sha, label=f"usability-{args.product}-{label}")

        try:
            w = run_capture(
                args.product,
                serial,
                out,
                only=args.scenario,
                locale=args.locale,
                font_scale=args.font_scale,
                driver_type=driver_mode,
            )
        except DeviceBusy as exc:
            print(f"device {serial} is busy: {exc}", file=sys.stderr)
            if out.is_dir() and not any(out.iterdir()):
                out.rmdir()
            return 3
        except ValueError as exc:  # a request the product cannot honour (no serial, no translation)
            print(f"error: {exc}", file=sys.stderr)
            if out.is_dir() and not any(out.iterdir()):
                out.rmdir()
            return 2
        for sc in w["scenarios"]:
            print(f"{sc['scenario']}: {sc['status']} — {len(sc['screens'])} screens" + (f" ({sc['error']})" if sc["error"] else ""))
        print(out)
        return 0 if all(sc["status"] == "pass" for sc in w["scenarios"]) else 1

    if args.subcommand == "usability-explore":
        import contextlib
        import os
        import subprocess

        from .paths import new_run_dir, repo_root
        from .usability.capture import android_session, load_scenarios
        from .usability.driver import DeviceBusy, get_driver
        from .usability.explore import (
            ClaudeExplorationAgent,
            GeminiExplorationAgent,
            MockExplorationAgent,
            load_charter,
            run_exploration,
        )

        driver_mode = getattr(args, "driver", "auto")
        if args.product not in ("phototok", "android-desktop") and driver_mode != "mock":
            # The loop observes the screen between single steps; the desktop hooks run a whole
            # script per launch and cannot answer "what is on screen now".
            print(f"error: live exploration needs an Android product, not {args.product!r}", file=sys.stderr)
            return 2
        serial = args.serial or os.environ.get("ANDROID_SERIAL")
        if driver_mode != "mock" and not serial:
            print(f"error: --serial (or ANDROID_SERIAL env var) is required for Android product '{args.product}'", file=sys.stderr)
            return 2
        try:
            charter = load_charter(args.charter, product=args.product)
        except (OSError, ValueError) as exc:
            print(f"error loading charter: {exc}", file=sys.stderr)
            return 2
        if args.out:
            out = Path(args.out)
        else:
            sha = subprocess.run(["git", "-C", str(repo_root()), "rev-parse", "HEAD"], capture_output=True, text=True).stdout
            out = new_run_dir(sha, label=f"explore-{charter.id}")
        spec = load_scenarios(args.product)
        app_id = str(spec["app_id"])
        agents = {"mock": MockExplorationAgent, "claude": ClaudeExplorationAgent, "gemini": GeminiExplorationAgent}
        agent = agents[args.agent]()
        driver = get_driver(args.product, app_id, out, serial=serial, driver_type=driver_mode)
        session = (
            contextlib.nullcontext()
            if driver_mode == "mock"
            else android_session(driver.adb, spec, args.product, purpose="usability-explore")  # type: ignore[attr-defined]
        )
        try:
            with session:
                if driver_mode != "mock":
                    driver._step({"launch": {}})  # type: ignore[attr-defined]
                result = run_exploration(driver=driver, charter=charter, step_budget=args.budget, agent=agent, out_dir=out)
        except DeviceBusy as exc:
            print(f"device {serial} is busy: {exc}", file=sys.stderr)
            return 3
        finally:
            driver.close()

        status = result.get("status", "unknown")
        findings = result.get("findings", [])
        print(f"Exploration {status}: {result.get('steps_taken', 0)}/{result.get('budget', args.budget)} steps, "
              f"{result.get('screens_visited_count', 0)} unique screens, {len(findings)} findings, "
              f"{result.get('dead_ends', 0)} dead ends" + (f" ({result['error']})" if result.get("error") else ""))
        print(out)
        # Reaching the goal, running out of steps or giving up are results; a broken run is not.
        return 0 if status in ("goal_reached", "budget_exhausted", "gave_up", "dead_end") else 1

    run_dir = Path(args.run_dir)
    if args.subcommand == "usability-panel":
        from .usability.panel import build_panel

        print(build_panel(run_dir, variants=[Path(v) for v in args.variant]))
        return 0
    if args.subcommand == "usability-textload":
        from .usability.panel import load_variants, text_load_table

        table, over = text_load_table(load_variants(run_dir, [Path(v) for v in args.variant]))
        print(table)
        return 1 if over else 0
    if args.subcommand == "usability-assemble":
        from .usability.panel import assemble_answers

        problems = assemble_answers(run_dir / "panel")
        for pid, items in problems.items():
            print(f"{pid}: {'ok' if not items else '; '.join(items)}")
        return 1 if any(problems.values()) or not problems else 0
    if args.subcommand == "usability-check":
        from .usability.panel import expected_screen_keys, extract_json, validate_persona

        walkthrough = json.loads((run_dir / "walkthrough.json").read_text(encoding="utf-8"))
        answers = run_dir / "panel" / "answers"
        for raw in sorted(answers.glob("*.raw.txt")):
            target = answers / raw.name.replace(".raw.txt", ".json")
            if not target.exists():
                target.write_text(json.dumps(extract_json(raw.read_text(encoding="utf-8")), indent=2), encoding="utf-8")
        dec_raw = run_dir / "panel" / "decisions.raw.txt"
        if dec_raw.is_file() and not (run_dir / "panel" / "decisions.json").exists():
            decisions = extract_json(dec_raw.read_text(encoding="utf-8"))
            (run_dir / "panel" / "decisions.json").write_text(json.dumps(decisions, indent=2), encoding="utf-8")
        failed = 0
        assignments_path = run_dir / "panel" / "assignments.json"
        assignments = json.loads(assignments_path.read_text(encoding="utf-8")) if assignments_path.is_file() else {}
        for path in sorted(answers.glob("*.json")):
            variant_file = run_dir / "panel" / "variants" / f"{assignments.get(path.stem, '')}.json"
            seen = json.loads(variant_file.read_text(encoding="utf-8")) if variant_file.is_file() else walkthrough
            problems = validate_persona(json.loads(path.read_text(encoding="utf-8")), expected_screen_keys(seen))
            print(f"{path.stem}: {'ok' if not problems else '; '.join(problems)}")
            failed += bool(problems)
        return 1 if failed else 0
    if args.subcommand == "usability-report":
        from .usability.report import coverage_problems, write_report

        for problem in coverage_problems(run_dir):
            print(f"warning: {problem}")
        html_path, md_path = write_report(run_dir)
        print(html_path)
        print(md_path)
        return 0
    return 2
