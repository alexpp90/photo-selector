# Usage-conditions data

The conditions users have, as data every product's scale and performance tests read — data
only, no code.

- `conditions.json` — folder sizes (typical, the size every pull request is tested at, the
  release stress size), the growth check scale tests apply, folder layouts, bursts, file sizes
  and resolutions, storage classes and session lengths. Validated by
  `scripts/check_conditions_contract.py`.

Rules and workflow: [`docs/shared/USAGE_CONDITIONS.md`](../../docs/shared/USAGE_CONDITIONS.md).
