# Usage conditions

Applies to **every active product** (Jackpix, Frameduel, Lensjet, Blitzlens). The products share
this *contract*, not code: each reads the same figures in its own tests.

A test proves only the state it runs on. Jackpix 0.7.0 froze 3–5 s on every swipe in a camera
roll of 3,000 photos (F-TOK-098) while every test, lab run and review passed: the largest folder
any of them opened held 21 photos. This contract states what users have, so that tests run there.

## The contract

The single source of truth is the machine-readable
[`testdata/conditions/conditions.json`](../../testdata/conditions/conditions.json).

| Condition | Figure | Used by |
|---|---|---|
| Folder, typical | **2,000** photos | tests of an everyday folder |
| Folder, pull request | **10,000** photos | every scale test in the PR gate |
| Folder, stress | **50,000** photos | the release stress run (`release-product`), never the PR gate |
| Layouts | one flat camera-roll folder; DCF card folders of up to 9,999 files; RAW+JPEG pairs with `.xmp` | which tree a scale test builds |
| Bursts | 20 frames (short), 200 frames (pre-capture, sports) at 30 fps | burst and series logic |
| Files | phone 12–24 MP, 1.5–8 MB; phone full resolution 48–200 MP, 8–40 MB; phone DNG 25–75 MB; camera JPEG 24–61 MP, 6–30 MB; camera RAW 20–61 MP, 20–130 MB | decode, memory and copy budgets |
| Storage | internal 1–4 GB/s; SD UHS-I 40–90 MB/s (small random reads 5–20 MB/s); UHS-II 150–250 MB/s; camera over MTP 10–35 MB/s; cloud providers download each file on open | listing, prefetch and copy budgets |
| Sessions | phone 1–10 min; tablet 20–90 min; desk 30–120 min at 0.3–2 s per decision | memory growth, caches, battery |

## Rules

1. **Scale tests read the contract.** A test that runs at a folder size reads it from
   `conditions.json`, never a literal of its own, and goes through the product's real view
   model or state holder — the code the screen calls — not a helper beside it.
2. **Assert growth, not milliseconds.** In the PR gate a scale test times the same operation
   at N and at 4N (N = PR size / 4, so 2,500 and 10,000 photos) after a warm-up, takes the best
   of a few runs, and fails when the ratio exceeds `growth_check.max_ratio` (8): linear work
   gives about 4, quadratic about 16. Machines differ; ratios on one machine do not.
   Absolute budgets (time to first photo, main-thread time per navigation) are measured on the
   reference devices in the release run.
3. **A test declares the scale it runs at.** A comment `Scale: pr`, `Scale: stress` or
   `Scale: <photos>` inside the test (or its docstring) tells the requirement checker which
   figure the test covers. A requirement that states a folder size, a speed or a memory figure
   counts as enforced only by a test that runs at that figure.
4. **Red first.** A scale test for a defect is seen failing on the old code before the fix.
5. **One product's scale finding is every product's question.** A `performance` finding in one
   product obliges a recorded check of the same class in the other three.
6. **Switching on.** A product adds itself to `consumers` in the PR that lands its first scale
   test; from then on `scripts/check_conditions_contract.py` fails if none of its tests reads
   the contract.
