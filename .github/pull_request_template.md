## Description

Summarize the changes proposed in this Pull Request and provide any relevant context or background.

## Targeted Solution(s)

Please select which independent solution(s) this PR modifies:

- [ ] **Jackpix** (Kotlin/Compose, files under `products/android/phototok/`)
- [ ] **Frameduel** — Android Desktop (Kotlin/Compose, files under `products/android/android-desktop/`)
- [ ] **Lensjet** — macOS Desktop (Swift/SwiftUI, files under `products/macos-desktop/`)
- [ ] **Blitzlens** — Linux Desktop (Python/GTK4, files under `products/linux-desktop/`)
- [ ] **Desktop (legacy)** (Python/Tkinter, files under `products/desktop/`)

## Verification

<!-- Where this was developed and how far it was tested — CONTRIBUTING.md, "Verification record".
     One block per machine that ran ./scripts/run_tests.sh, written by
     `python3 scripts/verification_record.py pr`. Run it on every machine that tested this PR
     (e.g. the Mac for macOS Desktop, the Linux workstation for Linux Desktop); it replaces only
     that machine's block. Don't edit the blocks by hand. -->
<!-- pst-verification:records:begin -->
_No local verification record yet: run `./scripts/run_tests.sh`, then `python3 scripts/verification_record.py pr`._ <!-- pst-verification:placeholder -->
<!-- pst-verification:records:end -->

### Who did the work

- [ ] The maintainer, hands-on
- [ ] An agent on the maintainer's machine (which: …)
- [ ] An agent with limited tooling — cloud agent or sandbox (which: …)

### Not tested locally

<!-- Every product, gate or device this change affects that nobody ran, and why
     (no Mac at hand, no GTK 4, no Android device, sandbox without the SDK, …).
     "Nothing" only when every touched product shows ✅ above. -->

### Manual testing on real hardware

<!-- Native apps: what was tried by hand, on which device. Leave the table empty if nothing
     was — the PR check notes that rather than guessing. -->

| Product | Device / machine | What was exercised | Result |
|---|---|---|---|

## Checklist

- [ ] I have read [CONTRIBUTING.md](../CONTRIBUTING.md) and the target product's documentation under [docs/products/](../docs/README.md).
- [ ] I named the target product in the description, and did not change the others.
- [ ] If my changes alter observable behaviour, I described the new behaviour precisely above (and updated `docs/shared/` if it crosses products).
- [ ] I have added/updated unit tests to cover my changes.
- [ ] All automated tests pass successfully.
- [ ] The verification blocks above are from the last push (re-run `verification_record.py pr` after re-testing).
- [ ] I have formatted my code using the project's style guidelines (e.g. Flake8, Kotlin style guides).
- [ ] Room database schema is exported and committed (if any database entities changed in `products/android/android-desktop`).
