"""Expectations recorded before an action runs: counter reading, classification, photo binding."""

from __future__ import annotations

import dataclasses
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _explore_fakes import make_state, node  # noqa: E402

from lab.explore.actions import Action  # noqa: E402
from lab.explore.expectations import (  # noqa: E402
    STANDARD_FORBIDDEN_UI_NODES,
    Expectation,
    ExpectationRecorder,
    feed_bundles,
    read_counter,
)


def counter_state(text: str, node_id: str = "position_counter", files=None, extra=None):
    nodes = [node(node_id, [0, 0, 100, 50], text=text, interactive=False)] + list(extra or [])
    return make_state(nodes, files=files or ["a.jpg", "b.jpg"])


class ReadCounterTest(unittest.TestCase):
    def test_reads_index_and_count_from_the_counter_node(self) -> None:
        self.assertEqual(read_counter(counter_state("3 / 24")), (2, 24))
        self.assertEqual(read_counter(counter_state("3/24", node_id="viewer.counter")), (2, 24))

    def test_a_still_loading_feed_has_no_count(self) -> None:
        self.assertEqual(read_counter(counter_state("3 / 40+")), (2, None))

    def test_exif_text_is_not_a_counter(self) -> None:
        # "1/250" is a shutter speed in an EXIF card, not a position.
        state = make_state([node("exif_card", [0, 0, 100, 50], text="1/250", interactive=False)])
        self.assertIsNone(read_counter(state))

    def test_only_the_whole_text_of_the_counter_node_counts(self) -> None:
        self.assertIsNone(read_counter(counter_state("Photo 3 / 24 of the day")))

    def test_the_accessibility_name_is_the_fallback(self) -> None:
        state = make_state([node("position_counter", [0, 0, 10, 10], text="", name="5 / 9", interactive=False)])
        self.assertEqual(read_counter(state), (4, 9))

    def test_the_label_child_of_a_counter_container(self) -> None:
        # Jackpix (uiautomator, 2026-10-26): the tag is on the container, the text on its child.
        state = make_state([
            node("viewer.counter", [300, 700, 80, 30], interactive=False),
            node("#22", [310, 705, 60, 20], text="1 / 12", parent="viewer.counter", interactive=False),
            node("exif", [0, 0, 100, 20], text="2 / 3", interactive=False),
        ])
        self.assertEqual(read_counter(state), (0, 12))
        # A grandchild is not the label.
        state = make_state([node("viewer.counter", [300, 700, 80, 30], interactive=False),
                            node("box", [300, 700, 80, 30], parent="viewer.counter", interactive=False),
                            node("#23", [310, 705, 60, 20], text="1 / 12", parent="box", interactive=False)])
        self.assertIsNone(read_counter(state))

    def test_the_apps_own_position_wins_over_the_tree(self) -> None:
        # Blitzlens exports no counter node; its driver reads the position from state.json.
        state = make_state([node("slot_current", [0, 0, 10, 10], interactive=False)])
        self.assertIsNone(read_counter(state))
        state.position = (4, 9)
        self.assertEqual(read_counter(state), (4, 9))
        self.assertEqual(read_counter(dataclasses.replace(counter_state("1 / 3"), position=(0, 0))), (0, 0))


class NavigationTest(unittest.TestCase):
    def test_feed_position_comes_from_the_screen(self) -> None:
        rec = ExpectationRecorder("android-desktop")
        mid = rec.record(counter_state("3 / 24"), Action("key", params={"key": "Right"}))
        self.assertEqual(mid.expected_index_delta, 1)
        end = rec.record(counter_state("24 / 24"), Action("key", params={"key": "Right"}))
        self.assertEqual(end.expected_index_delta, 0)
        start = rec.record(counter_state("1 / 24"), Action("key", params={"key": "Left"}))
        self.assertEqual(start.expected_index_delta, 0)
        back = rec.record(counter_state("5 / 24"), Action("key", params={"key": "Left"}))
        self.assertEqual(back.expected_index_delta, -1)

    def test_explicit_position_overrides_the_screen(self) -> None:
        exp = ExpectationRecorder("android-desktop").record(counter_state("3 / 24"), Action("key", params={"key": "Right"}), feed_index=23, feed_count=24)
        self.assertEqual(exp.expected_index_delta, 0)

    def test_without_a_position_no_index_change_is_claimed(self) -> None:
        # Nothing on the screen says where the feed is, so the verifier could not check a claim;
        # 2026-10-26: 115 and 124 Blitzlens navigation steps per run passed with no oracle behind them.
        exp = ExpectationRecorder("linux-desktop").record(make_state([node("slot_current", [0, 0, 10, 10], interactive=False)]), Action("key", params={"key": "J"}))
        self.assertIsNone(exp.expected_index_delta)
        self.assertIn("index not checked", exp.description)

    def test_navigation_keys_are_the_ones_the_product_binds(self) -> None:
        def delta(product: str, key: str):
            return ExpectationRecorder(product).record(counter_state("3 / 24"), Action("key", params={"key": key})).expected_index_delta

        # Blitzlens (keyboard_router.py): Left/H/K back, Right/L/J on; Space zooms, Up/Down are unbound.
        self.assertEqual([delta("linux-desktop", k) for k in ("Left", "H", "K", "Right", "L", "J")], [-1, -1, -1, 1, 1, 1])
        for key in ("Space", "Up", "Down", "Page_Down"):
            self.assertIsNone(delta("linux-desktop", key), key)
        # Frameduel (SelectorScreen.kt): only the arrows; L opens Inspect.
        self.assertEqual([delta("android-desktop", k) for k in ("Left", "Right")], [-1, 1])
        for key in ("L", "J", "K", "H", "Up", "Down", "Space"):
            self.assertIsNone(delta("android-desktop", key), key)
        # Lensjet: arrows with Up/Down, and the Vim keys.
        self.assertEqual([delta("macos-desktop", k) for k in ("Up", "K", "Down", "J")], [-1, -1, 1, 1])
        # Jackpix handles no keys of its own.
        self.assertIsNone(delta("phototok", "Right"))

    def test_next_in_a_guide_is_not_feed_navigation(self) -> None:
        # Frameduel's coach overlay: "Next" turns the guide's page (2026-10-26, 11 taps in one run).
        rec = ExpectationRecorder("android-desktop")
        coach = rec.record(counter_state("3 / 24"), Action("tap", target_id="coach_overlay_next", label="Tap Next"))
        self.assertIsNone(coach.expected_index_delta)
        self.assertEqual(rec.record(counter_state("3 / 24"), Action("tap", target_id="btn_next", label="Tap Next")).expected_index_delta, 1)

    def test_an_unbound_key_expects_nothing_to_change(self) -> None:
        exp = ExpectationRecorder("linux-desktop").record(counter_state("3 / 24"), Action("key", params={"key": "Space"}))
        self.assertEqual(exp.expected_feed_count_delta, 0)
        self.assertEqual(exp.fs_expect, {"unchanged": True})

    def test_modified_arrow_keys_are_not_navigation(self) -> None:
        exp = ExpectationRecorder("android-desktop").record(counter_state("3 / 24"), Action("key", params={"key": "Right", "modifiers": ["alt"]}))
        self.assertIsNone(exp.expected_index_delta)

    def test_back_is_navigation_only_for_a_real_back_action(self) -> None:
        rec = ExpectationRecorder()
        tap = rec.record(counter_state("3 / 24"), Action("tap", target_id="btn_back_to_start"))
        self.assertIsNone(tap.expected_index_delta)
        real_back = rec.record(counter_state("3 / 24"), Action("back"))
        self.assertIsNone(real_back.expected_index_delta)
        self.assertEqual(real_back.fs_expect, {"unchanged": True})

    def test_words_not_substrings(self) -> None:
        rec = ExpectationRecorder()
        for label in ("Preview", "Remove tag", "Previously used"):
            exp = rec.record(counter_state("3 / 24"), Action("tap", label=label))
            self.assertIsNone(exp.expected_index_delta, label)
            self.assertEqual(exp.fs_expect, {"unchanged": True}, label)
        self.assertEqual(rec.record(counter_state("3 / 24"), Action("tap", target_id="btnNext")).expected_index_delta, 1)


class CullTest(unittest.TestCase):
    def shown(self, name: str):
        return counter_state("2 / 2", files=["a.jpg", "b.jpg"], extra=[node("exif_card", [0, 60, 200, 50], text=name, interactive=False)])

    def test_move_with_a_photo_on_screen_expects_its_files_to_move(self) -> None:
        exp = ExpectationRecorder("android-desktop").record(self.shown("b.jpg"), Action("key", params={"key": "m"}))
        self.assertEqual(exp.fs_expect, {"absent": ["b.jpg"], "present": ["Selection/b.jpg"]})
        self.assertEqual((exp.expected_index_delta, exp.expected_feed_count_delta), (0, -1))

    def test_without_an_identified_photo_the_files_are_checked_by_counts(self) -> None:
        rec = ExpectationRecorder("linux-desktop")
        state = counter_state("1 / 2", files=["a.jpg", "a.cr3", "b.jpg", "Selection/c.jpg"])
        cases = {
            "m": {"feed": -1, "selection": "+", "trash": 0},
            "c": {"feed": 0, "selection": "+", "trash": 0},
            "Delete": {"feed": -1, "selection": 0, "trash": "+"},
        }
        for key, counts in cases.items():
            exp = rec.record(state, Action("key", params={"key": key}))
            self.assertEqual(exp.fs_expect, {"counts": counts}, key)
            self.assertIsNone(exp.expected_feed_count_delta)
            self.assertNotIn("IMG_001", json.dumps(exp.to_dict()))
        undo = rec.record(state, Action("key", params={"key": "z", "modifiers": ["ctrl"]}))
        self.assertEqual(undo.fs_expect, {})

    def test_feed_bundles_count_companions_once(self) -> None:
        fs = {"files": [{"path": p} for p in ("a.jpg", "a.cr3", "sub/a.jpg", ".hidden", "Selection/b.jpg")]}
        self.assertEqual(feed_bundles(fs), {"a", "sub/a"})
        self.assertIsNone(feed_bundles(None))

    def test_an_empty_feed_expects_no_change(self) -> None:
        state = counter_state("0 / 0", files=["Selection/a.jpg"])
        exp = ExpectationRecorder("linux-desktop").record(state, Action("key", params={"key": "m"}))
        self.assertEqual(exp.fs_expect, {"counts": {"feed": 0, "selection": 0, "trash": 0}})

    def test_android_products_get_no_counts(self) -> None:
        for product in ("phototok", "android-desktop"):
            exp = ExpectationRecorder(product).record(counter_state("1 / 2"), Action("key", params={"key": "m"}))
            self.assertEqual(exp.fs_expect, {}, product)

    def test_without_a_snapshot_nothing_is_expected_of_the_files(self) -> None:
        state = counter_state("1 / 2")
        state.fs_snapshot = None
        exp = ExpectationRecorder("linux-desktop").record(state, Action("key", params={"key": "m"}))
        self.assertEqual(exp.fs_expect, {})

    def test_two_photo_names_on_screen_bind_nothing(self) -> None:
        state = counter_state("1 / 2", extra=[node("exif_card", [0, 60, 200, 50], text="a.jpg b.jpg", interactive=False)])
        exp = ExpectationRecorder().record(state, Action("key", params={"key": "m"}))
        self.assertEqual(exp.fs_expect, {})

    def test_delete_copy_and_undo(self) -> None:
        rec = ExpectationRecorder("android-desktop")
        state = self.shown("a.jpg")
        delete = rec.record(state, Action("key", params={"key": "Delete"}))
        self.assertEqual(delete.fs_expect, {"absent": ["a.jpg"]})  # Android snapshots list no Trash
        linux = ExpectationRecorder("linux-desktop").record(state, Action("key", params={"key": "Delete"}))
        self.assertEqual(linux.fs_expect, {"absent": ["a.jpg"], "trashed": ["a.jpg"]})
        copy = rec.record(state, Action("tap", target_id="btn_copy"))
        self.assertEqual(copy.fs_expect, {"present": ["a.jpg", "Selection/a.jpg"]})
        undo = rec.record(state, Action("key", params={"key": "z", "modifiers": ["ctrl"]}))
        self.assertEqual(undo.expected_feed_count_delta, 1)

    def test_a_key_is_classified_by_the_key_not_its_label(self) -> None:
        exp = ExpectationRecorder("android-desktop").record(counter_state("3 / 24"), Action("key", params={"key": "Tab"}, label="Key Tab (Next Focus)"))
        self.assertIsNone(exp.expected_index_delta)
        self.assertEqual(exp.fs_expect, {"unchanged": True})

    def test_modified_cull_keys_are_not_cull_actions(self) -> None:
        exp = ExpectationRecorder("android-desktop").record(self.shown("a.jpg"), Action("key", params={"key": "c", "modifiers": ["ctrl"]}))
        self.assertEqual(exp.fs_expect, {"unchanged": True})

    def test_jackpix_horizontal_swipes_depend_on_settings(self) -> None:
        rec = ExpectationRecorder("phototok")
        for direction, label in (("left", "Swipe Left (Discard/Delete)"), ("right", "Swipe Right (Keep/Selection)")):
            exp = rec.record(self.shown("a.jpg"), Action("swipe", target_id="viewer.photo", params={"direction": direction}, label=label))
            self.assertEqual(exp.fs_expect, {})
            self.assertIsNone(exp.expected_feed_count_delta)
        up = rec.record(counter_state("3 / 24"), Action("swipe", params={"direction": "up"}))
        self.assertEqual(up.expected_index_delta, 1)

    def test_unknown_cull_kind_raises(self) -> None:
        with self.assertRaises(ValueError):
            ExpectationRecorder().record_cull(self.shown("a.jpg"), Action("tap"), cull_action="shred")


class ExpectationRecordTest(unittest.TestCase):
    def test_standard_forbidden_nodes_are_always_present(self) -> None:
        exp = Expectation(Action("tap"), "x", forbidden_ui_nodes=["custom"])
        for node_id in STANDARD_FORBIDDEN_UI_NODES + ["custom"]:
            self.assertIn(node_id, exp.forbidden_ui_nodes)

    def test_round_trip(self) -> None:
        exp = Expectation(Action("key", params={"key": "m"}), "move", 0, -1, {"absent": ["a.jpg"]}, ["btn"])
        again = Expectation.from_dict(exp.to_dict())
        self.assertEqual(again.to_dict(), exp.to_dict())
        self.assertEqual(again.action, exp.action)


if __name__ == "__main__":
    unittest.main()
