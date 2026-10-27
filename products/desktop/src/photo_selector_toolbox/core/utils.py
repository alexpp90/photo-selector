import shutil
import sys
import os
import urllib.request
from urllib.error import URLError
from urllib.parse import urlparse, unquote
import logging
import functools
from pathlib import Path
from collections import Counter
from typing import List, Set, Tuple, Optional
from PIL import Image, ImageOps
import numpy as np

logger = logging.getLogger(__name__)

# Box the Review tab decodes its triplet previews into (and caches at). The
# format conformance test displays every supported format through
# `load_image_preview` at this size.
REVIEW_PREVIEW_SIZE: Tuple[int, int] = (1200, 900)


def _is_hash_similar(prev_res, next_res, hash_keys: List[str], threshold: int) -> bool:
    """Helper to check if two scan results are similar based on image hashes."""
    if not prev_res or not next_res:
        return False

    h1_val, h2_val = None, None
    for key in hash_keys:
        if h1_val is None:
            h1_val = prev_res.scores.get(key)
        if h2_val is None:
            h2_val = next_res.scores.get(key)

    if h1_val is not None and h2_val is not None:
        try:
            h1 = int(h1_val, 16) if isinstance(h1_val, str) else int(h1_val)
            h2 = int(h2_val, 16) if isinstance(h2_val, str) else int(h2_val)
            dist = (h1 ^ h2).bit_count()
            return dist <= threshold
        except (ValueError, TypeError):
            pass

    return False


try:
    import rawpy
except ImportError:
    rawpy = None


def resolve_path(path_str: str | Path) -> Path:
    """
    Resolves a path string to a pathlib.Path object.
    Supports resolving smb:// URLs to local mount points on Linux (GVFS) and macOS.

    Args:
        path_str: The input path string or Path object (e.g., '/tmp/test' or 'smb://server/share/path')

    Returns:
        Path object pointing to the local file system location.
    """
    if isinstance(path_str, Path):
        path_str = str(path_str)
    # Check if it looks like an SMB URL
    if path_str.startswith("smb://"):
        # Parse the URL
        parsed = urlparse(path_str)
        server = parsed.hostname
        # Path usually comes as '/share/folder/file'
        # We need to strip the leading slash to split easily, but keep it for logic
        full_path = parsed.path
        if not full_path:
            return Path(path_str)  # Should probably just return as is if malformed

        # Unquote to handle spaces (%20)
        full_path_decoded = unquote(full_path)

        # Use posixpath.normpath to logically resolve path components
        # cross-platform since URLs always use forward slashes
        import posixpath
        normalized_path = posixpath.normpath(full_path_decoded)

        # Ensure the normalized path is absolute (starts with /) before splitting
        if not normalized_path.startswith("/"):
            normalized_path = "/" + normalized_path

        parts = normalized_path.strip("/").split("/", 1)

        # If there are no parts, we don't have a valid share name
        if not parts or not parts[0]:
            return Path(path_str)

        share_name = parts[0]
        remainder = parts[1] if len(parts) > 1 else ""

        if sys.platform == "linux":
            # GVFS mount point pattern: /run/user/<uid>/gvfs/smb-share:server=<server>,share=<share>/<remainder>
            try:
                uid = os.getuid()
                gvfs_root = Path(f"/run/user/{uid}/gvfs")

                # Construct the directory name.
                # Note: commas in server or share names might need escaping in theory,
                # but standard GVFS behavior for simple names is server=<server>,share=<share>
                # We assume standard behavior.
                mount_dir_name = f"smb-share:server={server},share={share_name}"

                potential_path = gvfs_root / mount_dir_name
                if remainder:
                    potential_path = potential_path / remainder

                return potential_path
            except AttributeError:
                # os.getuid might not be available on Windows, but we are in linux block
                pass

        elif sys.platform == "darwin":
            # macOS mount point pattern: /Volumes/<share>/<remainder>
            # macOS typically mounts using just the share name in /Volumes
            potential_path = Path(f"/Volumes/{share_name}")
            if remainder:
                potential_path = potential_path / remainder
            return potential_path

    # Default: treat as local path
    return Path(path_str)


@functools.lru_cache()
def get_exiftool_path() -> str | None:
    """
    Returns the path to the exiftool executable.

    It checks:
    1. System PATH.
    2. A 'bin' directory adjacent to the executable (bundled).
    3. The PyInstaller temp directory (sys._MEIPASS).
    """
    # Check system PATH first
    exiftool_path = shutil.which("exiftool")
    if exiftool_path:
        return exiftool_path

    # Check for bundled executable
    # If running as a PyInstaller bundle
    if getattr(sys, "frozen", False):
        base_path = Path(sys._MEIPASS)
    else:
        # If running from source, check a 'bin' folder in the package
        base_path = Path(__file__).parent / "bin"

    # Determine executable name based on OS
    exe_name = "exiftool.exe" if sys.platform == "win32" else "exiftool"

    potential_path = base_path / exe_name

    if potential_path.exists():
        return str(potential_path)

    # Also check if it's just 'exiftool' without extension on Linux/Mac
    potential_path_no_ext = base_path / "exiftool"
    if potential_path_no_ext.exists():
        return str(potential_path_no_ext)

    return None


def _get_focal_length_groups(
    unique_fls: List[float], threshold: float
) -> List[List[float]]:
    groups = []
    if not unique_fls:
        return groups

    current_group = [unique_fls[0]]

    for fl in unique_fls[1:]:
        if (fl - current_group[0]) / current_group[0] <= threshold:
            current_group.append(fl)
        else:
            groups.append(current_group)
            current_group = [fl]
    groups.append(current_group)
    return groups


def _find_best_threshold(unique_fls: List[float], max_buckets: int) -> float:
    low = 0.0
    high = 2.0  # Allow up to 200% difference
    best_threshold = high

    for _ in range(20):
        mid = (low + high) / 2
        groups = _get_focal_length_groups(unique_fls, mid)
        if len(groups) <= max_buckets:
            best_threshold = mid
            high = mid
        else:
            low = mid

    return best_threshold


def _format_focal_length_label(min_fl: float, max_fl: float) -> str:
    def fmt(v):
        return f"{int(v)}" if float(v).is_integer() else f"{v:.1f}".rstrip("0").rstrip(".")

    if min_fl == max_fl:
        return f"{fmt(min_fl)} mm"

    if fmt(min_fl) == fmt(max_fl):
        return f"{fmt(min_fl)} mm"

    return f"{fmt(min_fl)}-{fmt(max_fl)} mm"


def _generate_exact_buckets(
    unique_fls: List[float], counts: Counter
) -> List[Tuple[str, int, float]]:
    """Generates buckets with exact focal length values when no aggregation is needed."""
    result = []
    for fl in unique_fls:
        label = f"{int(fl)} mm" if float(fl).is_integer() else f"{fl:.1f} mm"
        result.append((label, counts[fl], fl))
    return result


def _generate_aggregated_buckets(
    unique_fls: List[float], counts: Counter, max_buckets: int
) -> List[Tuple[str, int, float]]:
    """Generates aggregated buckets when there are too many unique focal lengths."""
    best_threshold = _find_best_threshold(unique_fls, max_buckets)
    final_groups = _get_focal_length_groups(unique_fls, best_threshold)

    result = []
    for group in final_groups:
        group_count = sum(counts[fl] for fl in group)
        min_fl = min(group)
        max_fl = max(group)
        label = _format_focal_length_label(min_fl, max_fl)

        result.append((label, group_count, min_fl))

    return result


def aggregate_focal_lengths(
    focal_lengths: List[float], max_buckets: int = 25
) -> List[Tuple[str, int, float]]:
    """
    Aggregates focal lengths into buckets based on percentage difference.

    Args:
        focal_lengths: List of focal length values.
        max_buckets: Maximum number of buckets to create.

    Returns:
        List of tuples (label, count, sort_key).
        - label: String representation (e.g., "50 mm" or "24-28 mm").
        - count: Number of items in this bucket.
        - sort_key: The representative value for sorting (e.g., min value of the bucket).
    """
    if not focal_lengths:
        return []

    # Filter out focal lengths <= 0
    valid_focal_lengths = [fl for fl in focal_lengths if fl > 0]
    if not valid_focal_lengths:
        return []

    # Count exact values first
    counts = Counter(valid_focal_lengths)

    unique_fls = sorted(counts.keys())

    if len(unique_fls) <= max_buckets:
        # No aggregation needed
        return _generate_exact_buckets(unique_fls, counts)

    return _generate_aggregated_buckets(unique_fls, counts, max_buckets)


def apply_exif_orientation(img: Image.Image) -> Image.Image:
    """
    Rotates/flips an image in place according to its EXIF orientation tag.

    Camera JPEG/HEIC files and the JPEG thumbnails embedded in RAW files are
    stored in the sensor's native orientation with an `Orientation` tag (1-8)
    describing how a viewer must rotate them. Ignoring the tag renders every
    portrait shot as landscape.

    `ImageOps.exif_transpose(..., in_place=True)` mutates the image and returns
    `None`; it also strips the orientation tag from the result, so no consumer
    downstream can rotate the same image a second time. Images without EXIF
    (e.g. anything built by `Image.fromarray`, such as a rawpy postprocess
    result, which libraw has already flipped via its default `user_flip=-1`)
    are left untouched.

    Args:
        img: The image to orient.

    Returns:
        The oriented image (the same object that was passed in).
    """
    try:
        result = ImageOps.exif_transpose(img, in_place=True)
    except Exception as e:
        # A truncated/corrupt EXIF block must never cost us the preview.
        logger.debug("Could not apply EXIF orientation: %s", e)
        return img
    # in_place=True returns None; tolerate a copy-returning implementation too.
    return result if result is not None else img


def load_image_preview(
    path: Path, max_size: Tuple[int, int] = (150, 150), full_res: bool = False
) -> Optional[Image.Image]:
    """
    Loads an image for preview, handling both standard formats (via Pillow)
    and RAW formats (via rawpy). Resizes the image to fit within max_size.

    The EXIF orientation tag is always applied (see `apply_exif_orientation`)
    before the image is converted or resized, so every display path agrees on
    which way up a photograph is.

    Args:
        path: Path to the image file.
        max_size: Tuple (width, height) for thumbnail size.
        full_res: If True, loads the full resolution image (ignoring max_size).

    Returns:
        PIL Image object or None if loading fails.
    """
    try:
        from photo_selector_toolbox.exif.reader import RAW_EXTENSIONS

        ext = path.suffix.lower()
        img = None

        # Try rawpy for known RAW extensions
        if (ext in RAW_EXTENSIONS or ext in {".tif", ".tiff"}) and rawpy is not None:
            try:
                with rawpy.imread(str(path)) as raw:
                    # Try to extract embedded thumbnail for preview if not full_res
                    if not full_res:
                        try:
                            thumb = raw.extract_thumb()
                            if (
                                hasattr(rawpy, "ThumbFormat")
                                and thumb.format == rawpy.ThumbFormat.JPEG
                            ):
                                import io

                                img = Image.open(io.BytesIO(thumb.data))
                            elif (
                                hasattr(rawpy, "ThumbFormat")
                                and thumb.format == rawpy.ThumbFormat.BITMAP
                            ):
                                img = Image.fromarray(thumb.data)
                        except Exception as e:
                            logger.debug(
                                "Failed to extract embedded thumbnail for %s: %s",
                                path.name,
                                e,
                            )

                    # An embedded preview smaller than the requested box (and than
                    # the sensor) would be shown upscaled: phone DNGs embed ~672 px
                    # previews. Decode the RAW instead in that case only.
                    if img is not None:
                        try:
                            sensor_long = max(int(raw.sizes.width), int(raw.sizes.height))
                        except (AttributeError, TypeError, ValueError):
                            sensor_long = 0
                        if max(img.size) < min(max(max_size), sensor_long):
                            img.close()
                            img = None

                    # Fallback to standard postprocess if thumbnail extraction failed,
                    # the preview was too small, or full_res requested
                    if img is None:
                        rgb = raw.postprocess(
                            use_camera_wb=True, bright=1.0, half_size=not full_res
                        )
                        img = Image.fromarray(rgb).copy()
                        del rgb
            except (rawpy.LibRawError, OSError, ValueError) as e:
                # Catch common rawpy failures and fall through to Pillow
                logger.debug("rawpy failed to load %s: %s", path, e)

        # Fallback to Pillow if not RAW or rawpy failed
        if img is None:
            img = Image.open(path)

        # Orientation must be applied BEFORE thumbnailing: fitting an unrotated
        # portrait into the max_size box sizes it as if it were landscape, so a
        # later rotation would yield the wrong box. One call site here covers
        # the Pillow path, the RAW embedded-thumbnail path and the rawpy
        # postprocess path (a no-op for the latter, which libraw already
        # flipped).
        img = apply_exif_orientation(img)

        # REQUIRED — prevents I;16 crashes in ImageTk. Skipped when the decoded
        # image is already RGB, since convert() would otherwise copy the whole
        # (possibly full-resolution) buffer for nothing.
        if img.mode != "RGB":
            img = img.convert("RGB")

        # Resize (thumbnail modifies in-place)
        if not full_res:
            img.thumbnail(max_size)
        return img

    except (Image.UnidentifiedImageError, OSError, ValueError) as e:
        # Catch common image loading/processing errors
        logger.warning(f"Failed to load image preview for {path}: {e}")
        return None


@functools.lru_cache(maxsize=1)
def get_excluded_folder_names() -> frozenset:
    """
    Returns a frozenset of lowercased folder names that should be excluded from scanning.
    Loads the custom selection folder from the configuration.
    Cached to ensure it is strictly pre-calculated once for optimized O(1) lookups.
    """
    excluded_names = {"selection", "selected", "phototok_selection", "phototok_leftswipe"}
    try:
        from photo_selector_toolbox.core.config import load_config

        config = load_config()
        custom_folder = config.get("selection_folder", "Selection")
        custom_path = Path(custom_folder)
        if not custom_path.is_absolute():
            excluded_names.add(custom_path.name.lower())
    except Exception:
        pass
    return frozenset(excluded_names)


def scan_image_files(
    root: str | Path, excluded_names: Optional[Set[str]] = None
) -> List[Path]:
    """Recursively lists the supported photos under *root* (unsorted).

    The one scanner behind the CLI, the Statistics tab and the Review tab:
    extensions are matched case-insensitively against
    `exif.reader.SUPPORTED_EXTENSIONS`, AppleDouble ``._*`` files are skipped and
    excluded folders (Selection, ...) are pruned without being descended into.

    Args:
        root: Folder to scan.
        excluded_names: Lower-cased folder names to prune; defaults to
            `get_excluded_folder_names()`.
    """
    from photo_selector_toolbox.exif.reader import SUPPORTED_EXTENSIONS

    if excluded_names is None:
        excluded_names = get_excluded_folder_names()
    # Same rule as `exif.reader.is_supported_image_name`, inlined with the
    # tuple computed once per scan rather than once per file.
    exts = tuple(SUPPORTED_EXTENSIONS)
    files: List[Path] = []
    for dirpath, dirnames, filenames in os.walk(root):
        # Prune excluded directories in place
        dirnames[:] = [d for d in dirnames if d.lower() not in excluded_names]
        dp = Path(dirpath)
        for f in filenames:
            if not f.startswith("._") and f.lower().endswith(exts):
                files.append(dp / f)
    return files


def is_excluded_subfolder(
    file_path: Path,
    root_path: Path,
    excluded_names: Optional[Set[str]] = None,
) -> bool:
    """
    Checks if a file_path is located inside a subfolder named 'Selection' or 'Selected'
    under root_path, to exclude it from scanning.
    But if the root_path itself is named 'Selection' or 'Selected' (e.g. specifically selected),
    it is not excluded.

    Args:
        file_path: The file to check.
        root_path: The root scanning directory.
        excluded_names: Optional pre-computed set of lowercase excluded folder names.
                        If None, the config will be loaded (slow if called per-file).
    """
    try:
        relative = file_path.relative_to(root_path)
        if excluded_names is None:
            excluded_names = get_excluded_folder_names()

        # Check all parts of the relative path except the last one (the filename)
        for part in relative.parts[:-1]:
            if part.lower() in excluded_names:
                return True
    except ValueError:
        pass
    return False


def calculate_dhash(image: Image.Image, hash_size: int = 8) -> int:
    """
    Calculates the difference hash (dHash) of a PIL Image using NumPy
    for vectorized bit comparison (5-10× faster than Python loops).

    Args:
        image: The PIL Image to hash.
        hash_size: The grid size. The image will be resized to (hash_size + 1) x hash_size.

    Returns:
        The integer hash representing the image.
    """
    # Resize to (hash_size + 1) x hash_size, convert to grayscale (L)
    resized = image.resize(
        (hash_size + 1, hash_size), Image.Resampling.BILINEAR
    ).convert("L")
    pixels = np.array(resized)

    # Compare adjacent columns: diff[row, col] = pixel[row, col] > pixel[row, col+1]
    diff = pixels[:, :-1] > pixels[:, 1:]

    # Pack boolean array into bits and convert to integer
    flat = diff.flatten()
    total_bits = hash_size * hash_size
    packed = np.packbits(flat)
    # np.packbits pads to the next byte boundary; shift out any extra bits
    extra_bits = len(packed) * 8 - total_bits
    result = int.from_bytes(packed.tobytes(), "big") >> extra_bits
    return result


def group_files_by_similarity(
    files: List[Path],
    files_map,
    threshold: int = 10,
    group_level: str = "legacy",
) -> List[List[Path]]:
    """
    Groups consecutive image files based on similarity criteria.

    Args:
        files: A list of Paths sorted alphabetically/chronologically.
        files_map: A dict mapping Path to ScanResult.
        threshold: Default threshold used for backward compatibility or fast similarity.
        group_level: One of "Time & Filename", "Time + Fast Similarity", "Detailed Similarity".

    Returns:
        A list of groups, where each group is a list of Paths.
    """
    if not files:
        return []

    import os

    def get_name_prefix(name: str) -> str:
        stem = name.rsplit(".", 1)[0]
        # Using native rstrip avoids regex compilation overhead in tight loops
        return stem.rstrip("0123456789")

    def get_mtime(p: Path) -> float:
        try:
            return os.stat(p).st_mtime
        except OSError:
            return 0.0

    groups = []
    prev_file = files[0]
    current_group = [prev_file]

    # OPTIMIZATION: Track file metadata iteratively to avoid O(N) dict comprehension overhead.
    t1 = get_mtime(prev_file)
    pref1 = get_name_prefix(prev_file.name)

    for next_file in files[1:]:
        t2 = get_mtime(next_file)
        pref2 = get_name_prefix(next_file.name)

        similar = False

        if group_level == "Time & Filename":
            # Level 1: Time diff <= 30.0s and matching prefix
            if abs(t2 - t1) <= 30.0 and pref1 == pref2:
                similar = True

        elif group_level == "Time + Fast Similarity":
            # Level 2: Time diff <= 30.0s, matching prefix, and dHash 8x8 distance <= 10
            if abs(t2 - t1) <= 30.0 and pref1 == pref2:
                prev_res = files_map.get(prev_file)
                next_res = files_map.get(next_file)
                if _is_hash_similar(prev_res, next_res, ["dhash_8", "dhash"], threshold):
                    similar = True

        elif group_level == "Detailed Similarity":
            # Level 3: Time diff <= 30.0s, matching prefix, and detailed dHash 16x16 distance <= 24
            if abs(t2 - t1) <= 30.0 and pref1 == pref2:
                prev_res = files_map.get(prev_file)
                next_res = files_map.get(next_file)
                if _is_hash_similar(prev_res, next_res, ["dhash_16"], 24):
                    similar = True
        else:
            # Fallback to legacy behaviour if an unknown level is specified
            prev_res = files_map.get(prev_file)
            next_res = files_map.get(next_file)
            if _is_hash_similar(prev_res, next_res, ["dhash"], threshold):
                similar = True

        if similar:
            current_group.append(next_file)
        else:
            groups.append(current_group)
            current_group = [next_file]

        prev_file = next_file
        t1 = t2
        pref1 = pref2

    groups.append(current_group)
    return groups


def select_representative(group_files: List[Path], files_map) -> Path:
    """
    Selects the best representative image in a group.
    Prefers the one with the highest sharpness score, falling back to the first file in the group.

    Args:
        group_files: List of Paths in the group.
        files_map: Dict mapping Path to ScanResult.

    Returns:
        The Path of the selected representative image.
    """
    if not group_files:
        raise ValueError("Cannot select representative from empty group")

    best_path = group_files[0]
    best_score = -1.0

    for path in group_files:
        res = files_map.get(path)
        if res:
            score_val = res.scores.get("sharpness")
            if isinstance(score_val, (int, float)):
                if float(score_val) > best_score:
                    best_score = float(score_val)
                    best_path = path

    return best_path


@functools.lru_cache(maxsize=8)
def create_placeholder_image(width: int, height: int, text: str) -> Image.Image:
    """
    Dynamically generates a modern, beautiful dark-gradient placeholder image
    with a camera icon outline and text below it.

    Cached with LRU (maxsize=8) since placeholders are generated frequently
    during navigation but use a small set of distinct (width, height, text) combos.
    """
    from PIL import ImageDraw

    # Create base image with Zinc-900 base color (24, 24, 27)
    img = Image.new("RGB", (width, height), color=(24, 24, 27))
    draw = ImageDraw.Draw(img)

    # Draw simple gradient by interpolation
    # Background gradient: #1E1E24 (dark charcoal) to #121214 (deep charcoal)
    c1 = (30, 30, 36)
    c2 = (18, 18, 20)
    for y in range(height):
        ratio = y / max(1, height - 1)
        r = int(c1[0] * (1 - ratio) + c2[0] * ratio)
        g = int(c1[1] * (1 - ratio) + c2[1] * ratio)
        b = int(c1[2] * (1 - ratio) + c2[2] * ratio)
        draw.line([(0, y), (width, y)], fill=(r, g, b))

    # Draw subtle inner border
    border_color = (63, 63, 70)  # Zinc-700
    draw.rectangle([(5, 5), (width - 6, height - 6)], outline=border_color, width=1)

    # Draw simple camera icon in the center
    # Calculate center coordinates
    cx = width // 2
    cy = height // 2 - 20  # slightly shifted up to make room for text below

    # Camera body dimensions
    cam_w, cam_h = 60, 40

    # Camera lens dimensions
    lens_r = 16

    # Draw camera body
    # Base rectangle
    draw.rectangle(
        [(cx - cam_w // 2, cy - cam_h // 2), (cx + cam_w // 2, cy + cam_h // 2)],
        outline=(161, 161, 170),  # Zinc-400
        width=3,
    )
    # Camera flash/top prism shape
    draw.polygon(
        [
            (cx - 15, cy - cam_h // 2),
            (cx - 10, cy - cam_h // 2 - 8),
            (cx + 10, cy - cam_h // 2 - 8),
            (cx + 15, cy - cam_h // 2),
        ],
        outline=(161, 161, 170),
        fill=(30, 30, 36),
        width=3,
    )
    # Camera lens (circle)
    draw.ellipse(
        [(cx - lens_r, cy - lens_r), (cx + lens_r, cy + lens_r)],
        outline=(161, 161, 170),
        width=3,
    )

    # Centering text helper
    try:
        if hasattr(draw, "textbbox"):
            bbox = draw.textbbox((0, 0), text)
            tw = bbox[2] - bbox[0]
        elif hasattr(draw, "textsize"):
            tw, _ = draw.textsize(text)
        else:
            tw = len(text) * 6
    except Exception:
        tw = len(text) * 6

    tx = cx - tw // 2
    ty = cy + cam_h // 2 + 15
    draw.text((tx, ty), text, fill=(244, 244, 245))

    # Return a copy so the cached original is never mutated
    return img.copy()

class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """
    A custom URL handler that prevents following HTTP redirects.
    This is critical for preventing SSRF bypasses where an attacker
    redirects an initially safe request to a prohibited internal IP.
    """
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise URLError("SSRF Protection: Redirects are not allowed.")

import socket
import http.client

class SafeSSRFConnection(http.client.HTTPConnection):
    def __init__(self, *args, safe_ips=None, **kwargs):
        self.safe_ips = safe_ips
        super().__init__(*args, **kwargs)

    def connect(self):
        if self.safe_ips:
            last_err = None
            for safe_ip in self.safe_ips:
                try:
                    self.sock = self._create_connection(
                        (safe_ip, self.port), self.timeout, self.source_address)
                    self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    break
                except OSError as err:
                    last_err = err
                    if self.sock:
                        self.sock.close()
                        self.sock = None
            else:
                if last_err:
                    raise last_err
                else:
                    raise OSError("getaddrinfo returns an empty list")
        else:
            super().connect()

class SafeSSRFHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, *args, safe_ips=None, **kwargs):
        self.safe_ips = safe_ips
        super().__init__(*args, **kwargs)

    def connect(self):
        if self.safe_ips:
            last_err = None
            for safe_ip in self.safe_ips:
                try:
                    self.sock = self._create_connection(
                        (safe_ip, self.port), self.timeout, self.source_address)
                    self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    break
                except OSError as err:
                    last_err = err
                    if self.sock:
                        self.sock.close()
                        self.sock = None
            else:
                if last_err:
                    raise last_err
                else:
                    raise OSError("getaddrinfo returns an empty list")

            if self._tunnel_host:
                self._tunnel()

            self.sock = self._context.wrap_socket(self.sock,
                                                  server_hostname=self.host)
        else:
            super().connect()

class SafeSSRFHTTPHandler(urllib.request.HTTPHandler):
    def __init__(self, safe_ips, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.safe_ips = safe_ips

    def http_open(self, req):
        def _factory(*args, **kwargs):
            return SafeSSRFConnection(*args, safe_ips=self.safe_ips, **kwargs)
        return self.do_open(_factory, req)


class SafeSSRFHTTPSHandler(urllib.request.HTTPSHandler):
    def __init__(self, safe_ips, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.safe_ips = safe_ips

    def https_open(self, req):
        def _factory(*args, **kwargs):
            return SafeSSRFHTTPSConnection(
                *args,
                safe_ips=self.safe_ips,
                context=self._context,
                check_hostname=self._check_hostname,
                **kwargs,
            )
        return self.do_open(_factory, req)
