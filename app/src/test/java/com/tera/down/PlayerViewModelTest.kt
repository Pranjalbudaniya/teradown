package com.tera.down

import androidx.media3.common.Player
import com.tera.down.core.player.PlayerEventListener
import com.tera.down.core.player.PlayerStatus
import com.tera.down.core.player.VideoPlayerController
import com.tera.down.feature.player.PlaybackPositionTracker
import com.tera.down.feature.player.PlayerViewModel
import com.tera.down.feature.queue.LibraryRepository
import com.tera.down.feature.queue.LibraryRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeVideoPlayerController : VideoPlayerController {
    override var currentPositionMs: Long = 0L
    override var durationMs: Long = 60_000L
    override var bufferedPositionMs: Long = 30_000L
    override var isPlaying: Boolean = false
    private var internalSpeed: Float = 1.0f
    override val playbackSpeed: Float
        get() = internalSpeed
    override val exoPlayer: Player? = null

    var isPrepared: Boolean = false
    var isReleased: Boolean = false
    var preparedUri: String? = null
    var preparedInitialPosition: Long = 0L

    private val listeners = mutableListOf<PlayerEventListener>()

    override fun prepare(uri: String, initialPositionMs: Long) {
        isPrepared = true
        preparedUri = uri
        preparedInitialPosition = initialPositionMs
        currentPositionMs = initialPositionMs
        listeners.forEach {
            it.onPlaybackStateChanged(PlayerStatus.READY)
            it.onDurationChanged(durationMs)
        }
    }

    override fun play() {
        isPlaying = true
        listeners.forEach { it.onIsPlayingChanged(true) }
    }

    override fun pause() {
        isPlaying = false
        listeners.forEach { it.onIsPlayingChanged(false) }
    }

    override fun seekTo(positionMs: Long) {
        currentPositionMs = positionMs
        listeners.forEach { it.onPositionDiscontinuity(positionMs) }
    }

    override fun setPlaybackSpeed(speed: Float) {
        internalSpeed = speed
    }

    override fun release() {
        isReleased = true
        isPlaying = false
    }

    override fun addListener(listener: PlayerEventListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: PlayerEventListener) {
        listeners.remove(listener)
    }

    fun simulateError(message: String, canRetry: Boolean) {
        listeners.forEach { it.onPlayerError(message, canRetry) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var fakeDao: FakeLibraryMediaDao
    private lateinit var repository: LibraryRepository
    private lateinit var tracker: PlaybackPositionTracker
    private lateinit var fakeController: FakeVideoPlayerController
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeLibraryMediaDao()
        repository = LibraryRepositoryImpl(fakeDao, testDispatcher)
        tracker = PlaybackPositionTracker(
            libraryRepository = repository,
            scope = testScope,
            ioDispatcher = testDispatcher
        )
        fakeController = FakeVideoPlayerController()
        viewModel = PlayerViewModel(
            playerController = fakeController,
            positionTracker = tracker,
            libraryRepository = repository,
            coroutineScope = testScope.backgroundScope
        )
    }

    @After
    fun tearDown() {
        viewModel.release()
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isIdle() {
        val state = viewModel.uiState.value
        assertEquals(PlayerStatus.IDLE, state.status)
        assertFalse(state.isPlaying)
        assertEquals(0L, state.currentPositionMs)
    }

    @Test
    fun loadMedia_withValidLocalFile_preparesPlayerAndSetsReady() = runTest(testDispatcher) {
        val testFile = tempFolder.newFile("test_movie.mp4")
        testFile.writeText("fake video data")

        viewModel.loadMedia(
            rawUri = testFile.absolutePath,
            title = "Test Movie",
            isOnline = false,
            mediaId = "vid_1"
        )

        assertTrue(fakeController.isPrepared)
        assertEquals(testFile.toURI().toString(), fakeController.preparedUri)
        val state = viewModel.uiState.value
        assertEquals(PlayerStatus.READY, state.status)
        assertEquals("Test Movie", state.title)
        assertFalse(state.isOnline)
    }

    @Test
    fun loadMedia_withMissingFile_setsErrorState() = runTest(testDispatcher) {
        viewModel.loadMedia(
            rawUri = "/storage/non_existent_file.mp4",
            title = "Missing Video",
            isOnline = false
        )

        assertFalse(fakeController.isPrepared)
        val state = viewModel.uiState.value
        assertEquals(PlayerStatus.ERROR, state.status)
        assertNotNull(state.errorMessage)
        assertFalse(state.canRetry)
    }

    @Test
    fun loadMedia_withInsecureHttpUrl_setsErrorState() = runTest(testDispatcher) {
        viewModel.loadMedia(
            rawUri = "http://terabox.com/stream.mp4",
            title = "Insecure Video",
            isOnline = true
        )

        assertFalse(fakeController.isPrepared)
        val state = viewModel.uiState.value
        assertEquals(PlayerStatus.ERROR, state.status)
        assertNotNull(state.errorMessage)
    }

    @Test
    fun loadMedia_withValidHttpsOnlineUrl_preparesPlayer() = runTest(testDispatcher) {
        val streamUrl = "https://d.terabox.app/video/stream.mp4?auth=valid"

        viewModel.loadMedia(
            rawUri = streamUrl,
            title = "Direct Online Stream",
            isOnline = true,
            mediaId = "fs_100"
        )

        assertTrue(fakeController.isPrepared)
        assertEquals(streamUrl, fakeController.preparedUri)
        val state = viewModel.uiState.value
        assertEquals(PlayerStatus.READY, state.status)
        assertTrue(state.isOnline)
    }

    @Test
    fun playAndPause_togglesPlayingState() = runTest(testDispatcher) {
        val testFile = tempFolder.newFile("play_pause.mp4")
        testFile.writeText("data")

        viewModel.loadMedia(testFile.absolutePath, "Play Pause Test", isOnline = false)

        viewModel.play()
        assertTrue(viewModel.uiState.value.isPlaying)
        assertTrue(fakeController.isPlaying)

        viewModel.pause()
        assertFalse(viewModel.uiState.value.isPlaying)
        assertFalse(fakeController.isPlaying)

        viewModel.togglePlayPause()
        assertTrue(viewModel.uiState.value.isPlaying)

        viewModel.togglePlayPause()
        assertFalse(viewModel.uiState.value.isPlaying)
    }

    @Test
    fun seekForwardAndBackward_updatesPositionCorrectly() = runTest(testDispatcher) {
        val testFile = tempFolder.newFile("seek_test.mp4")
        testFile.writeText("data")

        viewModel.loadMedia(testFile.absolutePath, "Seek Test", isOnline = false)

        viewModel.seekTo(20_000L)
        assertEquals(20_000L, viewModel.uiState.value.currentPositionMs)
        assertEquals(20_000L, fakeController.currentPositionMs)

        viewModel.seekForward(10_000L)
        assertEquals(30_000L, viewModel.uiState.value.currentPositionMs)

        viewModel.seekBackward(10_000L)
        assertEquals(20_000L, viewModel.uiState.value.currentPositionMs)
    }

    @Test
    fun setPlaybackSpeed_updatesControllerAndState() = runTest(testDispatcher) {
        viewModel.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, viewModel.uiState.value.playbackSpeed, 0.01f)
        assertEquals(1.5f, fakeController.playbackSpeed, 0.01f)

        viewModel.setPlaybackSpeed(2.0f)
        assertEquals(2.0f, viewModel.uiState.value.playbackSpeed, 0.01f)
    }

    @Test
    fun toggleControlsVisibility_updatesState() {
        assertTrue(viewModel.uiState.value.areControlsVisible)
        viewModel.toggleControlsVisibility()
        assertFalse(viewModel.uiState.value.areControlsVisible)
        viewModel.toggleControlsVisibility()
        assertTrue(viewModel.uiState.value.areControlsVisible)
    }

    @Test
    fun retryPlayback_reloadsMedia() = runTest(testDispatcher) {
        val testFile = tempFolder.newFile("retry_test.mp4")
        testFile.writeText("data")

        viewModel.loadMedia(testFile.absolutePath, "Retry Test", isOnline = false)
        fakeController.simulateError("Connection dropped", canRetry = true)

        assertEquals(PlayerStatus.ERROR, viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.canRetry)

        viewModel.retryPlayback()
        assertEquals(PlayerStatus.READY, viewModel.uiState.value.status)
    }
}
