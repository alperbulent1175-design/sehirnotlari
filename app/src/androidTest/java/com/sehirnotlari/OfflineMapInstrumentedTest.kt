package com.sehirnotlari

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class OfflineMapInstrumentedTest {
    @Test
    fun bundledMapRendersAllProvincesInWebView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            val result = AtomicReference("")
            val pageState = AtomicReference("")
            scenario.onActivity { activity ->
                activity.findViewById<WebView>(R.id.webView).apply {
                    setNetworkAvailable(false)
                    reload()
                }
            }
            while (System.nanoTime() < deadline) {
                val done = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.findViewById<WebView>(R.id.webView).apply {
                        pageState.set("url=$url progress=$progress")
                        evaluateJavascript(
                            "Boolean(!navigator.onLine && window.L && mapReady && window.PROVINCE_GEOJSON.features.length === 81 && geoLayer && geoLayer.getLayers().length === 81 && !lmap._tile)"
                        ) {
                            result.set(it)
                            done.countDown()
                        }
                    }
                }
                done.await(5, TimeUnit.SECONDS)
                if (result.get() == "true") break
                Thread.sleep(250)
            }
            assertEquals("Offline map failed: ${pageState.get()}, js=${result.get()}", "true", result.get())
        }
    }
}
