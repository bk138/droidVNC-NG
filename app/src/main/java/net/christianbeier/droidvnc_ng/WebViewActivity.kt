package net.christianbeier.droidvnc_ng

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/*
    Using a WebView activity here since Android TVs usually do not have a browser preinstalled.
    In contrast to a WebView dialog this retains state on device rotation etc.
 */
class WebViewActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // On Android 15 and later, calling enableEdgeToEdge ensures system bar icon colors update
        // when the device theme changes. Because calling it on pre-Android 15 has the side effect of
        // enabling EdgeToEdge there as well, we only use it on Android 15 and later.
        if (Build.VERSION.SDK_INT >= 35) {
            this.enableEdgeToEdge()
        }
        setContentView(R.layout.activity_webview)

        progressBar = findViewById(R.id.progress_bar)
        webView = findViewById(R.id.webview)

        // Get URL and title from intent
        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }

        // Set title  if provided
        intent.getStringExtra(EXTRA_TITLE)?.let { title ->
            setTitle(title)
        }

        // Set up web view
        webView.apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                }
            }
            settings.javaScriptEnabled = true
            loadUrl(url)
        }
    }

}