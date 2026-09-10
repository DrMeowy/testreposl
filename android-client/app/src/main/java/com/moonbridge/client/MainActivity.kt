package com.moonbridge.client

import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var remoteSurface: RemoteSurfaceView
    private lateinit var connectScreen: ScrollView
    private lateinit var hostInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var keyboardButton: Button
    private lateinit var keyboardBar: LinearLayout
    private lateinit var keyboardInput: EditText
    private lateinit var keyboardCloseButton: Button
    private lateinit var statusText: TextView
    private val httpClient = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var connecting = false
    private var audioTrack: AudioTrack? = null
    private val audioExecutor = Executors.newSingleThreadExecutor()
    private var suppressKeyboardWatcher = false
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
                sendKey("Enter", true); sendKey("Enter", false); hideKeyboard(); true
            } else false
        }
        keyboardInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (suppressKeyboardWatcher) return
                val now = s?.toString() ?: ""
                if (now.startsWith(lastKeyboardText)) sendText(now.substring(lastKeyboardText.length))
                else repeat((lastKeyboardText.length - now.length).coerceAtLeast(0)) { sendKey("Backspace", true); sendKey("Backspace", false) }
                lastKeyboardText = now
            }
        })
        remoteSurface.sendEvent = { message -> socket?.send(message) }
    }

    private fun connect() {
        var host = hostInput.text.toString().trim()
        if (host.startsWith("http://")) host = host.removePrefix("http://")
        if (host.startsWith("https://")) host = host.removePrefix("https://")
        if (host.endsWith("/")) host = host.dropLast(1)
        if (host.isEmpty()) { showError("Enter the PC address shown in Moonbridge."); return }
        getPreferences(MODE_PRIVATE).edit().putString("host", host).apply()
        connecting = true
        connectButton.isEnabled = false
        statusText.setTextColor(Color.parseColor("#94A1BB"))
        statusText.text = "Connecting directly to $host…"
        val request = Request.Builder().url("ws://$host/socket").build()
        socket?.cancel()
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val metrics = resources.displayMetrics
                webSocket.send(JSONObject().put("type", "pair").put("code", "").put("width", minOf(metrics.widthPixels, 1920)).put("height", minOf(metrics.heightPixels, 1080)).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "paired" -> runOnUiThread {
                            connecting = false
                            remoteSurface.setScreenSize(message.optInt("width", 1920), message.optInt("height", 1080))
                            connectScreen.visibility = View.GONE
                            remoteSurface.visibility = View.VISIBLE
                            findViewById<View>(R.id.remoteControls).visibility = View.VISIBLE
                            statusText.text = "Connected"
                        }
                        "audio" -> runOnUiThread { startAudio(message.optInt("sampleRate", 48000), message.optInt("channels", 2)) }
                        "error" -> runOnUiThread { showError(message.optString("message", "Connection rejected.")) }
                    }
                } catch (_: Exception) { runOnUiThread { showError("The PC sent an invalid response.") } }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > 0 && bytes[0].toInt() == 0x41) {
                    val audioPacket = bytes.toByteArray()
                    audioExecutor.execute { audioTrack?.write(audioPacket, 1, audioPacket.size - 1, AudioTrack.WRITE_NON_BLOCKING) }
                } else {
                    val bitmap = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size)
                    if (bitmap != null) remoteSurface.offerFrame(bitmap)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { showError("Cannot reach PC. Check the IP, Wi-Fi, and Windows Firewall.") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { if (!connecting) disconnect("Connection closed") }
            }
        })
    }

    private fun startAudio(sampleRate: Int, channels: Int) {
        audioTrack?.release()
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build()).setBufferSizeInBytes(maxOf(minimum, sampleRate * channels * 2 / 5)).setTransferMode(AudioTrack.MODE_STREAM).build()
        audioTrack?.play()
    }

    private fun showKeyboard() { keyboardBar.visibility = View.VISIBLE; keyboardInput.requestFocus(); (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT) }
    private fun hideKeyboard() { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(keyboardInput.windowToken, 0); keyboardBar.visibility = View.GONE; keyboardInput.clearFocus() }
    private fun sendText(value: String) { if (value.isNotEmpty()) socket?.send(JSONObject().put("type", "text").put("value", value).toString()) }
    private fun sendKey(code: String, down: Boolean) { socket?.send(JSONObject().put("type", "key").put("code", code).put("down", down).toString()) }

    private fun showError(message: String) {
        connecting = false; socket?.cancel(); socket = null; audioTrack?.pause(); connectButton.isEnabled = true; connectScreen.visibility = View.VISIBLE; remoteSurface.visibility = View.GONE; findViewById<View>(R.id.remoteControls).visibility = View.GONE; statusText.setTextColor(Color.parseColor("#F17F7F")); statusText.text = message
    }
    private fun disconnect(message: String) {
        connecting = false; socket?.close(1000, "user disconnected"); socket = null; audioTrack?.pause(); remoteSurface.clearFrame(); remoteSurface.visibility = View.GONE; findViewById<View>(R.id.remoteControls).visibility = View.GONE; hideKeyboard(); connectScreen.visibility = View.VISIBLE; connectButton.isEnabled = true; statusText.setTextColor(Color.parseColor("#94A1BB")); statusText.text = message
    }
    override fun onBackPressed() { if (remoteSurface.visibility == View.VISIBLE || connecting) disconnect("Ready to connect") else super.onBackPressed() }
    override fun onDestroy() { socket?.cancel(); audioTrack?.release(); audioExecutor.shutdownNow(); httpClient.dispatcher.executorService.shutdown(); super.onDestroy() }
}
