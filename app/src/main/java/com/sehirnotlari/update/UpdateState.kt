package com.sehirnotlari.update

import android.content.SharedPreferences

enum class UpdateState {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    READY_TO_INSTALL,
    INSTALL_PERMISSION_REQUIRED,
    FAILED,
    DISMISSED;

    fun canMoveTo(next: UpdateState): Boolean = next in (ALLOWED[this] ?: emptySet())

    companion object {
        // DOWNLOADING is reachable only from UPDATE_AVAILABLE (the user's "Güncelle ve Yükle", or the
        // automatic download the user switched on) or from a failed download the user retries.
        private val ALLOWED: Map<UpdateState, Set<UpdateState>> = mapOf(
            IDLE to setOf(CHECKING),
            CHECKING to setOf(UP_TO_DATE, UPDATE_AVAILABLE, DISMISSED, FAILED),
            UP_TO_DATE to setOf(CHECKING),
            UPDATE_AVAILABLE to setOf(DOWNLOADING, VERIFYING, DISMISSED, CHECKING),
            DOWNLOADING to setOf(VERIFYING, FAILED, DISMISSED),
            VERIFYING to setOf(READY_TO_INSTALL, INSTALL_PERMISSION_REQUIRED, FAILED, DISMISSED),
            READY_TO_INSTALL to setOf(INSTALL_PERMISSION_REQUIRED, FAILED, DISMISSED, CHECKING),
            INSTALL_PERMISSION_REQUIRED to setOf(READY_TO_INSTALL, FAILED, DISMISSED, CHECKING),
            FAILED to setOf(CHECKING, DOWNLOADING, VERIFYING, DISMISSED),
            DISMISSED to setOf(CHECKING),
        )
    }
}

enum class FailedStage { CHECK, DOWNLOAD, VERIFY, INSTALL }

internal data class UpdateSnapshot(
    val state: UpdateState = UpdateState.IDLE,
    val release: ReleaseInfo? = null,
    /** The user started this flow (manual check or "Güncelle ve Yükle"), so results are shown. */
    val interactive: Boolean = false,
    val percent: Int = -1,
    val downloaded: Long = 0L,
    val total: Long = -1L,
    val failure: FailureKind? = null,
    val failedStage: FailedStage? = null,
    val retryable: Boolean = false,
    /** For a rate-limited check: when GitHub accepts requests again (epoch millis). */
    val retryAt: Long = 0L,
)

internal interface UpdatePrefs {
    var dismissedTag: String
    var dismissedAt: Long
    var upToDateAt: Long
    /** No GitHub API request before this time (epoch millis). */
    var rateLimitedUntil: Long
    /** The user's choice; off by default. Installation always needs the user's confirmation. */
    var autoDownload: Boolean
    /** Offer GitHub pre-releases (test versions) too; off by default. */
    var prereleases: Boolean
}

internal class SharedUpdatePrefs(private val prefs: SharedPreferences) : UpdatePrefs {
    override var dismissedTag: String
        get() = prefs.getString(KEY_DISMISSED_TAG, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DISMISSED_TAG, value).apply()
    override var dismissedAt: Long
        get() = prefs.getLong(KEY_DISMISSED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_DISMISSED_AT, value).apply()
    override var upToDateAt: Long
        get() = prefs.getLong(KEY_UP_TO_DATE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_UP_TO_DATE_AT, value).apply()
    override var rateLimitedUntil: Long
        get() = prefs.getLong(KEY_RATE_LIMITED_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_RATE_LIMITED_UNTIL, value).apply()
    override var autoDownload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, value).apply()
    override var prereleases: Boolean
        get() = prefs.getBoolean(KEY_PRERELEASES, false)
        set(value) = prefs.edit().putBoolean(KEY_PRERELEASES, value).apply()

    companion object {
        const val FILE = "app_updates"
        private const val KEY_DISMISSED_TAG = "dismissed_tag"
        private const val KEY_DISMISSED_AT = "dismissed_at"
        private const val KEY_UP_TO_DATE_AT = "up_to_date_at"
        private const val KEY_RATE_LIMITED_UNTIL = "rate_limited_until"
        private const val KEY_AUTO_DOWNLOAD = "auto_download"
        private const val KEY_PRERELEASES = "prereleases"
    }
}
