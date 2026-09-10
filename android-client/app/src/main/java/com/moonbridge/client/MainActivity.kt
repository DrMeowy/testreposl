package com.moonbridge.client

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
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

class MainActivity : AppCompatActivity() {
    private lateinit var remoteSurface: RemoteSurfaceView
    private lateinit var connectScreen: ScrollView
    private lateinit var hostInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusText: TextView
    private val httpClient = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var connecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        remoteSurface = findViewById(R.id.remoteSurface)
        connectScreen = findViewById(R.id.connectScreen)
        hostInput = findViewById(R.id.hostInput)
        codeInput = findViewById(R.id.codeInput)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        statusText = findViewById(R.id.statusText)
        hostInput.setText(getPreferences(MODE_PRIVATE).getString("host", ""))
        connectButton.setOnClickListener { connect() }
        disconnectButton.setOnClickListener { disconnect("Ready to connect") }
        remoteSurface.sendEvent = { message -> socket?.send(message) }
    }

    private fun connect() {
        var host = hostInput.text.toString().trim()
        val code = codeInput.text.toString().trim()
        if (host.startsWith("http://")) host = host.removePrefix("http://")
        if (host.startsWith("https://")) host = host.removePrefix("https://")
        if (host.endsWith("/")) host = host.dropLast(1)
        if (host.isEmpty()) { showError("Enter the PC address shown in Moonbridge."); return }
        if (code.length != 6) { showError("Enter the six-digit pairing code shown on the PC."); return }
        getPreferences(MODE_PRIVATE).edit().putString("host", host).apply()
        connecting = true
        connectButton.isEnabled = false
        statusText.setTextColor(Color.parseColor("#94A1BB"))
        statusText.text = "Connecting directly to $host…"
        val request = Request.Builder().url("ws://$host/socket").build()
        socket?.cancel()
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "pair").put("code", code).toString())
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
                            disconnectButton.visibility = View.VISIBLE
                        }
                        "error" -> runOnUiThread { showError(message.optString("message", "Pairing failed.")) }
                    }
                } catch (_: Exception) { runOnUiThread { showError("The PC sent an invalid response.") } }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val bitmap = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size)
                if (bitmap != null) runOnUiThread { remoteSurface.setFrame(bitmap) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { showError("Cannot reach PC. Check the IP, Wi-Fi, and Windows Firewall.") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { if (!connecting) disconnect("Connection closed") }
            }
        })
    }

    private fun showError(message: String) {
        connecting = false
        socket?.cancel()
        socket = null
        connectButton.isEnabled = true
        connectScreen.visibility = View.VISIBLE
        remoteSurface.visibility = View.GONE
        disconnectButton.visibility = View.GONE
        statusText.setTextColor(Color.parseColor("#F17F7F"))
        statusText.text = message
    }

    private fun disconnect(message: String) {
        connecting = false
        socket?.close(1000, "user disconnected")
        socket = null
        remoteSurface.clearFrame()
        remoteSurface.visibility = View.GONE
        disconnectButton.visibility = View.GONE
        connectScreen.visibility = View.VISIBLE
        connectButton.isEnabled = true
        statusText.setTextColor(Color.parseColor("#94A1BB"))
        statusText.text = message
    }

    override fun onBackPressed() {
        if (remoteSurface.visibility == View.VISIBLE || connecting) disconnect("Ready to connect") else super.onBackPressed()
    }

    override fun onDestroy() {
        socket?.cancel()
        httpClient.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
