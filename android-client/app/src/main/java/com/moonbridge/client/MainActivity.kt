package com.moonbridge.client

import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var remoteSurface: RemoteVideoSurfaceView
    private lateinit var connectScreen: ScrollView
    private lateinit var hostInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var keyboardButton: Button
    private lateinit var keyboardBar: LinearLayout
    private lateinit var keyboardInput: EditText
    private lateinit var keyboardCloseButton: Button
    private lateinit var statusText: TextView

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var audioSocket: WebSocket? = null
    private var connecting = false
    private var connectedHost = ""
    private var audioTrack: AudioTrack? = null
    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val videoExecutor = Executors.newSingleThreadExecutor()
    private val videoQueue = ArrayBlockingQueue<ByteArray>(24)
    @Volatile private var decoderRunning = false
    @Volatile private var videoDecoder: MediaCodec? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var lastKeyboardText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        remoteSurface = findViewById(R.id.remoteSurface)
        connectScreen = findViewById(R.id.connectScreen)
        hostInput = findViewById(R.id.hostInput)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        keyboardButton = findViewById(R.id.keyboardButton)
        keyboardBar = findViewById(R.id.keyboardBar)
        keyboardInput = findViewById(R.id.keyboardInput)
        keyboardCloseButton = findViewById(R.id.keyboardCloseButton)
        statusText = findViewById(R.id.statusText)

        hostInput.setText(getPreferences(MODE_PRIVATE).getString("host", ""))
        connectButton.setOnClickListener { connect() }
        disconnectButton.setOnClickListener { disconnect("Ready to connect") }
        keyboardButton.setOnClickListener { showKeyboard() }
        keyboardCloseButton.setOnClickListener { hideKeyboard() }
        keyboardInput.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                sendKey("Enter", true)
                sendKey("Enter", false)
                hideKeyboard()
                true
            } else false
        }
        keyboardInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val now = s?.toString() ?: ""
                if (now.startsWith(lastKeyboardText)) {
                    sendText(now.substring(lastKeyboardText.length))
                } else {
                    repeat((lastKeyboardText.length - now.length).coerceAtLeast(0)) {
                        sendKey("Backspace", true)
                        sendKey("Backspace", false)
                    }
                }
                lastKeyboardText = now
            }
        })
        remoteSurface.sendEvent = { message -> socket?.send(message) }
        remoteSurface.onSurfaceReady = { startVideoDecoder(it) }
        remoteSurface.onSurfaceDestroyed = { stopVideoDecoder() }
    }

    private fun connect() {
        var host = hostInput.text.toString().trim()
        host = host.removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (host.isEmpty()) {
            showError("Enter the PC address shown in Moonbridge.")
            return
        }

        getPreferences(MODE_PRIVATE).edit().putString("host", host).apply()
        connectedHost = host
        connecting = true
        connectButton.isEnabled = false
        statusText.setTextColor(Color.parseColor("#94A1BB"))
        statusText.text = "Connecting directly to $host…"

        socket?.cancel()
        socket = httpClient.newWebSocket(Request.Builder().url("ws://$host/socket").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val metrics = resources.displayMetrics
                webSocket.send(
                    JSONObject()
                        .put("type", "pair")
                        .put("code", "")
                        .put("width", min(metrics.widthPixels, 1920))
                        .put("height", min(metrics.heightPixels, 1080))
                        .toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "paired" -> runOnUiThread {
                            connecting = false
                            videoWidth = message.optInt("width", 1920).coerceAtLeast(1)
                            videoHeight = message.optInt("height", 1080).coerceAtLeast(1)
                            remoteSurface.setScreenSize(videoWidth, videoHeight)
                            videoQueue.clear()
                            connectScreen.visibility = View.GONE
                            remoteSurface.visibility = View.VISIBLE
                            findViewById<View>(R.id.remoteControls).visibility = View.VISIBLE
                            statusText.text = "Connected · hardware video"
                            startVideoDecoderIfSurfaceReady()
                            connectAudio(connectedHost)
                        }
                        "error" -> runOnUiThread { showError(message.optString("message", "Connection rejected.")) }
                    }
                } catch (_: Exception) {
                    runOnUiThread { showError("The PC sent an invalid response.") }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size <= 1 || bytes[0].toInt() != 0x56) return
                val payload = bytes.substring(1).toByteArray()
                if (!videoQueue.offer(payload)) {
                    videoQueue.poll()
                    videoQueue.offer(payload)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    if (connecting || remoteSurface.visibility != View.VISIBLE) showError("Cannot reach PC. Check the IP, Wi-Fi, and Windows Firewall.")
                    else disconnect("Connection lost")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { if (!connecting && remoteSurface.visibility == View.VISIBLE) disconnect("Connection closed") }
            }
        })
    }

    private fun connectAudio(host: String) {
        audioSocket?.cancel()
        audioSocket = httpClient.newWebSocket(Request.Builder().url("ws://$host/audio").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "pair").put("code", "").toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    if (message.optString("type") == "audio") {
                        runOnUiThread { startAudio(message.optInt("sampleRate", 48000), message.optInt("channels", 2)) }
                    }
                } catch (_: Exception) { }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size <= 1 || bytes[0].toInt() != 0x41) return
                val packet = bytes.toByteArray()
                audioExecutor.execute {
                    val track = audioTrack ?: return@execute
                    track.write(packet, 1, packet.size - 1, AudioTrack.WRITE_BLOCKING)
                }
            }
        })
    }

    private fun startVideoDecoderIfSurfaceReady() {
        if (remoteSurface.visibility == View.VISIBLE && remoteSurface.holder.surface.isValid) {
            startVideoDecoder(remoteSurface.holder.surface)
        }
    }

    private fun startVideoDecoder(surface: Surface) {
        if (videoWidth <= 0 || videoHeight <= 0 || !surface.isValid || decoderRunning) return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)

        try {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()
            videoDecoder = codec
            decoderRunning = true
            videoExecutor.execute { decodeVideo(codec) }
        } catch (_: Exception) {
            runOnUiThread { showError("This tablet could not start its hardware H.264 decoder.") }
        }
    }

    private fun decodeVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var presentationUs = 0L
        try {
            while (decoderRunning && !Thread.currentThread().isInterrupted) {
                val chunk = videoQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                var offset = 0
                while (offset < chunk.size && decoderRunning) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex < 0) {
                        drainDecoder(codec, info)
                        continue
                    }
                    val input = codec.getInputBuffer(inputIndex) ?: continue
                    input.clear()
                    val count = min(input.remaining(), chunk.size - offset)
                    input.put(chunk, offset, count)
                    codec.queueInputBuffer(inputIndex, 0, count, presentationUs, 0)
                    presentationUs += 1_000
                    offset += count
                    drainDecoder(codec, info)
                }
                drainDecoder(codec, info)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: IllegalStateException) {
            // The surface can disappear while Android is tearing down the codec.
        } finally {
            try { codec.stop() } catch (_: Exception) { }
            try { codec.release() } catch (_: Exception) { }
            if (videoDecoder === codec) videoDecoder = null
        }
    }

    private fun drainDecoder(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, 0)
            if (outputIndex < 0) return
            codec.releaseOutputBuffer(outputIndex, true)
        }
    }

    private fun stopVideoDecoder() {
        decoderRunning = false
        videoQueue.clear()
        val codec = videoDecoder
        videoDecoder = null
        if (codec != null) {
            try { codec.stop() } catch (_: Exception) { }
            try { codec.release() } catch (_: Exception) { }
        }
    }

    private fun startAudio(sampleRate: Int, channels: Int) {
        audioTrack?.release()
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build())
            .setBufferSizeInBytes(maxOf(minimum, sampleRate * channels * 2 / 10))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    private fun showKeyboard() {
        keyboardBar.visibility = View.VISIBLE
        keyboardInput.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(keyboardInput.windowToken, 0)
        keyboardBar.visibility = View.GONE
        keyboardInput.clearFocus()
    }

    private fun sendText(value: String) {
        if (value.isNotEmpty()) socket?.send(JSONObject().put("type", "text").put("value", value).toString())
    }

    private fun sendKey(code: String, down: Boolean) {
        socket?.send(JSONObject().put("type", "key").put("code", code).put("down", down).toString())
    }

    private fun showError(message: String) {
        connecting = false
        socket?.cancel(); socket = null
        audioSocket?.cancel(); audioSocket = null
        stopVideoDecoder(); releaseAudio()
        connectButton.isEnabled = true
        connectScreen.visibility = View.VISIBLE
        remoteSurface.visibility = View.GONE
        findViewById<View>(R.id.remoteControls).visibility = View.GONE
        statusText.setTextColor(Color.parseColor("#F17F7F"))
        statusText.text = message
    }

    private fun disconnect(message: String) {
        connecting = false
        socket?.close(1000, "user disconnected"); socket = null
        audioSocket?.close(1000, "user disconnected"); audioSocket = null
        stopVideoDecoder(); releaseAudio()
        remoteSurface.visibility = View.GONE
        findViewById<View>(R.id.remoteControls).visibility = View.GONE
        hideKeyboard()
        connectScreen.visibility = View.VISIBLE
        connectButton.isEnabled = true
        statusText.setTextColor(Color.parseColor("#94A1BB"))
        statusText.text = message
    }

    private fun releaseAudio() {
        try { audioTrack?.pause() } catch (_: Exception) { }
        try { audioTrack?.flush() } catch (_: Exception) { }
        try { audioTrack?.release() } catch (_: Exception) { }
        audioTrack = null
    }

    override fun onBackPressed() {
        if (remoteSurface.visibility == View.VISIBLE || connecting) disconnect("Ready to connect") else super.onBackPressed()
    }

    override fun onDestroy() {
        socket?.cancel(); audioSocket?.cancel()
        stopVideoDecoder(); releaseAudio()
        audioExecutor.shutdownNow(); videoExecutor.shutdownNow()
        httpClient.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
