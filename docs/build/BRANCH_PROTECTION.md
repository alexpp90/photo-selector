# Protecting `main`

How `main` should be protected in `alexpp90/photo-selector` (public) and what that takes, as
`gh api` commands the repository owner runs. **Nothing in this document has been applied.**
Repository settings and rulesets are the owner's.

State on 2026-10-18 (checked with `gh api`):

| Repository | Protection today | Rulesets / branch protection available? |
|---|---|---|
| `alexpp90/photo-selector` (public) | none (`branches/main/protection` → 404, `rulesets` → `[]`) | yes |
| the maintainer's private overlay repository | none | **no**: GitHub answers HTTP 403, "Upgrade to GitHub Pro or make this repository public". On the Free plan a private repository gets neither rulesets nor classic branch protection. |

Merging today: merge commits (`gh pr merge --merge --delete-branch`); squash is enabled too;
`delete_branch_on_merge` is on. No workflow pushes to `main`: the release jobs push to the
Homebrew tap and APT repositories, and tags are outside a branch ruleset.

## Why the checks need care

A required status check that never reports blocks the PR forever. Several of ours never
report on some PRs:

| Check (as GitHub names it) | Workflow | Runs on every PR to `main`? |
|---|---|---|
| `README parity & private-path guard` | `repo-hygiene.yml` | **yes** (no `paths:` filter) |
| `cla` | `cla.yml` (`pull_request_target`) | **yes** (opened / synchronize) |
| `Verification record (advisory)` | `pr-verification.yml` | yes, but it never fails on purpose: do not require it |
| `Detect changed modules` | `android.yml` | **no**: `android.yml` has a `paths:` filter, so a PR that touches only Linux, macOS, Desktop or docs never starts it |
| `Unit tests (JVM)`, `Android Lint`, `Instrumented tests (API 30)` | `android.yml` | no (same filter) |
| `Lint`, `Tests on … / Python 3.12`, `Visual regression tests` | `desktop.yml` | no (`paths:` filter) |
| `Swift Tests (macOS)` | `macos.yml` | no (`paths:` filter) |
| `Lint (flake8)`, `Headless Unit Tests (pytest)`, `Debian Packaging & APT Validation` | `linux.yml` | no (`paths:` filter) |

A job skipped by its own `if:` reports *skipped*, which a required check accepts. A workflow
that is not started because of `paths:` reports nothing, which a required check does not
accept. So `Detect changed modules` can only be required once it runs on every PR, and the
per-product jobs can only be required through one always-running job that summarises them.

## Recommended ruleset

1. **Require a pull request** before merging into `main`. No approvals are required: the
   owner is the only reviewer, and a required approval would block their own PRs and every
   agent PR. Merge commits and squash allowed (the history uses merge commits).
2. **Require the checks that always run**: `README parity & private-path guard` and `cla`.
3. **Require the per-product tests through an aggregate gate**, once it exists (see
   [Stage 2](#stage-2--an-aggregate-gate-so-product-tests-can-be-required)): one job that
   always runs, decides which products a PR touches the way `Detect changed modules` does
   today, and passes only when every job it depends on succeeded or was skipped for that
   reason. Until then the product tests stay unrequired, as now.
4. **Block force pushes and deletion** of `main`.
5. **Do not turn on "require branches to be up to date before merging"** on its own
   (`strict_required_status_checks_policy`). With several sessions merging in parallel, every
   merge makes every other open PR out of date, and each one then needs another merge of
   `main` and another full CI run. A merge queue gets the same guarantee without that.
6. **Follow-up: a merge queue** (see [Stage 3](#stage-3--merge-queue)).

Until protection is on, the manual rule in the ship-change playbook stays: after merging more
than one PR in a row, pull `main` and run `./scripts/run_tests.sh` there.

### Stage 1 — now (applies cleanly today)

Pull request required, the two always-running checks required, no force push, no deletion.
`integration_id` 15368 is the GitHub Actions app, so only a GitHub Actions run can satisfy the
checks.

```bash
gh api --method POST repos/alexpp90/photo-selector/rulesets --input - <<'JSON'
{
  "name": "main",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "bypass_actors": [],
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false,
        "allowed_merge_methods": ["merge", "squash"]
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": false,
        "do_not_enforce_on_create": false,
        "required_status_checks": [
          { "context": "README parity & private-path guard", "integration_id": 15368 },
          { "context": "cla", "integration_id": 15368 }
        ]
      }
    }
  ]
}
JSON
```

Check and, if needed, undo:

```bash
gh api repos/alexpp90/photo-selector/rulesets
gh api repos/alexpp90/photo-selector/rules/branches/main
gh api --method DELETE repos/alexpp90/photo-selector/rulesets/<ruleset-id>
```

`bypass_actors` is empty, so the owner is held to the rules too. To keep an emergency path,
add the admin role in bypass mode `pull_request` (bypass by opening a PR) or `always`:
`{ "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "pull_request" }`.

### Stage 2 — an aggregate gate, so product tests can be required

What has to change in `.github/workflows/` (and, because these are gates, in
`scripts/run_tests.sh` and `docs/build/CI_PARITY.md` in the same commit):

1. A workflow that starts on every PR to `main` and on `merge_group`, with no `paths:` filter,
   for example `ci-gate.yml`, or `android.yml` with its `pull_request.paths` removed. It runs
   the `dorny/paths-filter` step that `detect-changes` runs today, extended with one output
   per product (Jackpix, Frameduel, Lensjet, Blitzlens, Desktop).
2. The per-product jobs move behind that detection (`needs: detect-changes` +
   `if: needs.detect-changes.outputs.<product> == 'true'`) instead of behind a workflow
   `paths:` filter. They can stay in their own workflow files only if those files also lose
   their `paths:` filters. A required check cannot depend on a job in another workflow, so
   the gate has to sit in the workflow that holds the jobs. That means one gate per workflow,
   or the product jobs called from one workflow as reusable workflows (`workflow_call`).
3. A final job per gate, for example:

   ```yaml
   gate:
     name: CI gate
     if: always()
     needs: [detect-changes, unit-tests, lint, instrumented-tests]
     runs-on: ubuntu-latest
     timeout-minutes: 5
     steps:
       - run: |
           echo '${{ toJSON(needs) }}' | python3 -c '
           import json, sys
           bad = {k: v["result"] for k, v in json.load(sys.stdin).items()
                  if v["result"] not in ("success", "skipped")}
           sys.exit(f"failed: {bad}" if bad else 0)'
   ```

   Advisory jobs (`Android Lint` runs with `continue-on-error: true`) are left out of
   `needs`, or their `failure` is accepted, so the gate stays exactly as strict as CI is today.
4. `scripts/tests/test_workflow_bounds.py` already requires a `timeout-minutes` on every job,
   so the gate job needs one too.

Then add the gate to the ruleset; `Detect changed modules` is required at the same time,
because by then it runs on every PR:

```bash
gh api repos/alexpp90/photo-selector/rulesets/<ruleset-id> > ruleset.json
# add to rules[type=required_status_checks].parameters.required_status_checks:
#   { "context": "Detect changed modules", "integration_id": 15368 },
#   { "context": "CI gate", "integration_id": 15368 }
gh api --method PUT repos/alexpp90/photo-selector/rulesets/<ruleset-id> --input ruleset.json
```

### Stage 3 — merge queue

A merge queue tests each PR on top of `main` plus the PRs ahead of it in the queue, and merges
only what passed. This replaces the manual "run the mirror on `main` after a batch" rule.
`merge_group` support today: **no workflow has a `merge_group:` trigger**. What has to change:

| Workflow | Change for the merge queue |
|---|---|
| `repo-hygiene.yml` | add `merge_group:` under `on:`; no step reads PR context |
| `cla.yml` | `cla` runs on `pull_request_target` and needs the PR (the CLA assistant comments on it). It cannot run on a merge group, but a required check must report there too. Add `merge_group:` and a job of the same name that only succeeds when `github.event_name == 'merge_group'` (the CLA was already checked on the PR), or drop `cla` from the required checks |
| the Stage 2 gate workflow(s) | add `merge_group:`; the paths filter must compare the merge group with `main` (`base: ${{ github.event.merge_group.base_ref }}` or the equivalent for `dorny/paths-filter`), check this before relying on it |
| `pr-verification.yml` | nothing: it is not required, and it judges the PR description, which a merge group does not have |
| `android.yml` `build` / `publish-play`, release jobs in the other workflows | none of them may run for `merge_group` (they publish). Their `if:` conditions check `push` / tags today; keep it that way |

Then add the merge-queue rule:

```bash
gh api repos/alexpp90/photo-selector/rulesets/<ruleset-id> > ruleset.json
# add to rules:
#   { "type": "merge_queue", "parameters": {
#       "merge_method": "MERGE", "grouping_strategy": "ALLGREEN",
#       "max_entries_to_build": 5, "min_entries_to_merge": 1, "max_entries_to_merge": 5,
#       "min_entries_to_merge_wait_minutes": 1, "check_response_timeout_minutes": 60 } }
gh api --method PUT repos/alexpp90/photo-selector/rulesets/<ruleset-id> --input ruleset.json
```

`check_response_timeout_minutes` must be longer than the slowest required job (the
instrumented Android tests: `timeout-minutes: 45`). Agents then merge with
`gh pr merge <n> --merge --auto` (the PR joins the queue) instead of merging directly.

None of these workflow changes were made. Even adding `merge_group:` to `repo-hygiene.yml`
alone would have no effect without a queue, so all of them are left for the Stage 3 change.

## The private overlay repository

Rulesets and branch protection need GitHub Pro (or a public repository), so `main` there stays
unprotected for now and the batch-merge mirror run stays a manual rule. With GitHub Pro, the
Stage 1 command applies with the repository name changed and that repository's own
always-running check in `required_status_checks` (it has no `cla` check).
