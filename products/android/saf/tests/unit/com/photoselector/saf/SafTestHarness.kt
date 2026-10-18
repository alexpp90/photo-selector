package com.photoselector.saf

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The sorting rule the tests run with: the files they use are sorted the same way by both
 * products' own rules (Android Desktop `ImageFormats.selectionSubfolder`, PhotoTok
 * `PhotoExtensions`). A product's real rule is tested in that product.
 */
object TestSubfolderRule : SelectionSubfolderRule {
    private val raw = setOf("cr2", "cr3", "arw", "nef", "dng", "raf", "orf", "rw2")
    private val jpeg = setOf("jpg", "jpeg")

    override fun subfolderFor(fileName: String, bundleNames: List<String>): SelectionSubfolder {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val stem = fileName.substringBeforeLast('.')
        return when {
            extension in raw -> SelectionSubfolder.RAW
            extension in jpeg -> SelectionSubfolder.JPEG
            BundleNaming.DERIVATIVE_SUFFIXES.any { stem.endsWith(it, ignoreCase = true) } -> SelectionSubfolder.RAW
            extension == "xmp" && stem.substringAfterLast('.', "").lowercase() in raw -> SelectionSubfolder.RAW
            else -> SelectionSubfolder.ROOT
        }
    }
}

/** Android Desktop's configuration of the engine. */
val ALL_OR_NOTHING_POLICY = SafTransferPolicy(
    bundleAtomicity = BundleAtomicity.ALL_OR_NOTHING,
    markerTiming = MarkerTiming.AFTER_FIRST_TRANSFER,
)

/** PhotoTok's configuration of the engine. */
val PER_FILE_POLICY = SafTransferPolicy(
    bundleAtomicity = BundleAtomicity.PER_FILE,
    markerTiming = MarkerTiming.WHEN_FOLDER_RESOLVED,
)

/**
 * Robolectric set-up shared by the engine suites: a [FakeSafDocumentsProvider] over a temporary
 * folder, registered as a real DocumentsProvider, and an engine built with [policy].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [34], application = Application::class)
abstract class SafTestHarness(private val policy: SafTransferPolicy) {

    @get:Rule
    val tmp = TemporaryFolder()

    protected lateinit var rootDir: File
    protected lateinit var engine: SafTransferEngine

    protected val context: Context
        get() = RuntimeEnvironment.getApplication()

    protected val storage: SafStorage
        get() = SafStorage.of(context)

    protected val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(
        FakeSafDocumentsProvider.AUTHORITY,
        FakeSafDocumentsProvider.ROOT_ID,
    )

    protected fun selection(folderName: String = "Selection", root: Uri = treeUri) =
        SelectionDestination(rootUri = root, folderName = folderName)

    protected fun newEngine(p: SafTransferPolicy = policy) =
        SafTransferEngine(TestSubfolderRule, p).also { it.ioDispatcher = UnconfinedTestDispatcher() }

    @Before
    fun setUpHarness() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        rootDir = tmp.newFolder("shoot")
        FakeSafDocumentsProvider.reset(rootDir)
        Robolectric.buildContentProvider(FakeSafDocumentsProvider::class.java)
            .create(FakeSafDocumentsProvider.AUTHORITY)
        registerAsDocumentsProvider()
        engine = newEngine()
    }

    @After
    fun tearDownHarness() {
        Dispatchers.resetMain()
    }

    /**
     * A real DocumentsProvider answers the `android.content.action.DOCUMENTS_PROVIDER` intent;
     * without that, `DocumentsContract.isDocumentUri` is false and `DocumentFile.fromTreeUri`
     * on a document URI silently resolves to the tree root instead of that document.
     */
    private fun registerAsDocumentsProvider() {
        val component = android.content.ComponentName(
            context.packageName,
            FakeSafDocumentsProvider::class.java.name,
        )
        val info = android.content.pm.ProviderInfo().apply {
            authority = FakeSafDocumentsProvider.AUTHORITY
            name = component.className
            packageName = component.packageName
            exported = true
            grantUriPermissions = true
            applicationInfo = context.applicationInfo
        }
        val packageManager = org.robolectric.Shadows.shadowOf(context.packageManager)
        packageManager.addOrUpdateProvider(info)
        packageManager.addIntentFilterForProvider(
            component,
            android.content.IntentFilter("android.content.action.DOCUMENTS_PROVIDER"),
        )
    }

    protected fun documentUriFor(relativePath: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            "${FakeSafDocumentsProvider.ROOT_ID}/$relativePath",
        )

    protected suspend fun move(name: String, sorting: Boolean = true, folderName: String = "Selection") =
        engine.transferBundle(storage, listOf(documentUriFor(name)), selection(folderName), sorting, isMove = true).single()

    protected suspend fun copy(name: String, sorting: Boolean = true, folderName: String = "Selection") =
        engine.transferBundle(storage, listOf(documentUriFor(name)), selection(folderName), sorting, isMove = false).single()
}
