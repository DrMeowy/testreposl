package com.moonbridge.client

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var clientView: WebView
    private lateinit var connectScreen: ScrollView
    private lateinit var hostInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var connecting = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        clientView = findViewById(R.id.clientView)
        connectScreen = findViewById(R.id.connectScreen)
        hostInput = findViewById(R.id.hostInput)
        codeInput = findViewById(R.id.codeInput)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)

        hostInput.setText(getPreferences(MODE_PRIVATE).getString("host", ""))
        clientView.settings.javaScriptEnabled = true
        clientView.settings.domStorageEnabled = true
        clientView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        clientView.settings.mediaPlaybackRequiresUserGesture = false
        clientView.setBackgroundColor(Color.BLACK)
        clientView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!connecting) return
                val code = codeInput.text.toString()
                view?.evaluateJavascript("document.getElementById('code').value='$code'; document.getElementById('connectBtn').click();", null)
                pollForRemoteView()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != false) showConnectionError("PC not reachable. Check the IP, Wi-Fi, and Windows Firewall.")
            }
        }
        connectButton.setOnClickListener { connect() }
    }

    private fun connect() {
        var host = hostInput.text.toString().trim()
        val code = codeInput.text.toString().trim()
        if (host.isEmpty()) { showConnectionError("Enter the PC address shown in Moonbridge."); return }
        if (code.length != 6) { showConnectionError("Enter the six-digit pairing code from the PC."); return }
        if (!host.startsWith("http://") && !host.startsWith("https://")) host = "http://$host"
        getPreferences(MODE_PRIVATE).edit().putString("host", host.removePrefix("http://").removePrefix("https://")).apply()
        connecting = true
        connectButton.isEnabled = false
        statusText.setTextColor(Color.parseColor("#94A1BB"))
        statusText.text = "Connecting to $host…"
        clientView.visibility = View.VISIBLE
        clientView.loadUrl(host)
    }

    private fun pollForRemoteView() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!connecting) return
                clientView.evaluateJavascript("document.getElementById('remote')?.style.display === 'block'") { result ->
                    if (result == "true") {
                        connecting = false
                        connectScreen.visibility = View.GONE
                        connectButton.isEnabled = true
                    } else handler.postDelayed(this, 300)
                }
            }
        }, 500)
    }

    private fun showConnectionError(message: String) {
        connecting = false
        clientView.visibility = View.GONE
        connectButton.isEnabled = true
        statusText.setTextColor(Color.parseColor("#F17F7F"))
        statusText.text = message
    }

    override fun onBackPressed() {
        if (clientView.visibility == View.VISIBLE) {
            connecting = false
            clientView.loadUrl("about:blank")
            clientView.visibility = View.GONE
            connectScreen.visibility = View.VISIBLE
            connectButton.isEnabled = true
            statusText.setTextColor(Color.parseColor("#94A1BB"))
            statusText.text = "Ready to connect"
        } else super.onBackPressed()
    }
}
