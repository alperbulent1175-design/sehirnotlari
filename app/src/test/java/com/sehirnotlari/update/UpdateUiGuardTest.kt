package com.sehirnotlari.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static guards on the WebView half of the updater (index.html) and its native bridge. */
class UpdateUiGuardTest {
    private val html = source("src/main/assets/index.html")
    private val updateScript = html.substringAfter("// GitHub güncellemeleri").substringBefore("updateSum();")

    @Test
    fun onlyThePrimaryButtonInUpdateAvailableStartsADownload() {
        assertEquals(1, Regex("""downloadUpdate\(""").findAll(html).count())
        assertTrue(updateScript.contains("if(st==='UPDATE_AVAILABLE')window.Android.downloadUpdate();"))
        val handler = updateScript.substringAfter("function onGithubUpdate(s){").substringBefore("function updLaterAction(){")
        assertFalse(handler.contains("Android."))
    }

    @Test
    fun laterAndBackOnlyDismiss() {
        val later = updateScript.substringAfter("function updLaterAction(){").substringBefore("}\n")
        assertTrue(later.contains("dismissUpdate()"))
        assertFalse(later.contains("downloadUpdate") || later.contains("installUpdate"))
        val back = html.substringAfter("function handleAndroidBack() {").substringBefore("// 0.")
        assertTrue(back.contains("updLaterAction()"))
    }

    @Test
    fun notesAreRenderedWithoutHtmlSinks() {
        assertTrue(html.contains("<script src=\"release-notes.js\"></script>"))
        assertTrue(updateScript.contains("ReleaseNotes.render(document,notes,s.notes||'')"))
        for (sink in listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "eval(")) {
            assertFalse(sink, updateScript.contains(sink))
        }
    }

    @Test
    fun dialogIsAccessibleAndScalesWithSystemText() {
        val dialog = html.substringAfter("<div class=\"sub-ov upd-ov\" id=\"updOv\">").substringBefore("<div class=\"toast\"")
        listOf(
            "role=\"dialog\"", "aria-modal=\"true\"", "aria-labelledby=\"updTitle\"", "aria-describedby=\"updStatus\"",
            "role=\"progressbar\"", "aria-valuemin=\"0\"", "aria-valuemax=\"100\"",
            "role=\"status\" aria-live=\"polite\"", "aria-label=\"Sürüm notları\"", "Neler Yeni?",
            "Güncelle ve Yükle", "Daha Sonra",
        ).forEach { assertTrue(it, dialog.contains(it)) }
        // The WebView already scales px text by the system font size; a second multiplier doubled it.
        assertFalse(html.contains("--upd-fs"))
        assertFalse(updateScript.contains("fontScale"))
        assertFalse(Regex("""\.upd-[^{]*\{[^}]*calc\([^)]*\*""").containsMatchIn(html))
        assertTrue(html.contains(".upd-dlg{display:flex;flex-direction:column;max-height:calc(100vh - 24px);max-height:calc(100dvh - 24px);overflow:hidden;font-size:14px}"))
        assertTrue(html.contains(".upd-dlg .upd-row button{flex:1 1 130px;min-height:48px;font-size:14px}"))
        assertTrue(html.contains("@media(max-width:360px){.upd-dlg .upd-row button{flex:0 0 auto}}"))
        val short = html.substringAfter("@media(max-height:500px){").substringBefore("\n}")
        assertTrue(short.contains(".upd-dlg{overflow-y:auto"))
        assertTrue(short.contains(".upd-dlg .upd-row{position:sticky;bottom:-16px"))
        assertTrue(html.contains("prefers-reduced-motion"))
    }

    @Test
    fun automaticFailuresStayOutOfTheWay() {
        assertTrue(updateScript.contains("(s.state==='FAILED'&&!s.interactive)){updClose();return;}"))
    }

    @Test
    fun bridgeExposesOnlyUserIntents() {
        val activity = source("src/main/java/com/sehirnotlari/MainActivity.kt")
        val bridge = activity.substringAfter("fun checkForUpdate()")
        listOf("releaseUpdater.checkForUpdate()", "releaseUpdater.downloadUpdate()", "releaseUpdater.installUpdate()",
            "releaseUpdater.dismissUpdate()", "releaseUpdater.retryUpdate()").forEach {
            assertTrue(it, activity.contains(it))
        }
        // The only update methods with a parameter are the two switches, and they take a Boolean.
        val withParams = Regex("""fun (\w*[Uu]pdate\w*)\(\s*\w+\s*:\s*(\w+)""").findAll(bridge).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf("setAutoDownloadUpdates" to "Boolean", "setPrereleaseUpdates" to "Boolean"), withParams)
        assertTrue(activity.contains("override fun onResume()") && activity.contains("releaseUpdater.onResume()"))
    }

    @Test
    fun switchesStartOffAndAreShownOnlyInTheApp() {
        val prefs = source("src/main/java/com/sehirnotlari/update/UpdateState.kt")
        assertTrue(prefs.contains("prefs.getBoolean(KEY_AUTO_DOWNLOAD, false)"))
        assertTrue(prefs.contains("prefs.getBoolean(KEY_PRERELEASES, false)"))
        listOf("<label class=\"set-row\" id=\"updAutoRow\" hidden>", "<label class=\"set-row\" id=\"updPreRow\" hidden>").forEach {
            assertTrue(it, html.contains(it))
        }
        assertTrue(html.contains(".set-row[hidden]{display:none}"))
        assertTrue(updateScript.contains("auto.checked=st.autoDownload===true;pre.checked=st.prereleases===true;"))
    }

    @Test
    fun installationGoesThroughPackageInstallerWithTheUsersConfirmation() {
        val updater = source("src/main/java/com/sehirnotlari/GithubReleaseUpdater.kt")
        val session = source("src/main/java/com/sehirnotlari/update/SessionInstaller.kt")
        val manifest = source("src/main/AndroidManifest.xml")
        assertFalse(updater.contains("ACTION_VIEW"))
        assertFalse(updater.contains("FileProvider"))
        assertFalse(manifest.contains("application/vnd.android.package-archive"))
        assertTrue(session.contains("setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)"))
        assertTrue(session.contains("if (written != sha256) {\n                    session.abandon()"))
        assertTrue(manifest.contains("<receiver android:name=\".update.UpdateInstallReceiver\" android:exported=\"false\" />"))
        // The confirmation screen is opened by the visible activity, never straight from the receiver.
        val receiver = session.substringAfter("class UpdateInstallReceiver")
        assertFalse(receiver.contains("startActivity"))
        assertTrue(updater.contains("if (activity.isDestroyed || !isResumed) return@runOnUiThread"))
    }

    @Test
    fun theShownVersionComesFromTheInstalledApk() {
        assertFalse(Regex("""[Ss]ürüm:? v?\d+\.\d+""").containsMatchIn(html))
        assertTrue(source("src/main/java/com/sehirnotlari/GithubReleaseUpdater.kt").contains(".put(\"version\", BuildConfig.VERSION_NAME)"))
        assertTrue(updateScript.contains("document.getElementById('updVerTxt').textContent='Yüklü sürüm: '+st.version;"))
    }

    @Test
    fun releaseBuildsAlwaysUseGitHub() {
        val gradle = source("build.gradle.kts")
        val release = gradle.substringAfter("release {").substringBefore("}")
        assertTrue(release.contains("buildConfigField(\"String\", \"UPDATE_TEST_SERVER\", \"\\\"\\\"\")"))
        val updater = source("src/main/java/com/sehirnotlari/GithubReleaseUpdater.kt")
        assertTrue(updater.contains("val source = if (BuildConfig.DEBUG && BuildConfig.UPDATE_TEST_SERVER.isNotEmpty()) {"))
        assertEquals(1, Regex("""UpdateSource\.testServer\(""").findAll(updater).count())
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.first { it.exists() }.readText().replace("\r\n", "\n")
    }
}
