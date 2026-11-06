package com.sehirnotlari.update

import java.io.File
import java.security.MessageDigest

/**
 * What PackageManager reports about an APK. Signer values are SHA-256 digests of the
 * signing certificates, lowercase hex. [pastSigners] holds the APK's key-rotation history.
 */
internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val signers: Set<String>,
    val pastSigners: Set<String> = emptySet(),
)

internal data class InstalledApp(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signers: Set<String>,
)

internal fun interface ApkInspector {
    /** Null when the file is not a readable APK. */
    fun inspect(file: File): ApkIdentity?
}

internal object ApkVerifier {
    /**
     * Full check of a downloaded file against the release it was announced in and the app
     * that is installed now. Returns the first failure, or null when installation may proceed.
     */
    fun verify(file: File, release: ReleaseInfo, installed: InstalledApp, inspector: ApkInspector): FailureKind? {
        val apk = release.apk ?: return FailureKind.BAD_RELEASE
        if (apk.sha256.isEmpty()) return FailureKind.BAD_RELEASE
        if (!file.isFile) return FailureKind.STORAGE
        if (file.length() != apk.size) return FailureKind.SIZE
        if (sha256(file) != apk.sha256) return FailureKind.HASH
        return checkIdentity(inspector.inspect(file), installed, release.tag, release.versionCode)
    }

    fun checkIdentity(apk: ApkIdentity?, installed: InstalledApp, tag: String, declaredCode: Long? = null): FailureKind? {
        if (apk == null) return FailureKind.PACKAGE
        if (apk.packageName != installed.packageName) return FailureKind.PACKAGE
        if (apk.versionCode <= installed.versionCode) return FailureKind.VERSION
        if (declaredCode != null && apk.versionCode != declaredCode) return FailureKind.VERSION
        if (!ReleaseVersions.sameVersion(tag, apk.versionName)) return FailureKind.VERSION
        if (!signersCompatible(apk, installed)) return FailureKind.SIGNATURE
        return null
    }

    /** Same signer set, or a single-key rotation whose history includes the installed key. */
    fun signersCompatible(apk: ApkIdentity, installed: InstalledApp): Boolean {
        if (apk.signers.isEmpty() || installed.signers.isEmpty()) return false
        if (apk.signers == installed.signers) return true
        return installed.signers.size == 1 && apk.signers.size == 1 && installed.signers.single() in apk.pastSigners
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
