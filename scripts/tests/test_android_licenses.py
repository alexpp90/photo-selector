"""Unit tests for scripts/android_licenses.py (no Gradle, no device).

Each Android app bundles the licence notices of its release classpath
(``res/raw/third_party_notices.txt``) and shows them in Settings. The script fails when a runtime
dependency has no entry in ``products/android/licenses/components.json`` or when a committed
notice file no longer matches what its classpath needs.
"""

from __future__ import annotations

import json
from pathlib import Path
import re
import zipfile
import sys
import unittest

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import android_licenses as al  # noqa: E402

REPO = SCRIPTS_DIR.parent

TREE = """\
------------------------------------------------------------
Project ':phototok'
------------------------------------------------------------

releaseRuntimeClasspath - Runtime classpath of '/release'.
+--- org.jetbrains.kotlin:kotlin-stdlib:2.4.21
|    +--- org.jetbrains:annotations:13.0 -> 23.0.0
|    +--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0 -> 1.9.0 (c)
|    \\--- org.jetbrains.kotlin:kotlin-stdlib-common:2.4.21 (c)
+--- project ':core'
|    +--- androidx.exifinterface:exifinterface:1.4.2
|    |    \\--- org.jspecify:jspecify:1.0.0
|    \\--- org.jetbrains.kotlin:kotlin-stdlib:2.4.21 (*)
+--- androidx.compose.ui:ui -> 1.10.0
\\--- io.coil-kt:coil-compose:2.7.0

(c) - A dependency constraint, not a dependency.
(*) - Indicates repeated occurrences of a transitive dependency subtree.
"""


class ParseClasspathTest(unittest.TestCase):
    def test_reads_group_artifact_and_the_resolved_version(self):
        coords = al.parse_classpath(TREE)
        self.assertEqual(coords["org.jetbrains:annotations"], "23.0.0")
        self.assertEqual(coords["androidx.compose.ui:ui"], "1.10.0")   # BOM-managed: no declared version
        self.assertEqual(coords["io.coil-kt:coil-compose"], "2.7.0")

    def test_constraints_projects_and_legend_lines_are_not_dependencies(self):
        coords = al.parse_classpath(TREE)
        self.assertNotIn("org.jetbrains.kotlin:kotlin-stdlib-jdk8", coords)   # (c): not on the classpath
        self.assertFalse([c for c in coords if c.startswith("project")])
        self.assertEqual(sorted(c for c in coords if c.startswith("(")), [])


class MatchTest(unittest.TestCase):
    def components(self):
        return [
            {"id": "protobuf", "name": "Protocol Buffers", "owner": "Google", "license": "BSD-3-Clause",
             "url": "https://protobuf.dev", "match": ["androidx.datastore:datastore-preferences-external-protobuf"]},
            {"id": "androidx", "name": "AndroidX", "owner": "AOSP", "license": "Apache-2.0",
             "url": "https://developer.android.com", "match": ["androidx.*"]},
        ]

    def test_first_matching_entry_wins(self):
        comps = self.components()
        self.assertEqual(al.find_component(comps, "androidx.datastore:datastore-preferences-external-protobuf")["id"], "protobuf")
        self.assertEqual(al.find_component(comps, "androidx.core:core")["id"], "androidx")

    def test_an_unknown_dependency_has_no_entry(self):
        self.assertIsNone(al.find_component(self.components(), "org.example:thing"))

    def test_unlicensed_lists_every_coordinate_without_an_entry(self):
        coords = {"androidx.core:core": "1", "org.example:thing": "2", "org.other:lib": "3"}
        self.assertEqual(al.unlicensed(self.components(), coords), ["org.example:thing", "org.other:lib"])


class RenderTest(unittest.TestCase):
    def setUp(self):
        self.data = {
            "licenses": {"Apache-2.0": {"name": "Apache License 2.0", "url": "https://x", "file": "Apache-2.0.txt"}},
            "components": [{"id": "androidx", "name": "AndroidX", "owner": "The Android Open Source Project",
                            "license": "Apache-2.0", "url": "https://developer.android.com", "match": ["androidx.*"]},
                           {"id": "vico", "name": "Vico", "owner": "Patryk Goworowski", "license": "Apache-2.0",
                            "url": "https://github.com/patrykandpatrick/vico", "match": ["com.patrykandpatrick.vico:*"]}],
            "bundled": [],
        }
        self.texts = {"Apache-2.0": "APACHE LICENSE TEXT"}

    def test_lists_the_components_the_classpath_uses_and_the_licence_text_once(self):
        text = al.render("Jackpix", self.data, {"androidx.core:core": "1.0"}, self.texts)
        self.assertIn("Jackpix", text)
        self.assertIn("AndroidX", text)
        self.assertIn("The Android Open Source Project", text)
        self.assertNotIn("Vico", text)            # not on this app's classpath
        self.assertEqual(text.count("APACHE LICENSE TEXT"), 1)

    def test_the_text_carries_no_version_so_a_dependency_bump_does_not_stale_it(self):
        a = al.render("Jackpix", self.data, {"androidx.core:core": "1.0"}, self.texts)
        b = al.render("Jackpix", self.data, {"androidx.core:core": "2.0", "androidx.ui:ui": "9"}, self.texts)
        self.assertEqual(a, b)

    def test_a_component_with_bundled_native_libraries_lists_them(self):
        self.data["bundled"] = [{"in": "androidx.core:core", "name": "libpng", "license": "libpng License",
                                 "url": "https://www.libpng.org"}]
        text = al.render("Jackpix", self.data, {"androidx.core:core": "1.0"}, self.texts)
        self.assertIn("libpng", text)
        self.assertIn("https://www.libpng.org", text)


class BundledLicenseTextTest(unittest.TestCase):
    """A bundled native library's licence text must be in the notice, not only named."""

    def data(self):
        return {"licenses": {"Apache-2.0": {"name": "Apache", "url": "", "file": "Apache-2.0.txt"}},
                "components": [{"id": "ocv", "name": "OpenCV", "owner": "OpenCV team", "license": "Apache-2.0",
                                "url": "https://opencv.org", "match": ["org.opencv:*"]}],
                "bundled": [{"in": "org.opencv:opencv", "name": "libpng", "license": "libpng License",
                             "url": "https://x"}]}

    def test_check_fails_when_a_bundled_library_names_no_text(self):
        problems = al.missing_bundled_texts(self.data())
        self.assertEqual(len(problems), 1)
        self.assertIn("libpng", problems[0])

    def test_check_fails_when_the_named_text_file_is_missing(self):
        data = self.data()
        data["bundled"][0]["texts"] = ["libpng"]
        data["licenses"]["libpng"] = {"name": "libpng", "url": "", "file": "does-not-exist.txt"}
        self.assertTrue(al.missing_bundled_texts(data))
        self.assertTrue(al.check({"phototok": {"org.opencv:opencv": "5"}, "android-desktop": {}}, data))

    def test_render_reproduces_the_text_of_each_bundled_library(self):
        data = self.data()
        data["bundled"][0]["texts"] = ["libpng"]
        data["licenses"]["libpng"] = {"name": "libpng", "url": "", "file": "libpng.txt"}
        text = al.render("Frameduel", data, {"org.opencv:opencv": "5"}, {"Apache-2.0": "APACHE", "libpng": "LIBPNG TEXT"})
        self.assertIn("LIBPNG TEXT", text)

    def test_every_shipped_bundled_library_has_its_text_committed(self):
        self.assertEqual(al.missing_bundled_texts(al.load_components()), [])


class BundledNoticeTest(unittest.TestCase):
    """Attribution lines a licence asks for are printed, not only implied by the text."""

    def test_render_prints_the_owner_and_the_notice_of_a_bundled_library(self):
        data = BundledLicenseTextTest().data()
        data["bundled"][0].update({"texts": ["Apache-2.0"], "owner": "Copyright 2023 Arm Limited",
                                   "notice": "This software is based in part on the work of the IJG."})
        text = al.render("Frameduel", data, {"org.opencv:opencv": "5"}, {"Apache-2.0": "APACHE"})
        self.assertIn("Copyright 2023 Arm Limited", text)
        self.assertIn("This software is based in part on the work of the IJG.", text)

    def test_shipped_attribution_statements_are_present(self):
        data = al.load_components()
        by_name = {b["name"]: b for b in data["bundled"]}
        self.assertIn("Independent JPEG Group", by_name["libjpeg-turbo"]["notice"])
        self.assertIn("University of California, Berkeley", by_name["libtiff"]["notice"])
        self.assertEqual(by_name["KleidiCV"]["owner"],
                         "Copyright 2023-2026 Arm Limited and/or its affiliates <open-source-office@arm.com>")
        self.assertIn("2014-2015, NVIDIA Corporation", by_name["Carotene"]["owner"])

    def test_texts_are_complete(self):
        data = al.load_components()
        texts = al.licence_texts(data)
        self.assertIn("even if advised of the possibility of such damage", texts["OpenCV-BSD-3-Clause"])
        self.assertIn("Code generated by the Protocol Buffer compiler is owned by", texts["BSD-3-Clause"])
        self.assertIn("Intel Simplified Software License", texts["Intel-IPP"])
        self.assertIn("Permission is hereby granted, free of charge", texts["MIT"])
        self.assertIn("SIL OPEN FONT LICENSE Version 1.1", texts["OFL-1.1"])

    def test_zlib_is_a_system_library_and_not_listed_as_bundled(self):
        self.assertNotIn("zlib", [b["name"] for b in al.load_components()["bundled"]])


def _opencv_aar() -> "Path | None":
    version = re.search(r'^opencvAndroid = "([^"]+)"', (al.ANDROID_DIR / "gradle" / "libs.versions.toml").read_text(), re.M)
    root = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1" / "org.opencv" / "opencv"
    found = sorted(root.glob(f"{version.group(1)}/*/opencv-{version.group(1)}.aar")) if version else []
    return found[0] if found else None


class NativeLibraryListMatchesTheBinaryTest(unittest.TestCase):
    """Every library OpenCV's build summary says it compiled in must be in the bundled list."""

    ABIS = ("arm64-v8a", "x86_64")

    def test_every_3rdparty_dependency_of_each_shipped_abi_is_listed(self):
        aar = _opencv_aar()
        if aar is None:
            self.skipTest("the OpenCV AAR is not in the Gradle cache (run a Gradle build first); "
                          "the bundled-library list could not be compared with the binary")
        known = {name for item in al.load_components()["bundled"] for name in item.get("cmake", [])}
        with zipfile.ZipFile(aar) as zf:
            for abi in self.ABIS:
                with self.subTest(abi=abi):
                    blob = zf.read(f"jni/{abi}/libopencv_java5.so")
                    line = re.search(rb"3rdparty dependencies:[ \t]+([^\n\x00]+)", blob)
                    self.assertIsNotNone(line, f"{abi}: no '3rdparty dependencies:' line in the build summary")
                    names = line.group(1).decode().split()
                    self.assertEqual([n for n in names if n not in known], [],
                                     f"{abi}: built-in libraries missing from components.json 'bundled' (field 'cmake')")


class CommittedFilesTest(unittest.TestCase):
    """The shipped data: every entry names a licence with a text, every notice file is generated."""

    def test_every_entry_names_a_licence_whose_text_is_committed(self):
        data = al.load_components()
        for comp in data["components"]:
            with self.subTest(component=comp["id"]):
                self.assertIn(comp["license"], data["licenses"])
                self.assertTrue(comp["match"])
        for name, lic in data["licenses"].items():
            with self.subTest(license=name):
                self.assertTrue((al.LICENSES_DIR / lic["file"]).is_file())

    def test_the_gradle_tree_of_the_real_apps_parses_to_something(self):
        # shape check against the real report: a tree line with a group:artifact:version
        self.assertTrue(al.parse_classpath("+--- androidx.core:core:1.19.1\n"))

    def test_notice_files_are_where_each_app_reads_them(self):
        for module, path in al.NOTICE_FILES.items():
            with self.subTest(module=module):
                self.assertTrue(path.parent.is_dir(), path)
                self.assertEqual(path.name, "third_party_notices.txt")   # a valid R.raw name


if __name__ == "__main__":
    unittest.main()
