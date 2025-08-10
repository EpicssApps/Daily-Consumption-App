package com.epicx.apps.dailyconsumptionformapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class WebViewActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        val webView = findViewById<WebView>(R.id.webview)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        val medicine = intent.getStringExtra("medicineName")
        val date = intent.getStringExtra("date")
        val combineOpeningBalance = intent.getIntExtra("opening", 0)
        val consumption = intent.getIntExtra("consumption", 0)
        val emergency = intent.getIntExtra("emergency", 0)
        val closing = intent.getIntExtra("closing", 0)
        val storeIssuedMainStore = intent.getIntExtra("storeIssued", 0)
        val stockAvailableBeforeIssue = intent.getStringExtra("stockAvailable")

        val tvMedicineName = findViewById<TextView>(R.id.tvMedicineName)
        tvMedicineName.text = medicine ?: "Medicine Name"
        

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                // Calculate yesterday's date in yyyy-MM-dd format
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.DATE, -1)
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val prevDate = sdf.format(cal.time)

                view?.loadUrl("javascript:(function(){ var el = document.getElementsByClassName('whsOnd zHQkBf')[0]; el.value = '${prevDate}'; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); })()")
                view?.loadUrl("javascript:(function(){ var el = document.getElementsByClassName('whsOnd zHQkBf')[1]; el.value = '${combineOpeningBalance}'; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); })()")
                view?.loadUrl("javascript:(function(){ var el = document.getElementsByClassName('whsOnd zHQkBf')[2]; el.value = '${storeIssuedMainStore}'; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); })()")
                view?.loadUrl("javascript:(function(){ var el = document.getElementsByClassName('whsOnd zHQkBf')[3]; el.value = '${stockAvailableBeforeIssue}'; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); })()")
                view?.loadUrl("javascript:(function(){ var el = document.getElementsByClassName('whsOnd zHQkBf')[4]; el.value = '${consumption}'; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); })()")
                view?.loadUrl("javascript:(function(){ var el = document.getElementsByClassName('whsOnd zHQkBf')[5]; el.value = '${emergency}'; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); })()")
                view?.loadUrl("javascript:(function(){ var el = document.getElementsByClassName('whsOnd zHQkBf')[6]; el.value = '${closing}'; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); })()")

                // ... your other logic (like hiding loader)
                super.onPageFinished(view, url)
            }
        }
        webView.loadUrl("https://docs.google.com/forms/d/e/1FAIpQLSd4UmFkDzx8CZzptA-u3KCFE-aHwMTdl2Z_q8WP3nnagqYPcw/viewform")
    }
}