"""Command line entry point for photo-selector-linux."""

import argparse
import sys
from pathlib import Path
from photo_selector_linux import __version__


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="photo-selector-linux",
        description="Native GNOME photo culling app for Debian 13 / Linux.",
    )
    parser.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    parser.add_argument(
        "paths",
        nargs="*",
        type=str,
        metavar="path",
        help="Folder to open; photos given by a file manager open their folder.",
    )

    args = parser.parse_args(argv)

    targets = []
    for given in args.paths:
        target = Path(given).resolve()
        if not target.exists():
            print(f"Error: Target path does not exist: {target}", file=sys.stderr)
            return 1
        targets.append(target)

    try:
        from photo_selector_linux.ui.application import PhotoSelectorApplication
    except (ImportError, ValueError) as e:
        print(f"Error: GTK 4 / Libadwaita are not available ({e}).", file=sys.stderr)
        return 1

    app = PhotoSelectorApplication()
    return app.run(sys.argv[:1] + [str(t) for t in targets])


if __name__ == "__main__":
    sys.exit(main())
