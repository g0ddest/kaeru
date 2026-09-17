package app.kaeru.ui.common.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.update.ApkDownload
import app.kaeru.domain.update.ApkDownloads
import app.kaeru.domain.update.UpdateFailure
import app.kaeru.domain.update.UpdateInstaller
import app.kaeru.domain.update.UpdateRepository
import app.kaeru.domain.update.UpdateResult
import app.kaeru.ui.common.toUserMessage
import app.kaeru.ui.common.updateFailureMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * The «Обновления» screen: what is installed, what is published, and the three presses between
 * them.
 *
 * Opening the screen checks, and does so unforced — the app already asked at launch, and a second
 * request a minute later would spend the hourly budget on an answer it has in hand. «Проверить» is
 * the forced one: a press is a question put directly, and answering it out of a cache would be a
 * button that does nothing.
 *
 * The download and the install are one press, not two. A viewer who asked for «Скачать и
 * установить» has said what they want; stopping at a finished file to make them press again would
 * be the app asking for confirmation it was already given. The installer still asks — it always
 * does, and nothing here can or should change that.
 */
@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val repository: UpdateRepository,
    private val downloads: ApkDownloads,
    private val installer: UpdateInstaller,
    @param:Named("versionName") installedVersion: String,
) : ViewModel() {

    private val state = MutableStateFlow(UpdateUiState(installedVersion = installedVersion))
    val uiState: StateFlow<UpdateUiState> = state.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** The file waiting for the installer, once one has arrived. */
    private var readyPath: String? = null

    init {
        check(force = false)
    }

    /**
     * Asks again. [force] is the viewer's own press; false is the screen opening.
     *
     * A check already running is left alone rather than restarted: two answers to one question
     * would race to write the same three fields, and the second press buys nothing.
     */
    fun check(force: Boolean = true) {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            state.update { it.copy(stage = UpdateStage.CHECKING, message = null) }
            repository.check(force)
                .onSuccess { result -> state.update { it.applying(result) } }
                .onFailure { failure -> onCheckFailed(failure) }
        }
    }

    /**
     * «Скачать и установить».
     *
     * The permission is asked about before the transfer rather than after it: thirty megabytes
     * fetched and then refused at the last step is thirty megabytes of somebody's data spent on
     * a question that could have been put first.
     */
    fun download() {
        val release = state.value.release ?: return
        if (downloadJob?.isActive == true) return
        if (!installer.allowed()) {
            state.update { it.copy(permissionNeeded = true, message = null) }
            return
        }
        state.update {
            it.copy(
                stage = UpdateStage.DOWNLOADING,
                downloadedBytes = 0,
                message = null,
                permissionNeeded = false,
            )
        }
        downloadJob = viewModelScope.launch {
            downloads.download(release).collect { stage ->
                when (stage) {
                    is ApkDownload.Running -> state.update {
                        // Only while the download is the thing on screen. A late progress emission
                        // arriving after a failure must not put the bar back.
                        if (it.stage == UpdateStage.DOWNLOADING) it.copy(downloadedBytes = stage.bytes) else it
                    }
                    is ApkDownload.Ready -> {
                        readyPath = stage.path
                        state.update { it.copy(stage = UpdateStage.READY) }
                        install()
                    }
                    is ApkDownload.Failed -> {
                        readyPath = null
                        state.update {
                            it.copy(
                                // Back to the offer it came from, so the button that failed is
                                // the button that is there to press again.
                                stage = UpdateStage.AVAILABLE,
                                downloadedBytes = 0,
                                message = updateFailureMessage(stage.reason),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Hands the downloaded file to the system, which is the only thing that can install it. */
    fun install() {
        val path = readyPath ?: return
        if (!installer.allowed()) {
            state.update { it.copy(permissionNeeded = true, message = null) }
            return
        }
        if (!installer.install(path)) {
            state.update { it.copy(message = updateFailureMessage(UpdateFailure.INSTALLER_REFUSED)) }
        }
    }

    /** Opens the system screen where installs from this app are allowed. */
    fun allowInstalls() {
        if (!installer.requestPermission()) {
            state.update { it.copy(message = updateFailureMessage(UpdateFailure.INSTALLER_REFUSED)) }
        }
    }

    /**
     * The screen is back in front of the viewer, which is the only signal there is that the
     * system's permission screen has been answered.
     *
     * Android reports nothing when it is dismissed — there is no result to listen for — so the
     * answer is read by asking again, and the press that was interrupted carries on from where it
     * stopped: installing a file that is already here, or fetching one that is not.
     */
    fun resumed() {
        if (!state.value.permissionNeeded || !installer.allowed()) return
        state.update { it.copy(permissionNeeded = false) }
        if (readyPath != null) install() else download()
    }

    /**
     * A check that failed still leaves the last real answer on screen.
     *
     * That is the whole of what «offline: show the last known result» means: the stored result is
     * read back and shown under the failure, so a screen opened in a tunnel says «доступна версия
     * 0.4.0» and «нет связи» together rather than losing the first.
     */
    private suspend fun onCheckFailed(failure: Throwable) {
        val known = repository.lastResult.first()
        state.update { current ->
            // A download in flight is not interrupted by a check that failed beside it.
            if (current.stage == UpdateStage.DOWNLOADING || current.stage == UpdateStage.READY) {
                current.copy(message = failure.toUserMessage())
            } else {
                current.applying(known).copy(message = failure.toUserMessage())
            }
        }
    }
}

/** One stored answer, as the three fields of the screen it decides. */
private fun UpdateUiState.applying(result: UpdateResult?): UpdateUiState = copy(
    stage = when {
        result == null -> UpdateStage.UNKNOWN
        result.release != null -> UpdateStage.AVAILABLE
        else -> UpdateStage.UP_TO_DATE
    },
    checkedAt = result?.checkedAt,
    release = result?.release,
    message = null,
)
