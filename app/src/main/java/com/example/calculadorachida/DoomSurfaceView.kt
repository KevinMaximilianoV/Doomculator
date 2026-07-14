package com.example.calculadorachida

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaPlayer
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

class DoomSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        // Must match DOOMGENERIC_RESX / DOOMGENERIC_RESY in doomgeneric.h
        const val DOOM_W = 320
        const val DOOM_H = 200

        init { System.loadLibrary("doom-native") }
    }

    private val bitmap = Bitmap.createBitmap(DOOM_W, DOOM_H, Bitmap.Config.ARGB_8888)
    private val destRect = Rect()

    @Volatile private var surfaceReady = false
    @Volatile private var running = false
    private var initialized = false
    private var wadPath: String? = null
    private var filesDir: String? = null
    private var doomThread: Thread? = null
    private var mediaPlayer: MediaPlayer? = null

    // ── JNI ──────────────────────────────────────────────────────────────────
    private external fun nativeInit(wadPath: String, filesDir: String)
    private external fun nativeTick()
    external fun nativeSendKey(doomKey: Int, pressed: Boolean)

    // Called from native (JNI) on the DOOM thread — draws the current frame
    @Suppress("unused")
    fun onDoomFrame(pixels: IntArray, width: Int, height: Int) {
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val canvas: Canvas = holder.lockCanvas() ?: run {
            Log.e("DOOM", "lockCanvas() returned null, surfaceReady=$surfaceReady")
            return
        }
        try {
            canvas.drawColor(Color.BLACK)
            computeDestRect(canvas.width, canvas.height)
            canvas.drawBitmap(bitmap, null, destRect, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    // Called from native (JNI) on the DOOM thread — MUS data was already
    // converted to a temp .mid file; play it with Android's built-in MIDI
    // synth. prepare()/start() are synchronous so this needs no Looper.
    @Suppress("unused")
    fun musicPlay(path: String, looping: Boolean) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                isLooping = looping
                prepare()
                start()
            }
            Log.i("DOOM", "musicPlay started OK, isPlaying=${mediaPlayer?.isPlaying}, duration=${mediaPlayer?.duration}ms")
        } catch (e: Exception) {
            Log.e("DOOM", "musicPlay failed for '$path': ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Suppress("unused")
    fun musicStop() {
        mediaPlayer?.apply {
            try { stop() } catch (e: Exception) { /* already stopped */ }
            release()
        }
        mediaPlayer = null
    }

    @Suppress("unused")
    fun musicPause() {
        try { mediaPlayer?.pause() } catch (e: Exception) { /* not playing */ }
    }

    @Suppress("unused")
    fun musicResume() {
        try { mediaPlayer?.start() } catch (e: Exception) { /* no active song */ }
    }

    @Suppress("unused")
    fun musicSetVolume(vol: Float) {
        mediaPlayer?.setVolume(vol, vol)
    }

    // ── Public API ────────────────────────────────────────────────────────────
    fun prepare(wadPath: String, filesDir: String) {
        this.wadPath  = wadPath
        this.filesDir = filesDir
        startIfReady()
    }

    // ── SurfaceHolder.Callback ────────────────────────────────────────────────
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        computeDestRect(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    fun stopDoom() {
        running = false
        doomThread?.interrupt()
        doomThread = null
        musicStop()
    }

    // ── Internals ─────────────────────────────────────────────────────────────
    private fun startIfReady() {
        val wad  = wadPath  ?: return
        val fdir = filesDir ?: return
        if (!surfaceReady || initialized) return
        initialized = true
        running = true
        doomThread = Thread({
            nativeInit(wad, fdir)
            while (running && !Thread.currentThread().isInterrupted) {
                nativeTick()
            }
        }, "DoomThread").also { it.start() }
    }

    private fun computeDestRect(canvasW: Int, canvasH: Int) {
        val scale = minOf(canvasW.toFloat() / DOOM_W, canvasH.toFloat() / DOOM_H)
        val dw = (DOOM_W * scale).toInt()
        val dh = (DOOM_H * scale).toInt()
        val dx = (canvasW - dw) / 2
        val dy = (canvasH - dh) / 2
        destRect.set(dx, dy, dx + dw, dy + dh)
    }

    init {
        setBackgroundColor(Color.BLACK)
        // A SurfaceView's actual buffer is composited in a separate hardware
        // layer that defaults to sitting BEHIND the rest of the window's
        // content — so the window/root background (nearly black here) was
        // painting over every DOOM frame even though rendering itself worked.
        setZOrderOnTop(true)
        holder.addCallback(this)
    }
}
