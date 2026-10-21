# Jackpix — Architecture

> Product: **Jackpix** (`products/android/phototok/`, `:phototok`, `com.phototok`).
> Jackpix is an independent implementation, not a variant of Android Desktop: no
> OpenCV, no Room, no Vico, DataStore instead of a database, and a gesture-first feed
> rather than a comparison workspace.
> Architecture conventions that are enforced (typed settings, pure domain logic, image
> source abstraction, progressive discovery) are specified in
> `REQUIREMENTS.md` (maintainer-private) § 3.
> Visual design: [`DESIGN.md`](DESIGN.md).

## Theme Implementation

### 10.2 Jackpix Theme (:phototok)
Dark-only Material 3 scheme "Lime Fizz" (`ui/theme/Color.kt`, `Theme.kt`): a lime accent on neutral grey. Surfaces, background, outlines, secondary and tertiary roles are neutral grey (equal R, G, B) so they do not tint the photo being judged; the pink of the app icon is never used in the UI. Semantic colours (`SuccessGreen`, `WarningAmber`, the error roles) keep their meaning.

```kotlin
val PtDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB8EB4F),          // PtPrimary (lime, the icon tile colour)
    onPrimary = Color(0xFF1E2A10),        // PtOnPrimary (the icon mark colour)
    primaryContainer = Color(0xFF3E5A00), // dark lime
    onPrimaryContainer = Color(0xFFD4F58C),
    secondary = Color(0xFFC8C8C8),
    tertiary = Color(0xFFC7C7C7),
    surface = Color(0xFF131313),          // PtSurface
    surfaceVariant = Color(0xFF353535),
    onSurface = Color(0xFFE4E4E4),
    onSurfaceVariant = Color(0xFFC7C7C7),
    outline = Color(0xFF909090),
    outlineVariant = Color(0xFF464646),
    background = Color(0xFF131313),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
)
```
