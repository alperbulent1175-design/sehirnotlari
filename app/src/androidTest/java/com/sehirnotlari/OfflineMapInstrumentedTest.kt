package com.sehirnotlari

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OfflineMapInstrumentedTest {
    @Test
    fun bundledMapRendersAllProvincesInWebView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var result = ""
            while (System.nanoTime() < deadline) {
                val done = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.findViewById<WebView>(R.id.webView).evaluateJavascript(
                        "Boolean(window.L && mapReady && window.PROVINCE_GEOJSON.features.length === 81 && geoLayer.getLayers().length === 81)"
                    ) {
                        result = it
                        done.countDown()
                    }
                }
                assertTrue("WebView JavaScript did not respond", done.await(5, TimeUnit.SECONDS))
                if (result == "true") break
                Thread.sleep(250)
            }
            assertEquals("Bundled Leaflet map did not render 81 provinces", "true", result)
        }
    }
}
