import sys
from unittest.mock import MagicMock

# Mock dependencies that are not in the environment to allow importing utils
try:
    import PIL
    import PIL.Image
except ImportError:
    class MockUnidentifiedImageError(Exception):
        pass
    mock_pil = MagicMock()
    mock_image = MagicMock()
    mock_image.UnidentifiedImageError = MockUnidentifiedImageError
    mock_pil.Image = mock_image
    sys.modules['PIL'] = mock_pil
    sys.modules['PIL.Image'] = mock_image
    PIL = mock_pil

try:
    import rawpy
except ImportError:
    class MockLibRawError(Exception):
        pass
    mock_rawpy = MagicMock()
    mock_rawpy.LibRawError = MockLibRawError
    sys.modules['rawpy'] = mock_rawpy
    rawpy = mock_rawpy

import unittest
import pytest
from unittest.mock import patch
from pathlib import Path
from photo_selector_toolbox.core.utils import resolve_path, get_exiftool_path, load_image_preview, is_excluded_subfolder

class TestGetExiftoolPath(unittest.TestCase):

    def setUp(self):
        # Clear the lru_cache before each test to ensure tests don't interfere with each other
        get_exiftool_path.cache_clear()

    @patch('shutil.which')
    def test_found_in_path(self, mock_which):
        """Tests that 'exiftool' is returned if found in system PATH."""
        mock_which.return_value = "/usr/bin/exiftool"
        self.assertEqual(get_exiftool_path(), "/usr/bin/exiftool")

    @patch('pathlib.Path.exists')
    @patch('sys.platform', 'linux')
    @patch('shutil.which', return_value=None)
    def test_found_in_source_bin(self, mock_which, mock_exists):
        """Tests that it checks the bundled 'bin' directory when run from source."""
        mock_exists.return_value = True

        path = get_exiftool_path()
        self.assertIsNotNone(path)
        self.assertTrue("bin" in path and "exiftool" in path)

    @patch('pathlib.Path.exists')
    @patch('sys.platform', 'win32')
    @patch('shutil.which', return_value=None)
    def test_found_in_source_bin_windows(self, mock_which, mock_exists):
        """Tests that it checks for 'exiftool.exe' on Windows."""
        mock_exists.return_value = True

        path = get_exiftool_path()
        self.assertIsNotNone(path)
        self.assertTrue("bin" in path and "exiftool.exe" in path)

    @patch('pathlib.Path.exists')
    @patch('sys.platform', 'win32')
    @patch('shutil.which', return_value=None)
    def test_found_in_source_bin_windows_no_ext(self, mock_which, mock_exists):
        """Tests fallback to 'exiftool' without extension on Windows."""
        mock_exists.side_effect = [False, True]

        path = get_exiftool_path()
        self.assertIsNotNone(path)
        self.assertTrue("bin" in path)
        self.assertTrue(path.endswith("exiftool"))

    @patch('pathlib.Path.exists')
    @patch('sys.platform', 'linux')
    @patch('shutil.which', return_value=None)
    def test_not_found(self, mock_which, mock_exists):
        """Tests that it returns None if not found anywhere."""
        mock_exists.return_value = False
        self.assertIsNone(get_exiftool_path())

    @patch('pathlib.Path.exists')
    @patch('sys.platform', 'linux')
    @patch('shutil.which', return_value=None)
    def test_found_in_meipass_frozen(self, mock_which, mock_exists):
        """Tests that it checks sys._MEIPASS when frozen (PyInstaller)."""
        mock_exists.return_value = True

        with patch.object(sys, 'frozen', True, create=True), \
             patch.object(sys, '_MEIPASS', '/tmp/_MEI12345', create=True):
            path = get_exiftool_path()
            self.assertIsNotNone(path)
            expected = str(Path('/tmp/_MEI12345') / 'exiftool')
            self.assertEqual(path, expected)

class TestResolvePath(unittest.TestCase):
    def test_local_path(self):
        """Tests that standard local paths are correctly converted to Path objects."""
        # Using native Path creation to ensure platform-independent comparison
        path_str = str(Path("/tmp/test.jpg"))
        result = resolve_path(path_str)
        self.assertIsInstance(result, Path)
        self.assertEqual(result.as_posix(), "/tmp/test.jpg")

    @pytest.mark.requirement("REQ-DESK-PATH.02")
    @patch("sys.platform", "linux")
    @patch("photo_selector_toolbox.core.utils.os.getuid", return_value=1000, create=True)
    def test_smb_linux(self, mock_getuid):
        """Tests SMB URL resolution to GVFS mount points on Linux."""
        path_str = "smb://myserver/myshare/path/to/image.jpg"
        result = resolve_path(path_str)
        expected = Path("/run/user/1000/gvfs/smb-share:server=myserver,share=myshare/path/to/image.jpg")
        self.assertEqual(str(result), str(expected))

    @pytest.mark.requirement("REQ-DESK-PATH.03")
    @patch("sys.platform", "darwin")
    def test_smb_macos(self):
        """Tests SMB URL resolution to /Volumes mount points on macOS."""
        path_str = "smb://myserver/myshare/path/to/image.jpg"
        result = resolve_path(path_str)
        expected = Path("/Volumes/myshare/path/to/image.jpg")
        self.assertEqual(str(result), str(expected))

    @pytest.mark.requirement("REQ-DESK-PATH.04")
    @patch("sys.platform", "win32")
    def test_smb_windows_fallback(self):
        """Tests that SMB URLs return as-is on platforms like Windows."""
        path_str = "smb://myserver/myshare/path/to/image.jpg"
        result = resolve_path(path_str)
        # On non-linux/non-darwin, it should return Path(path_str)
        self.assertEqual(str(result), str(Path(path_str)))

    @pytest.mark.requirement("REQ-DESK-PATH.05")
    @patch("sys.platform", "darwin")
    def test_smb_url_decoding(self):
        """Tests that URL-encoded characters in SMB URLs are correctly decoded."""
        path_str = "smb://myserver/share%20with%20space/file%20name.jpg"
        result = resolve_path(path_str)
        expected = Path("/Volumes/share with space/file name.jpg")
        self.assertEqual(str(result), str(expected))

    @pytest.mark.requirement("REQ-DESK-PATH.01")
    def test_smb_no_path(self):
        """Tests handling of SMB URLs with no path component."""
        path_str = "smb://myserver"
        result = resolve_path(path_str)
        self.assertEqual(str(result), str(Path(path_str)))

    def test_path_object_input(self):
        """Tests that passing a Path object works and covers the isinstance check."""
        p = Path("/tmp/local_file.jpg")
        result = resolve_path(p)
        self.assertEqual(result, Path("/tmp/local_file.jpg"))

    @patch("photo_selector_toolbox.core.utils.urlparse")
    @patch("sys.platform", "darwin")
    def test_smb_no_leading_slash(self, mock_urlparse):
        """Tests handling when urlparse returns a path without a leading slash."""
        from urllib.parse import ParseResult
        mock_urlparse.return_value = ParseResult(
            scheme="smb", netloc="myserver", path="myshare/path", params="", query="", fragment=""
        )
        result = resolve_path("smb://myserver/myshare/path")
        expected = Path("/Volumes/myshare/path")
        self.assertEqual(str(result), str(expected))

    def test_smb_root_only(self):
        """Tests handling of SMB URLs with only a root slash."""
        path_str = "smb://myserver/"
        result = resolve_path(path_str)
        self.assertEqual(str(result), str(Path(path_str)))

    @patch("sys.platform", "linux")
    @patch("photo_selector_toolbox.core.utils.os.getuid", side_effect=AttributeError, create=True)
    def test_smb_linux_no_getuid(self, mock_getuid):
        """Tests fallback when os.getuid is missing on Linux."""
        path_str = "smb://myserver/myshare/file.jpg"
        result = resolve_path(path_str)
        self.assertEqual(str(result), str(Path(path_str)))



class TestLoadImagePreview(unittest.TestCase):
    @patch('photo_selector_toolbox.core.utils.Image.open')
    def test_standard_image(self, mock_open):
        """Test loading a standard image (e.g., JPEG)."""
        mock_img = MagicMock()
        mock_img.convert.return_value = mock_img
        mock_open.return_value = mock_img

        path = Path('test.jpg')
        result = load_image_preview(path)

        mock_open.assert_called_once_with(path)
        mock_img.thumbnail.assert_called_once_with((150, 150))
        self.assertEqual(result, mock_img)

    @patch('photo_selector_toolbox.core.utils.Image.fromarray')
    @patch('photo_selector_toolbox.core.utils.rawpy.imread')
    def test_raw_image(self, mock_imread, mock_fromarray):
        """Test loading a RAW image."""
        mock_raw = MagicMock()
        mock_rgb = MagicMock()
        mock_raw.__enter__.return_value = mock_raw
        mock_raw.postprocess.return_value = mock_rgb
        mock_imread.return_value = mock_raw

        mock_img = MagicMock()
        mock_img.copy.return_value = mock_img
        mock_img.convert.return_value = mock_img
        mock_fromarray.return_value = mock_img

        path = Path('test.arw')
        result = load_image_preview(path)

        mock_imread.assert_called_once_with('test.arw')
        mock_raw.postprocess.assert_called_once_with(use_camera_wb=True, bright=1.0, half_size=True)
        mock_fromarray.assert_called_once_with(mock_rgb)
        mock_img.thumbnail.assert_called_once_with((150, 150))
        self.assertEqual(result, mock_img)

    @patch('photo_selector_toolbox.core.utils.Image.open')
    def test_full_res(self, mock_open):
        """Test loading a standard image at full resolution."""
        mock_img = MagicMock()
        mock_img.convert.return_value = mock_img
        mock_open.return_value = mock_img

        path = Path('test.jpg')
        result = load_image_preview(path, full_res=True)

        mock_open.assert_called_once_with(path)
        mock_img.thumbnail.assert_not_called()
        self.assertEqual(result, mock_img)

    @patch('photo_selector_toolbox.core.utils.Image.open')
    @patch('photo_selector_toolbox.core.utils.rawpy.imread')
    def test_raw_fallback_to_pillow(self, mock_imread, mock_open):
        """Test that Pillow is used if rawpy fails."""
        mock_imread.side_effect = rawpy.LibRawError("rawpy failed")

        mock_img = MagicMock()
        mock_img.convert.return_value = mock_img
        mock_open.return_value = mock_img

        path = Path('test.arw')
        result = load_image_preview(path)

        mock_imread.assert_called_once_with('test.arw')
        mock_open.assert_called_once_with(path)
        mock_img.thumbnail.assert_called_once_with((150, 150))
        self.assertEqual(result, mock_img)

    @patch('photo_selector_toolbox.core.utils.Image.open')
    @patch('photo_selector_toolbox.core.utils.rawpy.imread')
    def test_raw_image_with_jpeg_thumb(self, mock_imread, mock_open):
        """Test loading a RAW image with an embedded JPEG thumbnail."""
        mock_raw = MagicMock()
        mock_imread.return_value = mock_raw
        mock_raw.__enter__.return_value = mock_raw

        mock_thumb = MagicMock()
        mock_thumb.format = 1  # Let's say rawpy.ThumbFormat.JPEG is 1
        mock_thumb.data = b"fake_jpeg_data"
        mock_raw.extract_thumb.return_value = mock_thumb

        # Mock rawpy.ThumbFormat.JPEG
        with patch('photo_selector_toolbox.core.utils.rawpy.ThumbFormat') as mock_thumb_format:
            mock_thumb_format.JPEG = 1

            mock_img = MagicMock()
            mock_img.convert.return_value = mock_img
            mock_open.return_value = mock_img

            path = Path('test.arw')
            result = load_image_preview(path)

            self.assertEqual(result, mock_img)
            mock_raw.extract_thumb.assert_called_once()
            mock_open.assert_called_once()

    @patch('photo_selector_toolbox.core.utils.Image.fromarray')
    @patch('photo_selector_toolbox.core.utils.rawpy.imread')
    def test_raw_image_with_bitmap_thumb(self, mock_imread, mock_fromarray):
        """Test loading a RAW image with an embedded BITMAP thumbnail."""
        mock_raw = MagicMock()
        mock_imread.return_value = mock_raw
        mock_raw.__enter__.return_value = mock_raw

        mock_thumb = MagicMock()
        mock_thumb.format = 2  # Let's say rawpy.ThumbFormat.BITMAP is 2
        mock_thumb.data = b"fake_bitmap_data"
        mock_raw.extract_thumb.return_value = mock_thumb

        # Mock rawpy.ThumbFormat.BITMAP
        with patch('photo_selector_toolbox.core.utils.rawpy.ThumbFormat') as mock_thumb_format:
            mock_thumb_format.JPEG = 1
            mock_thumb_format.BITMAP = 2

            mock_img = MagicMock()
            mock_img.convert.return_value = mock_img
            mock_fromarray.return_value = mock_img

            path = Path('test.arw')
            result = load_image_preview(path)

            self.assertEqual(result, mock_img)
            mock_raw.extract_thumb.assert_called_once()
            mock_fromarray.assert_called_once_with(b"fake_bitmap_data")

    @patch('photo_selector_toolbox.core.utils.Image.fromarray')
    @patch('photo_selector_toolbox.core.utils.rawpy.imread')
    def test_raw_image_thumb_exception(self, mock_imread, mock_fromarray):
        """Test loading a RAW image when thumb extraction raises an exception, falling back to postprocess."""
        mock_raw = MagicMock()
        mock_imread.return_value = mock_raw
        mock_raw.__enter__.return_value = mock_raw
        mock_raw.extract_thumb.side_effect = Exception("No thumb")

        mock_rgb = MagicMock()
        mock_raw.postprocess.return_value = mock_rgb

        mock_img = MagicMock()
        mock_img.copy.return_value = mock_img
        mock_img.convert.return_value = mock_img
        mock_fromarray.return_value = mock_img

        path = Path('test.arw')
        result = load_image_preview(path)

        self.assertEqual(result, mock_img)
        mock_raw.postprocess.assert_called_once()

    @patch('photo_selector_toolbox.core.utils.Image.open')
    def test_exception_handling(self, mock_open):
        """Test that None is returned on common image loading exceptions."""
        mock_open.side_effect = OSError("File not found or access denied")

        path = Path('test.jpg')
        result = load_image_preview(path)

        self.assertIsNone(result)

    @patch('photo_selector_toolbox.core.utils.Image.open')
    def test_unidentified_image_error(self, mock_open):
        """Test that None is returned when Pillow cannot identify the image."""
        mock_open.side_effect = PIL.Image.UnidentifiedImageError("Cannot identify image file")

        path = Path('test.jpg')
        result = load_image_preview(path)

        self.assertIsNone(result)


class TestIsExcludedSubfolder(unittest.TestCase):
    @pytest.mark.requirement("REQ-DESK-EXIF.05")
    def test_excluded_subfolders(self):
        root = Path("/Users/alex/Photos")
        # Subfolder named 'Selection'
        self.assertTrue(is_excluded_subfolder(root / "Selection" / "img.jpg", root))
        # Subfolder named 'Selected'
        self.assertTrue(is_excluded_subfolder(root / "Selected" / "img.jpg", root))
        # Case insensitive
        self.assertTrue(is_excluded_subfolder(root / "selection" / "img.jpg", root))
        self.assertTrue(is_excluded_subfolder(root / "selected" / "img.jpg", root))
        self.assertTrue(is_excluded_subfolder(root / "SELECTION" / "sub" / "img.jpg", root))

    def test_not_excluded_when_root(self):
        # Specifically selected root
        root = Path("/Users/alex/Photos/Selection")
        self.assertFalse(is_excluded_subfolder(root / "img.jpg", root))

        root2 = Path("/Users/alex/Photos/Selected")
        self.assertFalse(is_excluded_subfolder(root2 / "img.jpg", root2))

    def test_not_excluded_normal_path(self):
        root = Path("/Users/alex/Photos")
        self.assertFalse(is_excluded_subfolder(root / "img.jpg", root))
        self.assertFalse(is_excluded_subfolder(root / "Vacation" / "img.jpg", root))

        # Filename is 'Selection' but it's not a folder name in path
        self.assertFalse(is_excluded_subfolder(root / "Selection.jpg", root))

    def test_not_relative_path(self):
        root = Path("/Users/alex/Photos")
        self.assertFalse(is_excluded_subfolder(Path("/different/path/img.jpg"), root))


class TestCreatePlaceholderImage(unittest.TestCase):
    def test_create_placeholder_image(self):
        from photo_selector_toolbox.core.utils import create_placeholder_image
        # Verify it generates a valid PIL Image of correct dimensions
        img = create_placeholder_image(200, 100, "Test Placeholder")
        self.assertEqual(img.size, (200, 100))
        self.assertEqual(img.mode, "RGB")


class TestGetExcludedFolderNames(unittest.TestCase):
    @patch("photo_selector_toolbox.core.config.load_config")
    def test_get_excluded_folder_names_success(self, mock_load_config):
        from photo_selector_toolbox.core.utils import get_excluded_folder_names
        get_excluded_folder_names.cache_clear()
        mock_load_config.return_value = {"selection_folder": "MySelection"}
        names = get_excluded_folder_names()
        self.assertIn("myselection", names)
        self.assertIn("selection", names)
        self.assertIn("selected", names)

    @patch("photo_selector_toolbox.tools.ollama.load_config", side_effect=Exception("Failed to load config"))
    def test_get_excluded_folder_names_error(self, mock_load_config):
        from photo_selector_toolbox.core.utils import get_excluded_folder_names
        get_excluded_folder_names.cache_clear()
        names = get_excluded_folder_names()
        self.assertEqual(names, {"selection", "selected", "phototok_selection", "phototok_leftswipe"})


class TestFocalLengthFormatting(unittest.TestCase):
    def test_format_focal_length_label(self):
        from photo_selector_toolbox.core.utils import _format_focal_length_label
        self.assertEqual(_format_focal_length_label(50, 50), "50 mm")
        self.assertEqual(_format_focal_length_label(50.0, 50.0), "50 mm")
        self.assertEqual(_format_focal_length_label(50.5, 50.5), "50.5 mm")
        self.assertEqual(_format_focal_length_label(35, 50), "35-50 mm")

    def test_generate_exact_buckets(self):
        from photo_selector_toolbox.core.utils import _generate_exact_buckets
        from collections import Counter
        counts = Counter({50: 2, 85.5: 1})
        buckets = _generate_exact_buckets([50, 85.5], counts)
        self.assertEqual(len(buckets), 2)
        self.assertEqual(buckets[0], ("50 mm", 2, 50))
        self.assertEqual(buckets[1], ("85.5 mm", 1, 85.5))

if __name__ == "__main__":
    unittest.main()

