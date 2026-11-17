import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.AndroidComponentsExtension
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/** What a module says about itself to [AndroidModuleConventionPlugin]. */
abstract class PhotoSelectorModuleExtension {
    /** Compile `products/android/tests-shared/` into this module's JVM unit tests. */
    var sharedTestSources: Boolean = false
}

/**
 * The conventions of every Android module (`:core`, `:saf`, `:android-desktop`, `:phototok`):
 *
 * - **Source layout.** Every product in this repository exposes `src/` and `tests/`, whatever its
 *   language, so Gradle's `src/main | src/test | src/androidTest` convention is replaced:
 *   sources in `src/`, unit tests in `tests/unit/`, instrumented tests in `tests/instrumented/`,
 *   pure-Kotlin helpers both compile in `tests/common/`; `res/` and `AndroidManifest.xml` where
 *   the module has them. See docs/README.md.
 * - **Lint.** Android Lint fails the build on a new problem (`abortOnError`); the problems that
 *   existed when this gate was introduced are in the module's `lint-baseline.xml`.
 * - **Style.** ktlint and detekt run on `src/` and `tests/` against the baselines in the
 *   module's `config/` directory (`ktlint-baseline.xml`, `detekt-baseline.xml`); the rules are
 *   the repository's `config/` files next to this build.
 *
 * A module applies it after its Android plugin: `id("photoselector.android-module")`.
 */
class AndroidModuleConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val module = extensions.create<PhotoSelectorModuleExtension>("photoSelectorModule")

        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        pluginManager.apply("dev.detekt")

        (extensions.getByName("androidComponents") as AndroidComponentsExtension<*, *, *>).finalizeDsl { dsl ->
            val android = dsl as CommonExtension
            configureSourceLayout(android, module.sharedTestSources)
            android.lint.apply {
                abortOnError = true
                baseline = file("lint-baseline.xml")
            }
        }

        configureStyle()
    }

    private fun Project.configureSourceLayout(android: CommonExtension, sharedTestSources: Boolean) {
        val hasRes = file("res").isDirectory
        val hasManifest = file("AndroidManifest.xml").isFile
        val unitTest = listOfNotNull("tests/unit", "tests/common", "../tests-shared".takeIf { sharedTestSources })
        android.sourceSets.apply {
            getByName("main") {
                java.directories.apply { clear(); add("src") }
                kotlin.directories.apply { clear(); add("src") }
                if (hasRes) res.directories.apply { clear(); add("res") }
                if (hasManifest) manifest.srcFile("AndroidManifest.xml")
            }
            getByName("test") {
                java.directories.apply { clear(); addAll(unitTest) }
                kotlin.directories.apply { clear(); addAll(unitTest) }
            }
            getByName("androidTest") {
                java.directories.apply { clear(); add("tests/instrumented"); add("tests/common") }
                kotlin.directories.apply { clear(); add("tests/instrumented"); add("tests/common") }
            }
        }
    }

    private fun Project.configureStyle() {
        extensions.configure<KtlintExtension> {
            android.set(true)
            baseline.set(layout.projectDirectory.file("config/ktlint-baseline.xml"))
        }
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig.set(true)
            config.setFrom(rootProject.file("config/detekt.yml"))
            baseline.set(layout.projectDirectory.file("config/detekt-baseline.xml"))
            source.setFrom("src", "tests", "../tests-shared".takeIf { file(it).isDirectory })
        }
    }
}
