package com.daigorian.epcltvapp

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import androidx.leanback.media.PlaybackBaseControlGlue
import androidx.leanback.media.PlaybackGlueHost
import androidx.leanback.media.PlayerAdapter
import androidx.leanback.media.SurfaceHolderGlueHost
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.MediaPlayer.*
import java.io.IOException
import java.util.*
import kotlin.math.roundToLong

/**
 * This implementation extends the [PlayerAdapter] with a [org.videolan.libvlc.MediaPlayer].
 */
class VlcPlayerAdapter(var mContext: Context) : PlayerAdapter() {
    /**
     * Return the VlcPlayer associated with the VlcPlayerAdapter. App can use the instance
     * to config DRM or control volumes, etc.
     *
     * @return The VlcPlayer associated with the VlcPlayerAdapter.
     */

    // For LibVLC
    private val args: ArrayList<String?> = arrayListOf("")
    private val mLibVLC: LibVLC = LibVLC(mContext, args)
    private val vlcPlayer = MediaPlayer(mLibVLC)

    var mSurfaceHolderGlueHost: SurfaceHolderGlueHost? = null

    var mInitialized = false // true when the VlcPlayer is prepared/initialized
    var mMediaSourceUri: Uri? = null
    var mHasDisplay = false
    var mBufferedProgress: Long = 0
    var mBufferingStart = false
    fun notifyBufferingStartEnd() {
        callback.onBufferingStateChanged(
            this@VlcPlayerAdapter,
            mBufferingStart || !mInitialized
        )
    }

    var mDuration = -1L
    var mTime = -1L
    var mIsSeekable = false

    override fun onAttachedToHost(host: PlaybackGlueHost) {
        if (host is SurfaceHolderGlueHost) {
            mSurfaceHolderGlueHost = host
            mSurfaceHolderGlueHost!!.setSurfaceHolderCallback(this.VideoPlayerSurfaceHolderCallback())
        }
    }

    /**
     * Will reset the [org.videolan.libvlc.MediaPlayer] and the glue such that a new file can be played. You are
     * not required to call this method before playing the first file. However you have to call it
     * before playing a second one.
     */
    fun reset() {
        changeToUninitialized()
        vlcPlayer.stop()
        if(vlcPlayer.hasMedia()){
            vlcPlayer.media?.release()
            vlcPlayer.media = null
        }
    }

    fun changeToUninitialized() {
        if(vlcPlayer.hasMedia()){
            vlcPlayer.media?.release()
            vlcPlayer.media = null
        }
        if (mInitialized) {
            mInitialized = false
            notifyBufferingStartEnd()
            if (mHasDisplay) {
                callback.onPreparedStateChanged(this@VlcPlayerAdapter)
            }
        }
    }

    /**
     * Release internal VlcPlayer. Should not use the object after call release().
     */
    fun release() {
        changeToUninitialized()
        mHasDisplay = false
        vlcPlayer.release()
        mLibVLC.release()
    }

    override fun onDetachedFromHost() {
        if (mSurfaceHolderGlueHost != null) {
            mSurfaceHolderGlueHost!!.setSurfaceHolderCallback(null)
            mSurfaceHolderGlueHost = null
        }
        reset()
        release()
    }


    fun setDisplay(surfaceHolder: SurfaceHolder?) {
        val hadDisplay = mHasDisplay
        mHasDisplay = surfaceHolder != null
        if (hadDisplay == mHasDisplay) {
            return
        }
        if (surfaceHolder != null){
            vlcPlayer.vlcVout.setVideoSurface(surfaceHolder.surface,surfaceHolder)
            vlcPlayer.vlcVout.setWindowSize(surfaceHolder.surfaceFrame.width(),surfaceHolder.surfaceFrame.height())
            vlcPlayer.vlcVout.attachViews()
            mInitialized = true
        }else{
            mInitialized = false
            vlcPlayer.vlcVout.detachViews()
        }

        callback.onPreparedStateChanged(this@VlcPlayerAdapter)
    }




    override fun isPlaying(): Boolean {
        return vlcPlayer.isPlaying
    }

    override fun getDuration(): Long {
        return mDuration
    }

    override fun getCurrentPosition(): Long {
        return mTime
    }

    override fun play() {
        if ( vlcPlayer.isPlaying) {
            return
        }
        vlcPlayer.play()
    }

    override fun pause() {
        if (isPlaying) {
            vlcPlayer.pause()
        }
    }

    override fun seekTo(newPosition: Long) {
        if (!mInitialized) {
            return
        }
        vlcPlayer.time = newPosition
    }

    override fun getBufferedPosition(): Long {
        return mBufferedProgress
    }

    override fun getSupportedActions(): Long {

        return (PlaybackBaseControlGlue.ACTION_PLAY_PAUSE
                + PlaybackBaseControlGlue.ACTION_REWIND
                + PlaybackBaseControlGlue.ACTION_FAST_FORWARD).toLong()
    }

    /**
     * Sets the media source of the player with a given URI.
     *
     * @return Returns `true` if uri represents a new media; `false`
     * otherwise.
     */
    fun setDataSource(uri: Uri?): Boolean {
        if (if (mMediaSourceUri != null) mMediaSourceUri == uri else uri == null) {
            return false
        }
        mMediaSourceUri = uri
        prepareMediaForPlaying()
        return true
    }

    private fun prepareMediaForPlaying() {
        reset()

        try {
            val media = Media( mLibVLC, mMediaSourceUri )
            vlcPlayer.media = media
        } catch (e: IOException) {
            throw java.lang.RuntimeException("Invalid asset folder")
        }

        vlcPlayer.setEventListener { event ->
            when (event.type) {
                Event.MediaChanged -> {
                    Log.d(TAG, "libvlc Event.MediaChanged:")
                }
                Event.Opening -> {
                    Log.d(TAG, "libvlc Event.Opening")

                    if (mSurfaceHolderGlueHost == null || mHasDisplay) {
                        callback.onPreparedStateChanged(this@VlcPlayerAdapter)
                    }
                }
                Event.Buffering -> {
                    Log.d(TAG, "libvlc Event.Buffering" + event.buffering)
                    mBufferedProgress = (duration * event.buffering / 100).roundToLong()
                    callback.onBufferedPositionChanged(this@VlcPlayerAdapter)
                    if (mTime < mBufferedProgress) {
                        mBufferingStart = false
                        notifyBufferingStartEnd()
                    } else {
                        mBufferingStart = true
                        notifyBufferingStartEnd()
                    }
                }
                Event.Playing -> {
                    Log.d(TAG, "libvlc Event.Playing")
                    callback.onPlayStateChanged(this@VlcPlayerAdapter)
                }
                Event.Paused -> {
                    Log.d(TAG, "libvlc Event.Paused")
                    callback.onPlayStateChanged(this@VlcPlayerAdapter)
                }
                Event.Stopped -> {
                    // Playback of a media list player has stopped
                    Log.d(TAG, "libvlc Event.Stopped")
                }
                Event.EndReached -> {
                    // A media list has reached the end.
                    Log.d(TAG, "libvlc Event.EndReached")
                    callback.onPlayStateChanged(this@VlcPlayerAdapter)
                    callback.onPlayCompleted(this@VlcPlayerAdapter)
                }
                Event.EncounteredError -> {
                    Log.d(TAG, "libvlc Event.EncounteredError")

                    callback.onError(
                        this@VlcPlayerAdapter, 0,
                        "an error occurred"
                    )
                }
                Event.TimeChanged -> {
                    //Log.d(TAG, "libvlc Event.TimeChanged:"+ event.timeChanged)
                    mTime = event.timeChanged
                    callback.onCurrentPositionChanged(this@VlcPlayerAdapter)
                }
                Event.PositionChanged -> {
                    //Log.d(TAG, "libvlc Event.PositionChanged:"+ event.positionChanged )
                }
                Event.SeekableChanged -> {
                    Log.d(TAG, "libvlc Event.SeekableChanged:" + event.seekable)
                    mIsSeekable = event.seekable
                    callback.onMetadataChanged(this@VlcPlayerAdapter)
                }
                Event.PausableChanged -> {
                    Log.d(TAG, "libvlc Event.PausableChanged")
                }
                Event.LengthChanged -> {
                    Log.d(TAG, "libvlc Event.LengthChanged:" + event.lengthChanged)
                    mDuration = event.lengthChanged
                    callback.onDurationChanged(this@VlcPlayerAdapter)
                }
                Event.Vout -> {
                    Log.d(TAG, "libvlc Event.Vout")
                }
                Event.ESAdded -> {
                    // A track was added
                    Log.d(TAG, "libvlc Event.ESAdded")
                }
                Event.ESDeleted -> {
                    // A track was removed
                    Log.d(TAG, "libvlc Event.ESDeleted")
                }
                Event.ESSelected -> {
                    // Tracks were selected or unselected
                    Log.d(TAG, "libvlc Event.ESSelected")
                }
                Event.RecordChanged -> {
                    Log.d(TAG, "libvlc Event.RecordChanged")
                }
                else -> {
                    Log.d(TAG, "libvlc Event.Unknown")
                }


            }

        }
        notifyBufferingStartEnd()
        //callback.onPlayStateChanged(this@VlcPlayerAdapter)
    }

    /**
     * @return True if VlcPlayer OnPreparedListener is invoked and got a SurfaceHolder if
     * [PlaybackGlueHost] provides SurfaceHolder.
     */
    override fun isPrepared(): Boolean {
        return mInitialized && (mSurfaceHolderGlueHost == null || mHasDisplay)
    }

    /**
     * Implements [SurfaceHolder.Callback] that can then be set on the
     * [PlaybackGlueHost].
     */
    internal inner class VideoPlayerSurfaceHolderCallback : SurfaceHolder.Callback {
        override fun surfaceCreated(surfaceHolder: SurfaceHolder) {
            setDisplay(surfaceHolder)
        }

        override fun surfaceChanged(surfaceHolder: SurfaceHolder, i: Int, i1: Int, i2: Int) {}
        override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
            setDisplay(null)
        }
    }

    companion object {
        private const val TAG = "VlcPlayerAdapter"

    }

}