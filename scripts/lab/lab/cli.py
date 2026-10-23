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

    return 2


def _usability(args: argparse.Namespace) -> int:
    if args.subcommand == "usability-capture":
        import os
        import subprocess

        from .paths import new_run_dir, repo_root
        from .usability.capture import run_capture
        from .usability.driver import DeviceBusy

        if args.out:
            out = Path(args.out)
        else:
            sha = subprocess.run(["git", "-C", str(repo_root()), "rev-parse", "HEAD"], capture_output=True, text=True).stdout
            from .usability.capture import variant_label

            out = new_run_dir(f"{sha.strip()[:12]}-usability-{variant_label(args.locale, args.font_scale).replace('@', '-fs')}")

        serial = args.serial
        driver_mode = getattr(args, "driver", "auto")
        if args.product in ("phototok", "android-desktop") and driver_mode != "mock":
            if not serial:
                serial = os.environ.get("ANDROID_SERIAL")
            if not serial:
                print(f"error: --serial (or ANDROID_SERIAL env var) is required for Android product '{args.product}'", file=sys.stderr)
                return 2

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
        except Exception as exc:
            print(f"error running capture: {exc}", file=sys.stderr)
            return 1
        for sc in w["scenarios"]:
            print(f"{sc['scenario']}: {sc['status']} — {len(sc['screens'])} screens" + (f" ({sc['error']})" if sc["error"] else ""))
        print(out)
        return 0 if all(sc["status"] == "pass" for sc in w["scenarios"]) else 1

    if args.subcommand == "usability-explore":
        import os
        import subprocess

        from .paths import new_run_dir, repo_root
        from .usability.capture import load_scenarios
        from .usability.driver import DeviceBusy, get_driver
        from .usability.explore import (
            ClaudeExplorationAgent,
            GeminiExplorationAgent,
            MockExplorationAgent,
            load_charter,
            run_exploration,
        )

        if args.out:
            out = Path(args.out)
        else:
            sha = subprocess.run(["git", "-C", str(repo_root()), "rev-parse", "HEAD"], capture_output=True, text=True).stdout
            out = new_run_dir(f"{sha.strip()[:12]}-explore")

        serial = args.serial
        driver_mode = getattr(args, "driver", "auto")
        if args.product in ("phototok", "android-desktop") and driver_mode != "mock":
            if not serial:
                serial = os.environ.get("ANDROID_SERIAL")
            if not serial:
                print(f"error: --serial (or ANDROID_SERIAL env var) is required for Android product '{args.product}'", file=sys.stderr)
                return 2

        # 1. Resolve and load charter
        try:
            charter = load_charter(args.charter, product=args.product)
        except Exception as exc:
            print(f"error loading charter: {exc}", file=sys.stderr)
            return 2

        # 2. Resolve app_id from scenarios if available
        try:
            spec = load_scenarios(args.product)
            app_id = str(spec.get("app_id", "com.phototok.lab"))
        except Exception:
            app_id = f"com.{args.product}.lab"

        # 3. Create driver
        try:
            driver = get_driver(
                args.product,
                app_id,
                out,
                serial=serial,
                driver_type=driver_mode,
            )
        except DeviceBusy as exc:
            print(f"device {serial} is busy: {exc}", file=sys.stderr)
            return 3
        except Exception as exc:
            print(f"error initializing driver: {exc}", file=sys.stderr)
            return 1

        # 4. Instantiate exploration agent
        agent_type = getattr(args, "agent", "mock")
        if agent_type == "mock":
            agent = MockExplorationAgent()
        elif agent_type == "claude":
            agent = ClaudeExplorationAgent()
        elif agent_type == "gemini":
            agent = GeminiExplorationAgent()
        else:
            print(f"error: unknown agent type '{agent_type}'", file=sys.stderr)
            return 2

        # 5. Run exploration
        try:
            result = run_exploration(
                driver=driver,
                charter=charter,
                step_budget=args.budget,
                agent=agent,
                out_dir=out,
            )
        except Exception as exc:
            print(f"error during exploration: {exc}", file=sys.stderr)
            return 1
        finally:
            driver.close()

        # 6. Report trajectory summary
        status = result.get("status", "unknown")
        steps_taken = result.get("steps_taken", len(result.get("trajectory", [])))
        unique_screens = len({s.get("screen") for s in result.get("trajectory", []) if s.get("screen")})
        findings = result.get("findings", [])
        dead_ends = result.get("dead_ends", 0)

        print(f"Exploration {status}: {steps_taken}/{args.budget} steps, {unique_screens} unique screens, {len(findings)} findings, {dead_ends} dead ends")
        print(out)
        return 0 if status in ("pass", "goal_reached", "completed", "budget_exhausted") else 1

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
