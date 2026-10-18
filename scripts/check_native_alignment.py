#!/usr/bin/env python3
"""Verify 16 KB ELF PT_LOAD segment alignment of Android native libraries (.so).

Google Play requires apps targeting Android 15+ (API 35+) to support 16 KB
memory page sizes on 64-bit architectures (arm64-v8a and x86_64). Every
ELF64 PT_LOAD segment in any packaged `lib/{arm64-v8a,x86_64}/*.so` must
declare `p_align >= 16384` (0x4000).

Accepts `.apk` archives, `.aab` bundles, directories (e.g. Gradle's
`merged_native_libs` output), or individual `.so` files.

For `.apk` files it also checks the *zip* alignment (finding Q-6): a `.so` stored uncompressed
must start at a 16 KB-aligned offset in the file, as `zipalign -P 16` produces, so the page can be
mapped straight from the APK. Compressed entries are extracted at install and are not affected.
Bundles (`.aab`) are exempt: bundletool aligns the APKs it generates.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import struct
import sys
from typing import Iterable, List, Sequence
import zipfile

ELF_MAGIC = b"\x7fELF"
ELFCLASS32 = 1
ELFCLASS64 = 2
ELFDATA2LSB = 1
ELFDATA2MSB = 2
PT_LOAD = 1
DEFAULT_MIN_ALIGN = 16384  # 16 KB (0x4000)
ZIP_ENTRY_ALIGN = 16384  # stored .so entries in an APK start at a multiple of this
ZIP_LOCAL_HEADER_SIZE = 30
TARGET_ABIS = ("arm64-v8a", "x86_64")

ARCHIVE_SO_PATTERN = re.compile(
    r"(?:^|/)(?:base/)?lib/(?P<abi>arm64-v8a|x86_64)/(?P<name>[^/]+\.so)$"
)


@dataclass(frozen=True)
class LoadSegmentAlignment:
    segment_index: int
    p_align: int


@dataclass(frozen=True)
class LibraryAlignmentReport:
    source: str
    abi: str
    library: str
    load_segments: List[LoadSegmentAlignment]
    error: str | None = None
    zip_problem: str | None = None

    @property
    def min_alignment(self) -> int | None:
        if not self.load_segments:
            return None
        return min(seg.p_align for seg in self.load_segments)

    def passes(self, required_alignment: int = DEFAULT_MIN_ALIGN) -> bool:
        if self.error is not None or self.zip_problem is not None or not self.load_segments:
            return False
        return all(seg.p_align >= required_alignment for seg in self.load_segments)


def parse_elf_load_alignments(data: bytes) -> List[LoadSegmentAlignment]:
    """Parse ELF program headers and return PT_LOAD segment alignments."""
    if len(data) < 64 or data[:4] != ELF_MAGIC:
        raise ValueError("not a valid ELF binary (bad magic or truncated header)")

    ei_class = data[4]
    ei_data = data[5]
    if ei_data == ELFDATA2LSB:
        endian = "<"
    elif ei_data == ELFDATA2MSB:
        endian = ">"
    else:
        raise ValueError(f"unsupported ELF data encoding: {ei_data}")

    if ei_class == ELFCLASS64:
        e_phoff = struct.unpack_from(f"{endian}Q", data, 32)[0]
        e_phentsize, e_phnum = struct.unpack_from(f"{endian}HH", data, 54)
        min_phentsize = 56
    elif ei_class == ELFCLASS32:
        e_phoff = struct.unpack_from(f"{endian}I", data, 28)[0]
        e_phentsize, e_phnum = struct.unpack_from(f"{endian}HH", data, 42)
        min_phentsize = 32
    else:
        raise ValueError(f"unsupported ELF class: {ei_class}")

    if e_phnum == 0 or e_phentsize < min_phentsize:
        raise ValueError(
            f"invalid ELF program header table (e_phnum={e_phnum}, e_phentsize={e_phentsize})"
        )

    end_offset = e_phoff + e_phentsize * e_phnum
    if end_offset > len(data):
        raise ValueError("ELF program header table extends past end of file")

    segments: List[LoadSegmentAlignment] = []
    for idx in range(e_phnum):
        ph_off = e_phoff + idx * e_phentsize
        p_type = struct.unpack_from(f"{endian}I", data, ph_off)[0]
        if p_type != PT_LOAD:
            continue
        if ei_class == ELFCLASS64:
            p_align = struct.unpack_from(f"{endian}Q", data, ph_off + 48)[0]
        else:
            p_align = struct.unpack_from(f"{endian}I", data, ph_off + 28)[0]
        segments.append(LoadSegmentAlignment(segment_index=idx, p_align=p_align))

    if not segments:
        raise ValueError("ELF binary contains no PT_LOAD segments")

    return segments


def inspect_elf_bytes(source: str, abi: str, library: str, data: bytes) -> LibraryAlignmentReport:
    try:
        segments = parse_elf_load_alignments(data)
        return LibraryAlignmentReport(
            source=source,
            abi=abi,
            library=library,
            load_segments=segments,
        )
    except ValueError as exc:
        return LibraryAlignmentReport(
            source=source,
            abi=abi,
            library=library,
            load_segments=[],
            error=str(exc),
        )


def zip_entry_alignment_problem(archive_path: Path, info: zipfile.ZipInfo) -> str | None:
    """Why a stored entry cannot be mapped from the APK at a 16 KB boundary, or None."""
    if info.compress_type != zipfile.ZIP_STORED:
        return None
    with archive_path.open("rb") as handle:
        handle.seek(info.header_offset)
        header = handle.read(ZIP_LOCAL_HEADER_SIZE)
    if len(header) < ZIP_LOCAL_HEADER_SIZE or header[:4] != b"PK\x03\x04":
        return "unreadable zip local header"
    name_length, extra_length = struct.unpack_from("<HH", header, 26)
    data_offset = info.header_offset + ZIP_LOCAL_HEADER_SIZE + name_length + extra_length
    if data_offset % ZIP_ENTRY_ALIGN:
        return (
            f"stored at zip offset {data_offset}, not a multiple of {ZIP_ENTRY_ALIGN} "
            f"(align with `zipalign -P 16 -f 4`)"
        )
    return None


def inspect_archive(archive_path: Path) -> List[LibraryAlignmentReport]:
    reports: List[LibraryAlignmentReport] = []
    check_zip = archive_path.suffix.lower() == ".apk"
    with zipfile.ZipFile(archive_path, "r") as zf:
        for entry in sorted(zf.namelist()):
            match = ARCHIVE_SO_PATTERN.search(entry)
            if not match:
                continue
            abi = match.group("abi")
            name = match.group("name")
            data = zf.read(entry)
            report = inspect_elf_bytes(
                source=str(archive_path),
                abi=abi,
                library=f"{abi}/{name}",
                data=data,
            )
            if check_zip:
                problem = zip_entry_alignment_problem(archive_path, zf.getinfo(entry))
                if problem is not None:
                    report = LibraryAlignmentReport(
                        source=report.source,
                        abi=report.abi,
                        library=report.library,
                        load_segments=report.load_segments,
                        error=report.error,
                        zip_problem=problem,
                    )
            reports.append(report)
    return reports


def _infer_abi_from_path(so_path: Path) -> str | None:
    parts = so_path.parts
    for abi in TARGET_ABIS:
        if abi in parts:
            return abi
    return None


def inspect_directory(directory: Path) -> List[LibraryAlignmentReport]:
    reports: List[LibraryAlignmentReport] = []
    for so_path in sorted(directory.rglob("*.so")):
        if not so_path.is_file():
            continue
        abi = _infer_abi_from_path(so_path)
        if abi is None:
            continue
        reports.append(
            inspect_elf_bytes(
                source=str(directory),
                abi=abi,
                library=f"{abi}/{so_path.name}",
                data=so_path.read_bytes(),
            )
        )
    return reports


def inspect_target(path: Path) -> List[LibraryAlignmentReport]:
    if not path.exists():
        raise FileNotFoundError(f"path does not exist: {path}")
    if path.is_dir():
        return inspect_directory(path)
    suffix = path.suffix.lower()
    if suffix in (".apk", ".aab", ".zip"):
        return inspect_archive(path)
    if suffix == ".so":
        abi = _infer_abi_from_path(path) or "unknown"
        return [
            inspect_elf_bytes(
                source=str(path),
                abi=abi,
                library=path.name if abi == "unknown" else f"{abi}/{path.name}",
                data=path.read_bytes(),
            )
        ]
    raise ValueError(f"unsupported target type: {path}")


def check_targets(
    targets: Iterable[Path],
    min_align: int = DEFAULT_MIN_ALIGN,
) -> tuple[bool, List[LibraryAlignmentReport]]:
    all_reports: List[LibraryAlignmentReport] = []
    for target in targets:
        all_reports.extend(inspect_target(target))
    ok = all(report.passes(min_align) for report in all_reports)
    return ok, all_reports


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify 16 KB ELF PT_LOAD alignment of 64-bit Android native libraries (.so)."
    )
    parser.add_argument(
        "targets",
        nargs="+",
        type=Path,
        help="APK/AAB archives, merged_native_libs directories, or .so files to inspect.",
    )
    parser.add_argument(
        "--min-align",
        type=int,
        default=DEFAULT_MIN_ALIGN,
        help=f"Minimum required PT_LOAD p_align in bytes (default: {DEFAULT_MIN_ALIGN}).",
    )
    args = parser.parse_args(argv)

    try:
        ok, reports = check_targets(args.targets, min_align=args.min_align)
    except (FileNotFoundError, ValueError, zipfile.BadZipFile) as exc:
        print(f"✘ Native alignment check error: {exc}", file=sys.stderr)
        return 1

    failures = [r for r in reports if not r.passes(args.min_align)]
    if failures:
        for failure in failures:
            if failure.zip_problem:
                print(
                    f"✘ {failure.source} [{failure.library}]: {failure.zip_problem}",
                    file=sys.stderr,
                )
            if failure.error:
                print(
                    f"✘ {failure.source} [{failure.library}]: {failure.error}",
                    file=sys.stderr,
                )
            elif any(seg.p_align < args.min_align for seg in failure.load_segments):
                bad_segs = ", ".join(
                    f"seg#{s.segment_index}=0x{s.p_align:x} ({s.p_align} B)"
                    for s in failure.load_segments
                    if s.p_align < args.min_align
                )
                print(
                    f"✘ {failure.source} [{failure.library}]: PT_LOAD alignment < "
                    f"{args.min_align} B (0x{args.min_align:x}): {bad_segs}",
                    file=sys.stderr,
                )
        print(
            f"✘ {len(failures)}/{len(reports)} native library(ies) failed "
            f"{args.min_align}-byte (16 KB) page-size alignment.",
            file=sys.stderr,
        )
        return 1

    print(
        f"✔ 16 KB native alignment verified: {len(reports)} 64-bit native library(ies) "
        f"across {len(args.targets)} target(s) have PT_LOAD p_align >= {args.min_align} B."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
