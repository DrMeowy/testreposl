package com.moonbridge.client

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var clientView: WebView
    private lateinit var connectBar: View
    private lateinit var hostInput: EditText

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        clientView = findViewById(R.id.clientView)
        connectBar = findViewById(R.id.connectBar)
        hostInput = findViewById(R.id.hostInput)

        clientView.settings.javaScriptEnabled = true
        clientView.settings.domStorageEnabled = true
        clientView.settings.mediaPlaybackRequiresUserGesture = false
        clientView.webViewClient = WebViewClient()
        clientView.webChromeClient = WebChromeClient()

        findViewById<Button>(R.id.connectButton).setOnClickListener { openHost() }
    }

    private fun openHost() {
        var host = hostInput.text.toString().trim()
        if (host.isEmpty()) return
        if (!host.startsWith("http://") && !host.startsWith("https://")) host = "http://$host"
        clientView.loadUrl(host)
        connectBar.visibility = View.GONE
    }

    override fun onBackPressed() {
        if (clientView.canGoBack()) clientView.goBack() else super.onBackPressed()
    }
}
