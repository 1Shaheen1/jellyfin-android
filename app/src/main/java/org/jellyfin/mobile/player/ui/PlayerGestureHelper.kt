package org.jellyfin.mobile.player.ui

import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import androidx.media3.ui.PlayerView
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.databinding.FragmentPlayerBinding
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.brightness
import org.jellyfin.mobile.utils.dip
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class PlayerGestureHelper(
    private val fragment: PlayerFragment,
    private val playerBinding: FragmentPlayerBinding,
    private val playerLockScreenHelper: PlayerLockScreenHelper,
) : KoinComponent {
    private val appPreferences: AppPreferences by inject()
    private val audioManager: AudioManager by lazy { fragment.requireActivity().getSystemService()!! }
    private val playerView: PlayerView by playerBinding::playerView
    private val gestureIndicatorOverlayLayout: LinearLayout by playerBinding::gestureOverlayLayout
    private val gestureIndicatorOverlayImage: ImageView by playerBinding::gestureOverlayImage
    private val gestureIndicatorOverlayProgress: ProgressBar by playerBinding::gestureOverlayProgress
    private val seekOverlayLayout: LinearLayout by playerBinding::seekOverlayLayout
    private val seekOverlayImage: ImageView by playerBinding::seekOverlayImage
    private val seekOverlayText: TextView by playerBinding::seekOverlayText
    private val seekPositionText: TextView by playerBinding::seekPositionText
    private val seekOverlayProgress: ProgressBar by playerBinding::seekOverlayProgress
    private val zoomOverlayText: TextView by playerBinding::zoomOverlayText
    private var isOnPressingSpeedUp = false

    init {
        if (appPreferences.exoPlayerRememberBrightness) {
            fragment.requireActivity().window.brightness = appPreferences.exoPlayerBrightness
        }
    }

    /**
     * Zoom of the video picture, 1 fits the video to the screen. Changed freely with a pinch gesture in landscape.
     * Only the video surface gets scaled, so subtitles stay inside the visible area.
     */
    private var zoomScale = 1f

    /**
     * Zoom without snapping to "fit" or "fill screen", so a pinch can move past the snap points.
     */
    private var unsnappedZoomScale = 1f

    /**
     * Offset of the zoomed picture from the screen center, moved by pinching with two fingers.
     */
    private var zoomPanX = 0f
    private var zoomPanY = 0f

    /**
     * Pinch focus point of the previous scale event, relative to the screen center.
     */
    private var lastZoomFocusX = 0f
    private var lastZoomFocusY = 0f

    /**
     * Tracks whether a pinch happened since the first finger touched the screen,
     * so lifting one finger doesn't turn the rest of the gesture into a swipe.
     */
    private var isZooming = false

    /**
     * Tracks a value during a swipe gesture (between multiple onScroll calls).
     * When the gesture starts it's reset to an initial value and gets increased or decreased
     * (depending on the direction) as the gesture progresses.
     */
    private var swipeGestureValueTracker = -1f

    private enum class GestureDirection {
        NONE,
        HORIZONTAL,
        VERTICAL,
    }

    /**
     * Tracks the current active swipe gesture.
     */
    private var currentGesture = GestureDirection.NONE

    /**
     * Tracks whether a horizontal swipe seek gesture is in progress.
     */
    private var isHorizontalSeeking = false

    /**
     * Tracks accumulated seek time during horizontal swipe (in milliseconds).
     */
    private var seekTimeAccumulator = 0L

    /**
     * Tracks the initial playback position when seek gesture started.
     */
    private var seekStartPosition = 0L

    /**
     * Tracks total duration of current media.
     */
    private var mediaDuration = 0L

    /**
     * Runnable that hides [playerView] controller
     */
    private val hidePlayerViewControllerAction = Runnable {
        playerView.hideController()
    }

    /**
     * Runnable that hides [gestureIndicatorOverlayLayout]
     */
    private val hideGestureIndicatorOverlayAction = Runnable {
        gestureIndicatorOverlayLayout.isVisible = false
    }

    /**
     * Runnable that hides [seekOverlayLayout]
     */
    private val hideSeekOverlayAction = Runnable {
        seekOverlayLayout.isVisible = false
    }

    /**
     * Runnable that hides [zoomOverlayText]
     */
    private val hideZoomOverlayAction = Runnable {
        zoomOverlayText.isVisible = false
    }

    /**
     * Handles taps when controls are locked
     */
    private val unlockDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerLockScreenHelper.peekUnlockButton()
                return true
            }
        },
    )

    /**
     * Handles double tap to seek and brightness/volume gestures
     */
    private val gestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val viewWidth = playerView.measuredWidth
                val viewHeight = playerView.measuredHeight
                val viewCenterX = viewWidth / 2
                val viewCenterY = viewHeight / 2
                val isFastForward = e.x.toInt() > viewCenterX

                // Show ripple effect
                playerBinding.seekRippleOverlay.foreground?.apply {
                    val left = if (isFastForward) viewCenterX else 0
                    val right = if (isFastForward) viewWidth else viewCenterX
                    setBounds(left, viewCenterY - viewCenterX / 2, right, viewCenterY + viewCenterX / 2)
                    setHotspot(e.x, e.y)
                    state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
                    playerView.postDelayed(Constants.DOUBLE_TAP_RIPPLE_DURATION_MS) {
                        state = IntArray(0)
                    }
                }

                // Fast-forward/rewind
                with(fragment) { if (isFastForward) onFastForward() else onRewind() }

                // Cancel previous runnable to not hide controller while seeking
                playerView.removeCallbacks(hidePlayerViewControllerAction)

                // Ensure controller gets hidden after seeking
                playerView.postDelayed(hidePlayerViewControllerAction, Constants.DEFAULT_CONTROLS_TIMEOUT_MS.toLong())
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerView.apply {
                    if (!isControllerFullyVisible) showController() else hideController()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!appPreferences.exoPlayerAllowPressSpeedUp) {
                    return
                }

                with(fragment) {
                    isOnPressingSpeedUp = true
                    onPressSpeedUp(true)
                }
            }

            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (firstEvent == null) return false

                // Check whether swipe was started in excluded region (vertical)
                val exclusionSizeVertical = playerView.resources.dip(Constants.SWIPE_GESTURE_EXCLUSION_SIZE_VERTICAL)
                if (
                    firstEvent.y < exclusionSizeVertical ||
                    firstEvent.y > playerView.height - exclusionSizeVertical
                ) {
                    return false
                }

                // Determine/lock gesture direction
                if (currentGesture == GestureDirection.NONE) {
                    val deltaX = currentEvent.x - firstEvent.x
                    val deltaY = currentEvent.y - firstEvent.y

                    currentGesture = if (abs(deltaX) > abs(deltaY) * 2) GestureDirection.HORIZONTAL
                    else if (abs(deltaY) > abs(deltaX) * 2) GestureDirection.VERTICAL
                    else return false
                }

                // Handle horizontal swipe for seek
                if (currentGesture == GestureDirection.HORIZONTAL && appPreferences.exoPlayerAllowHorizontalGesture) {
                    // Check horizontal exclusion zones (edges of screen)
                    val exclusionSizeHorizontal = playerView.resources.dip(Constants.SWIPE_GESTURE_EXCLUSION_SIZE_HORIZONTAL)
                    if (
                        firstEvent.x < exclusionSizeHorizontal ||
                        firstEvent.x > playerView.width - exclusionSizeHorizontal
                    ) {
                        return false
                    }

                    // Hide vertical overlay
                    gestureIndicatorOverlayLayout.isVisible = false
                    gestureIndicatorOverlayLayout.removeCallbacks(hideGestureIndicatorOverlayAction)

                    // Initialize seek start position on first swipe
                    if (!isHorizontalSeeking) {
                        val player = playerView.player
                        if (player != null) {
                            seekStartPosition = player.currentPosition
                            mediaDuration = player.duration.coerceAtLeast(0)
                        }
                    }

                    isHorizontalSeeking = true

                    // Calculate seek time with non-linear acceleration
                    // The further you swipe, the faster the seek time increases
                    val baseSeekDeltaMs = (-distanceX * 1000 / Constants.HORIZONTAL_SWIPE_DISTANCE_PER_SECOND).toLong()

                    // Apply acceleration based on accumulated distance
                    // Use portrait acceleration (2x) for portrait mode, default for landscape
                    val accelerationFactor = if (fragment.isLandscape()) {
                        Constants.SEEK_ACCELERATION_FACTOR
                    } else {
                        Constants.SEEK_ACCELERATION_FACTOR_PORTRAIT
                    }
                    val currentSeekSeconds = abs(seekTimeAccumulator / 1000f)
                    val accelerationMultiplier = 1f + (currentSeekSeconds / 30f) * (accelerationFactor - 1f)
                    val acceleratedSeekDelta = (baseSeekDeltaMs * accelerationMultiplier).toLong()

                    seekTimeAccumulator += acceleratedSeekDelta

                    // Clamp the accumulated seek time to valid range
                    val minSeek = -seekStartPosition
                    val maxSeek = if (mediaDuration > 0) mediaDuration - seekStartPosition else Long.MAX_VALUE
                    // Allow seeking up to media duration (if known). Do not enforce an artificial MAX_SEEK_TIME_MS limit.
                    seekTimeAccumulator = seekTimeAccumulator.coerceIn(minSeek, maxSeek)

                    // Update the seek overlay with mm:ss format
                    val totalSeconds = abs(seekTimeAccumulator / 1000)
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60
                    val timeFormatted = String.format(Locale.US, "%02d:%02d", minutes, seconds)
                    val seekText = if (seekTimeAccumulator >= 0) "+$timeFormatted" else "-$timeFormatted"
                    seekOverlayText.text = seekText

                    // Update position text (current position / duration)
                    val targetPosition = (seekStartPosition + seekTimeAccumulator).coerceIn(0, mediaDuration)
                    seekPositionText.text = "${formatTime(targetPosition)} / ${formatTime(mediaDuration)}"

                    // Update progress bar
                    if (mediaDuration > 0) {
                        seekOverlayProgress.max = 1000
                        seekOverlayProgress.progress = (targetPosition * 1000 / mediaDuration).toInt()
                    }

                    // Set appropriate icon based on direction
                    val iconRes = if (seekTimeAccumulator >= 0) {
                        R.drawable.ic_fast_forward_black_32dp
                    } else {
                        R.drawable.ic_rewind_black_32dp
                    }
                    seekOverlayImage.setImageResource(iconRes)

                    seekOverlayLayout.isVisible = true
                    return true
                } else if (currentGesture == GestureDirection.HORIZONTAL && !appPreferences.exoPlayerAllowHorizontalGesture) {
                    // If horizontal gesture is disabled while a gesture was in progress, reset the state
                    currentGesture = GestureDirection.NONE
                    isHorizontalSeeking = false
                    seekTimeAccumulator = 0L
                    seekStartPosition = 0L
                    mediaDuration = 0L
                    seekOverlayLayout.isVisible = false
                }


                if (!appPreferences.exoPlayerAllowSwipeGestures) {
                    return false
                }
                // Handle vertical swipe for brightness/volume (existing logic)
                if (currentGesture != GestureDirection.VERTICAL) {
                    return false
                }

                // Hide horizontal overlay
                seekOverlayLayout.isVisible = false
                seekOverlayLayout.removeCallbacks(hideSeekOverlayAction)

                val viewCenterX = playerView.measuredWidth / 2

                // Distance to swipe to go from min to max
                val distanceFull = playerView.measuredHeight * Constants.FULL_SWIPE_RANGE_SCREEN_RATIO
                val ratioChange = distanceY / distanceFull

                if (firstEvent.x.toInt() > viewCenterX) {
                    // Swiping on the right, change volume

                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (swipeGestureValueTracker == -1f) swipeGestureValueTracker = currentVolume.toFloat()

                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val change = ratioChange * maxVolume
                    swipeGestureValueTracker += change

                    val toSet = swipeGestureValueTracker.toInt().coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, toSet, 0)

                    gestureIndicatorOverlayImage.setImageResource(R.drawable.ic_volume_white_24dp)
                    gestureIndicatorOverlayProgress.max = maxVolume
                    gestureIndicatorOverlayProgress.progress = toSet
                } else {
                    // Swiping on the left, change brightness

                    val window = fragment.requireActivity().window
                    val brightnessRange = BRIGHTNESS_OVERRIDE_OFF..BRIGHTNESS_OVERRIDE_FULL

                    // Initialize on first swipe
                    if (swipeGestureValueTracker == -1f) {
                        val brightness = window.brightness
                        swipeGestureValueTracker = when (brightness) {
                            in brightnessRange -> brightness
                            else -> {
                                Settings.System.getFloat(
                                    fragment.requireActivity().contentResolver,
                                    Settings.System.SCREEN_BRIGHTNESS,
                                ) / Constants.SCREEN_BRIGHTNESS_MAX
                            }
                        }
                    }

                    swipeGestureValueTracker = (swipeGestureValueTracker + ratioChange).coerceIn(brightnessRange)
                    window.brightness = swipeGestureValueTracker
                    if (appPreferences.exoPlayerRememberBrightness) {
                        appPreferences.exoPlayerBrightness = swipeGestureValueTracker
                    }

                    gestureIndicatorOverlayImage.setImageResource(R.drawable.ic_brightness_white_24dp)
                    gestureIndicatorOverlayProgress.max = Constants.PERCENT_MAX
                    gestureIndicatorOverlayProgress.progress = (swipeGestureValueTracker * Constants.PERCENT_MAX).toInt()
                }

                gestureIndicatorOverlayLayout.isVisible = true
                return true
            }
        },
    )

    /**
     * Handles scale/zoom gesture
     */
    private val zoomGestureDetector = ScaleGestureDetector(
        playerView.context,
        object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (!isZoomAllowed()) return false
                isZooming = true
                unsnappedZoomScale = zoomScale
                lastZoomFocusX = detector.focusX - playerView.width / 2f
                lastZoomFocusY = detector.focusY - playerView.height / 2f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                unsnappedZoomScale = (unsnappedZoomScale * detector.scaleFactor)
                    .coerceIn(Constants.ZOOM_SCALE_MIN, Constants.ZOOM_SCALE_MAX)
                val newScale = snapZoomScale(unsnappedZoomScale)

                // Keep the part of the picture between the fingers under them, also when they move
                val focusX = detector.focusX - playerView.width / 2f
                val focusY = detector.focusY - playerView.height / 2f
                val scaleChange = newScale / zoomScale
                zoomPanX = focusX - (lastZoomFocusX - zoomPanX) * scaleChange
                zoomPanY = focusY - (lastZoomFocusY - zoomPanY) * scaleChange
                lastZoomFocusX = focusX
                lastZoomFocusY = focusY
                zoomScale = newScale
                updateVideoZoom()

                zoomOverlayText.text = String.format(Locale.US, "%d%%", (zoomScale * 100).roundToInt())
                zoomOverlayText.removeCallbacks(hideZoomOverlayAction)
                zoomOverlayText.isVisible = true
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                zoomOverlayText.postDelayed(hideZoomOverlayAction, Constants.ZOOM_OVERLAY_TIMEOUT_MS.toLong())
            }
        },
    ).apply { isQuickScaleEnabled = false }

    init {
        @Suppress("ClickableViewAccessibility")
        playerView.setOnTouchListener { _, event ->
            if (playerView.useController) {
                when (event.pointerCount) {
                    1 -> if (!isZooming) gestureDetector.onTouchEvent(event)
                    2 -> {
                        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                            // Stop a long press or swipe started by the first finger
                            val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            gestureDetector.onTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }
                        zoomGestureDetector.onTouchEvent(event)
                    }
                }
            } else {
                unlockDetector.onTouchEvent(event)
            }
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                if (isOnPressingSpeedUp) {
                    isOnPressingSpeedUp = false
                    with(fragment) {
                        onPressSpeedUp(false)
                    }
                }

                // Handle horizontal seek gesture completion
                if (event.action == MotionEvent.ACTION_UP && !isZooming && currentGesture == GestureDirection.HORIZONTAL && isHorizontalSeeking && seekTimeAccumulator != 0L) {
                    fragment.onSeekByOffset(seekTimeAccumulator)
                    seekOverlayLayout.apply {
                        removeCallbacks(hideSeekOverlayAction)
                        postDelayed(
                            hideSeekOverlayAction,
                            Constants.DEFAULT_CENTER_OVERLAY_TIMEOUT_MS.toLong(),
                        )
                    }
                }
                currentGesture = GestureDirection.NONE
                isHorizontalSeeking = false
                isZooming = false
                seekTimeAccumulator = 0L
                seekStartPosition = 0L
                mediaDuration = 0L

                // Hide gesture indicator after timeout, if shown
                gestureIndicatorOverlayLayout.apply {
                    if (isVisible) {
                        removeCallbacks(hideGestureIndicatorOverlayAction)
                        postDelayed(
                            hideGestureIndicatorOverlayAction,
                            Constants.DEFAULT_CENTER_OVERLAY_TIMEOUT_MS.toLong(),
                        )
                    }
                }
                swipeGestureValueTracker = -1f
            }
            true
        }
    }

    init {
        playerView.videoSurfaceView?.let { surfaceView ->
            // Let the zoomed picture extend over the black bars around the video frame,
            // which needs the frame (the surface's parent) and the player view to not clip their children
            (surfaceView.parent as? ViewGroup)?.clipChildren = false
            playerView.clipChildren = false

            // The surface size changes with the video's aspect ratio, which changes the allowed panning range
            surfaceView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateVideoZoom()
            }
        }
    }

    fun handleConfiguration(newConfig: Configuration) {
        updateVideoZoom(isZoomAllowed(newConfig))
    }

    private fun isZoomAllowed(configuration: Configuration = fragment.resources.configuration): Boolean {
        val isInPictureInPicture = AndroidVersion.isAtLeastN && fragment.requireActivity().isInPictureInPictureMode
        return fragment.isLandscape(configuration) && !isInPictureInPicture
    }

    /**
     * Snaps [scale] to fitting the video into the screen or filling the screen with it, when it's close to either.
     */
    private fun snapZoomScale(scale: Float): Float {
        val surfaceView = playerView.videoSurfaceView
        val fillScale = when {
            surfaceView == null || surfaceView.width == 0 || surfaceView.height == 0 -> 1f
            else -> max(
                playerView.width.toFloat() / surfaceView.width,
                playerView.height.toFloat() / surfaceView.height,
            )
        }
        return listOf(1f, fillScale).firstOrNull { snapScale ->
            abs(scale - snapScale) < Constants.ZOOM_SNAP_THRESHOLD
        } ?: scale
    }

    /**
     * Applies [zoomScale] and the panning offset to the video surface, or resets them if zoom isn't [allowed].
     */
    private fun updateVideoZoom(allowed: Boolean = isZoomAllowed()) {
        val surfaceView = playerView.videoSurfaceView ?: return
        val scale = if (allowed) zoomScale else 1f

        // Don't allow moving an edge of the picture into the screen
        val maxPanX = max(0f, (surfaceView.width * scale - playerView.width) / 2)
        val maxPanY = max(0f, (surfaceView.height * scale - playerView.height) / 2)
        if (allowed) {
            zoomPanX = zoomPanX.coerceIn(-maxPanX, maxPanX)
            zoomPanY = zoomPanY.coerceIn(-maxPanY, maxPanY)
        }

        surfaceView.scaleX = scale
        surfaceView.scaleY = scale
        surfaceView.translationX = if (allowed) zoomPanX else 0f
        surfaceView.translationY = if (allowed) zoomPanY else 0f
    }

    /**
     * Format time in milliseconds to mm:ss or h:mm:ss format
     */
    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
