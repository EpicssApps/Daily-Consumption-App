package com.epicx.apps.dailyconsumptionformapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.delay
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WebViewActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        val webView = findViewById<WebView>(R.id.webview)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        val medicine = intent.getStringExtra("medicineName")
        val date = intent.getStringExtra("date")
        val combineOpeningBalance = intent.getStringExtra("opening")
        val consumption = intent.getStringExtra("consumption")
        val emergency = intent.getStringExtra("emergency")
        val closing = intent.getStringExtra("closing")
        val storeIssuedMainStore = intent.getStringExtra("storeIssued")
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

                view?.loadUrl("javascript:(function(){ var el = document.getElementsByClassName('whsOnd zHQkBf')[0]; el.value = '${consumption}'; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); })()")
                view?.loadUrl("javascript:(function(){ var el = document.getElementsByClassName('whsOnd zHQkBf')[1]; el.value = '${emergency}'; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); })()")
                view?.loadUrl("javascript:(function(){ var el = document.getElementsByClassName('whsOnd zHQkBf')[2]; el.value = '${closing}'; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); })()")

                    // Division
                lifecycleScope.launch {
                    delay(1000)
                    view?.loadUrl(
                        "javascript:(function(){" +
                                "document.getElementsByClassName('vRMGwf oJeWuf')[0].click();" +
                                "setTimeout(function(){" +
                                "var options = document.querySelectorAll('[role=\"option\"]');" +
                                "for (var i = 0; i < options.length; i++) {" +
                                "if (options[i].innerText.trim() === 'BAHAWALPUR') { options[i].click(); break; }" +
                                "}" +
                                "}, 500);" +
                                "})()"
                    )
                }
                // Select District
                lifecycleScope.launch {
                    delay(1000)
                    view?.loadUrl(
                        "javascript:(function(){" +
                                "document.getElementsByClassName('MocG8c HZ3kWc mhLiyf LMgvRb KKjvXb DEh1R')[0].click();" +
                                "setTimeout(function(){" +
                                "var options = document.querySelectorAll('[role=\"option\"]');" +
                                "for (var i = 0; i < options.length; i++) {" +
                                "if (options[i].innerText.trim() === 'BAHAWALNAGAR') { options[i].click(); break; }" +
                                "}" +
                                "}, 500);" +
                                "})()"
                    )
                }
//                Select Tehsil
                lifecycleScope.launch {
                    delay(1000)
                    view?.loadUrl(
                        "javascript:(function(){" +
                                "document.getElementsByClassName('vRMGwf oJeWuf')[0].click();" +
                                "setTimeout(function(){" +
                                "var options = document.querySelectorAll('[role=\"option\"]');" +
                                "for (var i = 0; i < options.length; i++) {" +
                                "if (options[i].innerText.trim() === 'Bahawalnagar (Central Station)') { options[i].click(); break; }" +
                                "}" +
                                "}, 500);" +
                                "})()"
                    )
                }
                // Select Medicine Name
                lifecycleScope.launch {
                    delay(1000)
                    view?.loadUrl(
                        "javascript:(function(){" +
                                "document.getElementsByClassName('vRMGwf oJeWuf')[0].click();" +
                                "setTimeout(function(){" +
                                "var options = document.querySelectorAll('[role=\"option\"]');" +
                                "for (var i = 0; i < options.length; i++) {" +
                                "if (options[i].innerText.trim() === '${medicine}') { options[i].click(); break; }" +
                                "}" +
                                "}, 500);" +
                                "})()"
                    )
                }

                super.onPageFinished(view, url)
            }
        }
        webView.loadUrl("https://docs.google.com/forms/d/e/1FAIpQLSd4UmFkDzx8CZzptA-u3KCFE-aHwMTdl2Z_q8WP3nnagqYPcw/viewform")
    }
}