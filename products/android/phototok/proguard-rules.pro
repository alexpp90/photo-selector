# Photo-Tok ProGuard rules
-keepattributes *Annotation*

# Hilt: kept whole on purpose (H-JP-3, reviewed 2026-11-16). Hilt/Dagger's own consumer rules cover
# the generated components, but a rule that is too narrow breaks only at runtime — dependency
# injection fails on first launch of the minified build — and neither assembleLab nor a JVM unit
# test starts the app under R8, so no gate here would notice. Narrow it only together with a
# smoke run of the R8 build (assembleLab) on a device; the saving is a few KB of the 4 MB APK.
-keep class dagger.hilt.** { *; }
