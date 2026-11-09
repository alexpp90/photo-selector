package com.phototok

/**
 * A strict known-failure marker for a test that reveals an open defect from the findings history
 * (`F-TOK-…`). The [block] holds the assertions the fixed product must pass:
 *
 * - while the defect is open the block throws an [AssertionError]; the marker swallows it, so the
 *   suite stays green;
 * - the day the defect is fixed the block passes, and the marker fails with "… is fixed: remove the
 *   marker", so the test cannot silently keep guarding nothing.
 *
 * Only an [AssertionError] counts as the expected failure: a crash (any other exception) is a
 * broken test and propagates.
 */
inline fun knownDefect(findingId: String, what: String, block: () -> Unit) {
    try {
        block()
    } catch (expected: AssertionError) {
        // The defect is still there, as recorded.
        return
    }
    throw AssertionError("$findingId is fixed ($what): remove the knownDefect marker")
}
