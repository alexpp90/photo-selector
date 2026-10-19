# UI text: icon first, short text

Applies to **every product**. It is binding for PhotoTok and checked by
`scripts/check_ui_text_budget.py`, which reports findings for the other products but does not fail
them yet.
Each product applies the rule in its own stack. The products share the budgets, not the code.

People look at photos, not at text. On a phone the user is swiping with one thumb, often
standing or on a train. A word they have to read costs attention, and a sentence costs a whole
photo. A control that explains itself is better than one with a description.

## Principles

1. **Icon first, gesture first.** A control is an icon with no visible text unless the icon or the
   gesture cannot carry the meaning. Bar and toolbar controls are icon-only.
2. **Verbs, not sentences.** A label names the outcome: "Delete", "Show new", "Stay". It is not an
   instruction or a description ("Tap here to delete this photo").
3. **Numbers over words.** "12", not "12 photos selected". "3 new photos" is the most a count needs.
4. **Explain once.** An explanation appears at most once: in the first-run hint, the controls guide
   or a long-press tooltip. It is not repeated on every screen.
5. **Say only what the user cannot see.** No text confirms what the user just watched happen.

## When a description is allowed

Only in these cases, and then one line, as short as possible:

- **Irreversible or data-loss actions.** Example: "This location has no trash."
- **Permission or storage-access explanations.** Example: why the app needs a folder grant.
- **First run.** One hint per action, shown once.
- **Errors that need a decision.** Example: "Folder access lost" with a "Re-select" action.

Nothing else gets a body line, a subtitle or a subline.

## Budgets

Words are counted as runs of letters. Numbers, symbols (`+`, `/`, `·`) and format placeholders do
not count, so "Delete all (%d)" is two words. Characters are counted after placeholders are filled
with a typical value (a 2-digit number, a 10-character folder name).

German usually needs 30–50 % more characters than English for short UI strings (external:
W3C *Internationalization* and IBM globalization text-expansion guidance; the expansion is highest
for short strings). German therefore gets its own character limit. The word limit is the same in
both languages.

| Element | Resource key prefix | Words | EN chars | DE chars |
|---|---|---:|---:|---:|
| Bar / toolbar control (visible text) | — | 0 | 0 | 0 |
| Button or action label | `action_` | 2 | 16 | 22 |
| Chip or segmented option | `chip_` | 2 | 14 | 18 |
| Short visible label (field, badge, section) | `label_` | 2 | 18 | 24 |
| Screen or sheet title | `title_` | 3 | 24 | 32 |
| Dialog title | `dialog_title_` | 5 | 30 | 40 |
| Dialog body (allowed cases only) | `dialog_body_` | 8 | 50 | 65 |
| Snackbar message | `snackbar_` | 4 | 28 | 36 |
| Snackbar action (incl. undo) | `snackbar_action_` | 1 | 10 | 14 |
| Error or feedback message | `error_` | 8 | 50 | 65 |
| Empty state | `empty_` | 3 | 24 | 32 |
| First-run hint headline | `hint_title_` | 6 | 36 | 48 |
| First-run hint line (optional) | `hint_body_` | 6 | 40 | 52 |
| Settings row title | `setting_title_` | 3 | 26 | 34 |
| Settings row summary (value or data-loss warning only) | `setting_summary_` | 5 | 36 | 46 |
| Controls-guide callout | `guide_` | 2 | 14 | 18 |
| Spoken label (`contentDescription`, TalkBack) | `cd_` | not counted | — | — |

The numbers are the single source for `scripts/ui_text_budget.json`. A test checks that this table
and the JSON agree.

In addition to the table:

- **No sentence punctuation on a button**: no `.`, `!`, `?` and no `:` at the end of an `action_`,
  `chip_`, `snackbar_action_` or `guide_` string.
- **No visible label next to an icon** on the same button. An `Icon` plus a `Text` inside one
  button or bar item is flagged unless the label is in the budget table *and* the icon alone is
  ambiguous. In that case the exception is written down in the product's design guide.
- **No marketing copy**: no taglines, slogans or "engine"/"curation" language in the app.

## Accessibility

- An icon without visible text still gets a `contentDescription` (Android), an
  `accessibilityLabel` (SwiftUI) or an accessible name (GTK). That text is spoken, not shown. It does
  not count against the visible budget, and it may be as long as clarity needs ("Undo last delete").
- A long-press (or hover) tooltip shows the same name, so a sighted user can find out what an icon
  does without opening the guide.
- Touch targets stay at least 48 dp, whether or not a label is shown. Removing a label must not
  shrink the target.
- Layouts are checked at font scale 2.0. A label that only fits at 1.0 is a defect.

## Localization

- **Every user-visible string is a resource** (Android `res/values/strings.xml`; macOS String
  Catalog; Linux gettext). No literal in UI code.
- **Plurals** use plural resources (`<plurals>`), never `"$n photo" + "s"`. Where a number can
  stand alone, use the number alone.
- **German compound words do not wrap.** Prefer short roots ("Auswahl", "Ordner") and check the
  longest compound at font scale 2.0. A label that would need hyphenation is too long.
- **No text baked into a layout that only fits in English**: no fixed widths sized to an English
  label, and no `maxLines = 1` with truncation to hide an overflow. Buttons wrap their content.
  Rows reflow.
- Sentence case for titles and buttons in English. German capitalises nouns, so it follows German
  rules, not English title case.

## Review checklist (before committing UI text)

1. **Can it be an icon or a gesture?** If the control works without visible text (icon, 48 dp
   target, spoken label, long-press tooltip), remove the text.
2. **Is it within budget in EN and DE?** Run `python3 scripts/check_ui_text_budget.py`. Count the
   German string too, not only the English one.
3. **Is it an allowed description?** Only data loss, permissions, first run or an error that needs
   a decision may have a body line. If it is, keep it to one line.
4. **Does it say something the user cannot see?** Delete anything that describes what is on screen
   or what just happened. Use a number instead of a sentence.
5. **Is it true and consistent?** Keep the configured verb (copy or move) and the folder name. Never
   say "trash" for a permanent delete, and use one word for one action across the whole product.

## Per product

| Product | Where the rule is applied | Check |
|---|---|---|
| PhotoTok | `docs/products/phototok/DESIGN.md` § 8 *Copy & messaging* | `scripts/check_ui_text_budget.py --product phototok`: reports until PhotoTok's strings are resources, then fails (`mode` in `scripts/ui_text_budget.json`) |
| Android Desktop | its `DESIGN.md` (labels on the 2×3 action block are allowed: large screens, keyboard hints) | reports only |
| macOS Desktop, Linux Desktop, Desktop (legacy) | their design docs | not yet covered by the script |
