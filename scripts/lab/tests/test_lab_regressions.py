"""Regression tests for fixed lab defects whose ledger guard was `none` (FINDINGS_HISTORY, F-LAB rows).

Where the fix was about real-device behaviour the test replays what the device produced (the
captured Maestro output, the real launchd plist, the real flows); a fake written from the author's
model proves only the author's model (playbook-run-device-lab, *Writing or changing a flow*).
"""

from __future__ import annotations

import ast
import json
import plistlib
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import yaml  # noqa: E402

from lab.layout_lint import lint, load_rules  # noqa: E402
from lab.linux.keys import send_keys_xdotool  # noqa: E402
from lab.model import Tree  # noqa: E402
from lab.paths import repo_root  # noqa: E402

ROOT = repo_root()
LAB = ROOT / "scripts" / "lab"
E2E_TOK = ROOT / "products" / "android" / "phototok" / "tests" / "e2e"
E2E_AD = ROOT / "products" / "android" / "android-desktop" / "tests" / "e2e"

try:
    import PIL  # noqa: F401

    from PIL import Image

    # The lab's built-in fallback is not Pillow: it has no `__version__` on the module it installs.
    REAL_PILLOW = hasattr(PIL, "__version__") and hasattr(Image, "Resampling")
except ImportError:  # pragma: no cover
    REAL_PILLOW = False


# Maestro flows are real YAML (comments, `true:` keys); the lab's fallback reader is for journeys only.
NEEDS_PYYAML = unittest.skipUnless(hasattr(yaml, "safe_load_all"), "needs PyYAML to read Maestro flows (the lab's fallback reader cannot)")


def flow(path: Path) -> list:
    """The commands of a Maestro flow (the document after the `appId` header)."""
    docs = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
    return docs[-1] or []


def walk(commands: object):
    """Every command dict in a flow, with the chain of `when` guards of the `runFlow`s around it."""
    def rec(node: object, guards: tuple):
        if isinstance(node, list):
            for item in node:
                yield from rec(item, guards)
        elif isinstance(node, dict):
            yield node, guards
            for key, value in node.items():
                if key == "runFlow" and isinstance(value, dict):
                    g = guards + ((value.get("when"),) if value.get("when") else ())
                    yield from rec(value.get("commands", []), g)
                else:
                    pass
    yield from rec(commands, ())


def journey(jid: str) -> dict:
    return yaml.safe_load((LAB / "journeys" / f"{jid}.yaml").read_text(encoding="utf-8"))


class LabStartAndPortabilityTest(unittest.TestCase):
    def test_f_lab_001_the_mac_agent_is_started_through_bash_or_is_executable(self) -> None:
        """launchd ran agent_run.sh directly and got 'Permission denied': git keeps it 0644, so the plist names the interpreter."""
        plist = plistlib.loads((LAB / "macos" / "com.pstlab.runner.plist").read_bytes())
        args = plist["ProgramArguments"]
        script = next(a for a in args if a.endswith("agent_run.sh"))
        mode = subprocess.run(["git", "ls-files", "-s", "scripts/lab/macos/agent_run.sh"], cwd=ROOT, capture_output=True, text=True).stdout.split()
        executable = bool(mode) and mode[0] == "100755"
        self.assertTrue(executable or args[args.index(script) - 1] == "/bin/bash", f"{args} with git mode {mode[:1]}")

    def test_f_lab_004_every_lab_module_parses_on_python_39_and_defers_its_annotations(self) -> None:
        """The Mac lab runs Python 3.9: no 3.10 syntax, and `X | None` annotations need `from __future__ import annotations`."""
        offenders = []
        for path in sorted((LAB / "lab").rglob("*.py")):
            src = path.read_text(encoding="utf-8")
            tree = ast.parse(src, feature_version=(3, 9))  # raises on match/case, parenthesised context managers, ...
            deferred = any(isinstance(n, ast.ImportFrom) and n.module == "__future__" and any(a.name == "annotations" for a in n.names) for n in tree.body)
            annotations = [a for n in ast.walk(tree) for a in (
                [n.returns] + [x.annotation for x in n.args.args + n.args.kwonlyargs] if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
                else [n.annotation] if isinstance(n, ast.AnnAssign) else []) if a is not None]
            uses_union = any(isinstance(sub, ast.BinOp) and isinstance(sub.op, ast.BitOr) for a in annotations for sub in ast.walk(a))
            if uses_union and not deferred:
                offenders.append(str(path.relative_to(ROOT)))
        self.assertEqual(offenders, [])


class FlowRegressionTest(unittest.TestCase):
    @NEEDS_PYYAML
    def test_f_lab_009_the_frameduel_delete_flow_taps_delete_not_the_star_bars_reject(self) -> None:
        ids = [c["tapOn"]["id"] for c, _ in walk(flow(E2E_AD / "J-AD-04" / "02_touch_navigate_and_delete.yaml")) if isinstance(c.get("tapOn"), dict) and "id" in c["tapOn"]]
        self.assertEqual(ids, ["delete_button_(expanded|compact)"])
        # and the ids it may tap are tags the product defines
        src = "".join(p.read_text(encoding="utf-8") for p in (ROOT / "products/android/android-desktop/src").rglob("*.kt"))
        for tag in ("delete_button_expanded", "delete_button_compact"):
            self.assertIn(f'"{tag}"', src)

    def test_f_lab_027_the_journeys_that_expect_a_copy_first_make_swipe_right_copy(self) -> None:
        for jid in ("J-TOK-03", "J-TOK-05"):
            first = next(s for s in journey(jid)["steps"] if "maestro" in s)["maestro"]
            self.assertTrue(first.endswith("/_swipe_right_copies.yaml"), f"{jid} starts with {first}")

    @NEEDS_PYYAML
    def test_f_lab_029_and_036_the_landing_scrolls_run_only_when_the_landing_is_on_screen_and_never_guard_on_their_own_target(self) -> None:
        """The first fix guarded a scroll by the element it scrolls to, which skipped it exactly where needed (Xperia XA2)."""
        scrolls = 0
        for cmd, guards in walk(flow(E2E_TOK / "_ensure_folder.yaml")):
            sc = cmd.get("scrollUntilVisible")
            if not sc:
                continue
            scrolls += 1
            target = sc["element"]["id"]
            on_landing = any(isinstance(g, dict) and g.get("true", g.get(True)) == "${output.onLanding}" for g in guards)  # YAML 1.1 reads `true:` as a boolean key
            self.assertTrue(on_landing, f"scroll to {target} is not under the onLanding guard")
            for g in guards:
                if isinstance(g, dict) and "visible" in g:
                    self.assertNotEqual(g["visible"].get("id"), target, f"scroll to {target} guarded by its own visibility")
        self.assertGreaterEqual(scrolls, 2)
        # the guard's value is recorded from the landing's logo, which is visible in every orientation
        text = (E2E_TOK / "_ensure_folder.yaml").read_text(encoding="utf-8")
        self.assertRegex(text, r'visible:\s*\n\s*id: "landing_logo"\s*\n\s*commands:\s*\n\s*- evalScript: \$\{output\.onLanding = true\}')

    @NEEDS_PYYAML
    def test_f_lab_030_every_jackpix_dialog_button_is_matched_in_german_too(self) -> None:
        own = {"PSTLab", "work", "ISO 400", "Camera"}  # fixture-owned strings, the same in every language
        seen = []
        for path in sorted(E2E_TOK.rglob("*.yaml")):
            for cmd, _ in walk(flow(path)):
                for value in cmd.values():
                    if isinstance(value, dict):
                        selectors = [value] + [v for v in value.values() if isinstance(v, dict)]
                        for sel in selectors:
                            text = sel.get("text")
                            if isinstance(text, str) and text not in own:
                                seen.append((path.name, text))
                                self.assertIn("|", text, f"{path.name}: {text!r} has no German alternative")
        self.assertTrue({"Delete|Löschen", "Show new|Neue zeigen|Start with Newest"} <= {t for _, t in seen}, seen)


class LintRegressionTest(unittest.TestCase):
    def test_f_lab_026_a_static_position_counter_is_not_a_touch_target(self) -> None:
        """Lensjet's counter (36x20 pt, not interactive) was reported as a too-small control."""
        tree = Tree.from_dict({"platform": "macos", "window": {"w": 1440, "h": 900, "unit": "pt"}, "nodes": [
            {"id": "position_counter", "parent": None, "role": "text", "name": "3 of 12", "bounds": [700, 20, 36, 20],
             "interactive": False, "visible": True, "overlay": False, "text": "3 / 12", "clipped": None},
        ]})
        self.assertEqual([f.rule for f in lint(tree, load_rules("macos"), product="macos")], [])
        tree.nodes[0].interactive = True  # the same node as a control is still too small: the rule works
        self.assertEqual([f.rule for f in lint(tree, load_rules("macos"), product="macos")], ["touch-target"])


class KeyDriverRegressionTest(unittest.TestCase):
    def test_f_lab_040_the_linux_key_driver_finds_the_window_the_product_titles(self) -> None:
        """After the rename the driver searched for the old name. The search string must occur in the title the product sets."""
        styling = (ROOT / "products/linux-desktop/src/photo_selector_linux/ui/styling.py").read_text(encoding="utf-8")
        brand = re.search(r'APP_BRAND_NAME: str = "([^"]+)"', styling).group(1)
        window = (ROOT / "products/linux-desktop/src/photo_selector_linux/ui/window.py").read_text(encoding="utf-8")
        self.assertIn("self.set_title(APP_BRAND_NAME)", window)  # the title is the brand, nothing else
        cmds = send_keys_xdotool(["Right"], ":99", run_cmd=lambda c, e: subprocess.CompletedProcess(c, 0, "", ""), delay_s=0)
        search = cmds[0]
        self.assertEqual(search[search.index("--name") + 1], brand)


@unittest.skipUnless(REAL_PILLOW, "needs the real Pillow (the lab's fallback writes PNG data inside JPEG markers: F-LAB-003)")
class FixtureRegressionTest(unittest.TestCase):
    def test_f_lab_003_fixture_photos_are_real_jpegs(self) -> None:
        from lab.fixtures import generate_fixtures

        with tempfile.TemporaryDirectory() as tmp:
            paths = [p for p in generate_fixtures("smoke", Path(tmp) / "work") if p.suffix.lower() in (".jpg", ".jpeg")]
            self.assertTrue(paths)
            for p in paths:
                data = p.read_bytes()
                self.assertEqual(data[:2], b"\xff\xd8", p.name)
                self.assertNotIn(b"\x89PNG", data[:64], f"{p.name}: PNG data inside JPEG markers")
                with Image.open(p) as img:
                    self.assertEqual(img.format, "JPEG", p.name)
                    img.load()  # decodes: an undecodable photo shows as an empty frame in the app

    def test_f_lab_044_scenes_have_the_detail_a_sharp_frame_needs(self) -> None:
        """Smooth scenes scored every frame, the keeper too, 'Blurry'. A scene must lose most of its edge energy when blurred."""
        from PIL import ImageFilter, ImageStat
        from lab.usability.scenes import PALETTES, render_scene

        def edge_energy(img: "Image.Image") -> float:
            return ImageStat.Stat(img.convert("L").filter(ImageFilter.FIND_EDGES)).var[0]

        for palette in sorted(PALETTES):
            scene = render_scene(palette, (640, 426), seed=7)
            sharp, soft = edge_energy(scene), edge_energy(scene.filter(ImageFilter.GaussianBlur(4)))
            self.assertGreater(sharp, 3 * soft, f"{palette}: sharp {sharp:.1f} vs blurred {soft:.1f}")


@unittest.skipUnless(REAL_PILLOW, "needs the real Pillow")
class ChangeFractionRegressionTest(unittest.TestCase):
    def test_f_lab_071_a_fully_changed_frame_reads_one(self) -> None:
        from lab.review_bundle import compute_image_change_fraction

        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a.png", Path(tmp) / "b.png"
            Image.new("RGB", (1280, 800), (0, 0, 0)).save(a)
            Image.new("RGB", (1280, 800), (255, 255, 255)).save(b)
            self.assertEqual(compute_image_change_fraction(a, b), 1.0)
            self.assertEqual(compute_image_change_fraction(a, a), 0.0)


class UsabilityScenarioRegressionTest(unittest.TestCase):
    def test_f_lab_061_lensjet_screens_after_a_culling_key_are_captured_inside_the_toast(self) -> None:
        """Toasts last 1.2 s (`showToast`); 0.7-0.9 s waits plus the capture's settle missed them."""
        src = (ROOT / "products/macos-desktop/src/PhotoSelectorKit/ViewModels/CullingWorkspaceViewModel.swift").read_text(encoding="utf-8")
        lifetime_ms = int(re.search(r"func showToast\(.*?Task\.sleep\(nanoseconds: ([\d_]+)\)", src, re.S).group(1).replace("_", "")) // 1_000_000
        self.assertEqual(lifetime_ms, 1200)
        culling = {"Delete", "Backspace", "M", "C", "Cmd+Z"}
        checked = []
        for sc in yaml.safe_load((LAB / "usability" / "macos.yaml").read_text(encoding="utf-8"))["scenarios"]:
            steps = sc["steps"]
            for i, step in enumerate(steps):
                if "wait_ms" in step and 0 < i < len(steps) - 1 and steps[i - 1].get("key") in culling and "screen" in steps[i + 1]:
                    checked.append((sc["id"], step["wait_ms"]))
        self.assertGreaterEqual(len(checked), 3)
        for sid, ms in checked:
            self.assertLessEqual(ms + 450, lifetime_ms, f"{sid}: a {ms} ms wait plus the settle outlives the toast")


if __name__ == "__main__":
    unittest.main()
