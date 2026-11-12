"""Safe to format (plan 1.2): only for a removable source with the Selection on another volume."""

from pathlib import Path

from photo_selector_linux.core import volumes as v

SRC = Path("/run/media/alex/CARD/DCIM")
KEEP = Path("/home/alex/Pictures/Selection")
DEV = {SRC: 11, KEEP: 22}


def _verdict(source=SRC, selection=KEEP, queued=0, remaining=0, remote=False, removable=True, dev=None):
    table = {**DEV, **(dev or {})}
    return v.format_verdict(
        source, selection, queued, remaining, remote,
        removable=lambda p: removable, device_of=lambda p: table.get(p),
    )


def test_a_removable_source_with_the_selection_elsewhere_is_safe_to_format(tmp_path):
    keep = tmp_path / "Selection"
    keep.mkdir()
    assert _verdict(selection=keep, dev={keep: 22}) == v.VERDICT_SAFE


def test_a_selection_on_the_card_is_said_not_blessed(tmp_path):
    keep = tmp_path / "Selection"
    keep.mkdir()
    assert _verdict(selection=keep, dev={keep: 11}) == v.VERDICT_SAME_VOLUME


def test_a_selection_not_yet_created_is_judged_by_its_nearest_existing_parent(tmp_path):
    keep = tmp_path / "Selection"  # not created
    assert _verdict(selection=keep, dev={tmp_path: 22}) == v.VERDICT_SAFE


def test_no_verdict_while_photos_remain_work_is_queued_or_the_source_is_remote_or_fixed(tmp_path):
    keep = tmp_path / "Selection"
    keep.mkdir()
    dev = {keep: 22}
    assert _verdict(selection=keep, dev=dev, remaining=1) == v.VERDICT_NONE
    assert _verdict(selection=keep, dev=dev, queued=1) == v.VERDICT_NONE
    assert _verdict(selection=keep, dev=dev, remote=True) == v.VERDICT_NONE
    assert _verdict(selection=keep, dev=dev, removable=False) == v.VERDICT_NONE
    assert _verdict(selection=None, dev=dev) == v.VERDICT_NONE


def test_udisks_mount_points_and_the_kernel_flag_make_a_volume_removable():
    on_card = lambda p: Path("/run/media/alex/CARD")  # noqa: E731
    elsewhere = lambda p: Path("/home")  # noqa: E731
    assert v.is_removable_volume(SRC, mount_of=on_card, kernel_removable=lambda d: False, device_of=lambda p: 1)
    assert v.is_removable_volume(SRC, mount_of=elsewhere, kernel_removable=lambda d: True, device_of=lambda p: 1)
    assert not v.is_removable_volume(SRC, mount_of=elsewhere, kernel_removable=lambda d: False, device_of=lambda p: 1)
    assert not v.is_removable_volume(SRC, mount_of=elsewhere, kernel_removable=lambda d: True, device_of=lambda p: None)
