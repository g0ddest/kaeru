package app.kaeru.ui.common.update

import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.update.ApkDownload
import app.kaeru.domain.update.ApkDownloads
import app.kaeru.domain.update.UpdateFailed
import app.kaeru.domain.update.UpdateFailure
import app.kaeru.domain.update.UpdateInstaller
import app.kaeru.domain.update.UpdateRelease
import app.kaeru.domain.update.FakeUpdateRepository
import app.kaeru.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * The screen as values, on virtual time: what it says while it asks, what it says when it has an
 * answer, and what happens between the one press and the system installer opening.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repository = FakeUpdateRepository()
    private val downloads = RecordingDownloads()
    private val installer = RecordingInstaller()

    private fun viewModel() = UpdatesViewModel(repository, downloads, installer, "0.3.0")

    /** A download whose stages the test writes, so progress can be observed without a network. */
    private class RecordingDownloads : ApkDownloads {
        var stages: List<ApkDownload> = listOf(ApkDownload.Ready("/cache/updates/Kaeru-0.4.0.apk"))
        val asked = mutableListOf<UpdateRelease>()

        override fun download(release: UpdateRelease): Flow<ApkDownload> {
            asked += release
            return flow { stages.forEach { emit(it) } }
        }
    }

    private class RecordingInstaller(
        var allowed: Boolean = true,
        var installs: Boolean = true,
    ) : UpdateInstaller {
        val installed = mutableListOf<String>()
        var permissionRequests = 0

        override fun allowed() = allowed

        override fun requestPermission(): Boolean {
            permissionRequests++
            return true
        }

        override fun install(path: String): Boolean {
            installed += path
            return installs
        }
    }

    @Test
    fun `the screen opens by asking, without forcing`() = runTest {
        repository.result = FakeUpdateRepository.found()

        val vm = viewModel()
        advanceUntilIdle()

        // Unforced: the app already asked at launch, and a second request a minute later would
        // spend the hourly budget on an answer it has in hand.
        assertEquals(listOf(false), repository.checks)
        assertEquals(UpdateStage.AVAILABLE, vm.uiState.value.stage)
        assertEquals("0.4.0", vm.uiState.value.release?.version)
        assertEquals("0.3.0", vm.uiState.value.installedVersion)
    }

    @Test
    fun `nothing newer reads as up to date, with the date of the check`() = runTest {
        repository.result = FakeUpdateRepository.found(release = null)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(UpdateStage.UP_TO_DATE, vm.uiState.value.stage)
        assertNull(vm.uiState.value.release)
        assertEquals(FakeUpdateRepository.found().checkedAt, vm.uiState.value.checkedAt)
    }

    @Test
    fun `pressing the button forces a check`() = runTest {
        repository.result = FakeUpdateRepository.found()
        val vm = viewModel()
        advanceUntilIdle()

        vm.check()
        advanceUntilIdle()

        assertEquals(listOf(false, true), repository.checks)
    }

    /** The whole of one press: check, progress, ready, and the installer opening by itself. */
    @Test
    fun `check then download then ready to install`() = runTest {
        repository.result = FakeUpdateRepository.found()
        downloads.stages = listOf(
            ApkDownload.Running(0, 31_457_280),
            ApkDownload.Running(15_728_640, 31_457_280),
            ApkDownload.Ready("/cache/updates/Kaeru-0.4.0.apk"),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.download()
        advanceUntilIdle()

        assertEquals(1, downloads.asked.size)
        assertEquals("0.4.0", downloads.asked.single().version)
        assertEquals(UpdateStage.READY, vm.uiState.value.stage)
        // The file goes to the system without a second press: the viewer already said «скачать и
        // установить», and asking again would be the app doubting an answer it was given.
        assertEquals(listOf("/cache/updates/Kaeru-0.4.0.apk"), installer.installed)
    }

    @Test
    fun `the bar fills as the bytes arrive`() = runTest {
        repository.result = FakeUpdateRepository.found()
        downloads.stages = listOf(ApkDownload.Running(15_728_640, 31_457_280))
        val vm = viewModel()
        advanceUntilIdle()

        vm.download()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(UpdateStage.DOWNLOADING, state.stage)
        assertEquals(15_728_640L, state.downloadedBytes)
        assertEquals(0.5f, state.progress, 0.001f)
    }

    @Test
    fun `a corrupted file says so and leaves the button where it was`() = runTest {
        repository.result = FakeUpdateRepository.found()
        downloads.stages = listOf(
            ApkDownload.Running(1024, 31_457_280),
            ApkDownload.Failed(UpdateFailure.CORRUPTED),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.download()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(UpdateStage.AVAILABLE, state.stage)
        assertEquals("Файл повреждён, попробуйте ещё раз", state.message)
        assertEquals(0L, state.downloadedBytes)
        assertTrue("nothing should have reached the installer", installer.installed.isEmpty())
    }

    /**
     * The permission is asked about before the transfer, not after it: thirty megabytes fetched
     * and then refused is somebody's data spent on a question that could have been put first.
     */
    @Test
    fun `without the install permission nothing is downloaded and the screen explains`() = runTest {
        repository.result = FakeUpdateRepository.found()
        installer.allowed = false
        val vm = viewModel()
        advanceUntilIdle()

        vm.download()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.permissionNeeded)
        assertEquals(UpdateStage.AVAILABLE, vm.uiState.value.stage)
        assertTrue("no transfer should have started", downloads.asked.isEmpty())
    }

    @Test
    fun `coming back with the permission granted carries the press on`() = runTest {
        repository.result = FakeUpdateRepository.found()
        installer.allowed = false
        val vm = viewModel()
        advanceUntilIdle()
        vm.download()
        advanceUntilIdle()

        installer.allowed = true
        vm.resumed()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.permissionNeeded)
        assertEquals(UpdateStage.READY, vm.uiState.value.stage)
        assertEquals(1, downloads.asked.size)
    }

    @Test
    fun `coming back without it changes nothing`() = runTest {
        repository.result = FakeUpdateRepository.found()
        installer.allowed = false
        val vm = viewModel()
        advanceUntilIdle()
        vm.download()
        advanceUntilIdle()

        vm.resumed()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.permissionNeeded)
        assertTrue(downloads.asked.isEmpty())
    }

    @Test
    fun `an installer that will not open is said out loud`() = runTest {
        repository.result = FakeUpdateRepository.found()
        installer.installs = false
        val vm = viewModel()
        advanceUntilIdle()

        vm.download()
        advanceUntilIdle()

        assertEquals("Android не открыл установщик", vm.uiState.value.message)
        assertEquals(UpdateStage.READY, vm.uiState.value.stage)
    }

    /** The offline rule: a failure never takes away the answer the device already had. */
    @Test
    fun `a failed check keeps the release it already knew about`() = runTest {
        repository.remember(FakeUpdateRepository.found())
        repository.failure = UpdateFailed(UpdateFailure.NO_NETWORK, IOException("no route"))

        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(UpdateStage.AVAILABLE, state.stage)
        assertEquals("0.4.0", state.release?.version)
        assertEquals("Нет связи", state.message)
    }

    @Test
    fun `a first check that fails on a device that knows nothing says nothing it cannot`() = runTest {
        repository.failure = UpdateFailed(UpdateFailure.RATE_LIMITED)

        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        // Never «у вас последняя версия»: nothing was ever learned, and saying otherwise is a lie.
        assertEquals(UpdateStage.UNKNOWN, state.stage)
        assertEquals("GitHub ограничил запросы, попробуйте через час", state.message)
    }

    @Test
    fun `a failure that is not the updater's own is still named`() = runTest {
        repository.failure = NetworkUnavailable(IOException("down"))

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("Нет соединения. Проверьте интернет", vm.uiState.value.message)
    }

    @Test
    fun `a second press while a download is running does not start another`() = runTest {
        repository.result = FakeUpdateRepository.found()
        downloads.stages = listOf(ApkDownload.Running(1024, 31_457_280))
        val vm = viewModel()
        advanceUntilIdle()

        vm.download()
        vm.download()
        advanceUntilIdle()

        assertEquals(1, downloads.asked.size)
    }
}
