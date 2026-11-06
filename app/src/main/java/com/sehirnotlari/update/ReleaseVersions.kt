package com.sehirnotlari.update

/**
 * Compares a GitHub release tag with the installed versionName / versionCode.
 * A leading "v" is ignored. Numeric dot-segments are compared in order.
 * When the names are equal, an optional "+N" or "(N)" suffix is compared with versionCode.
 */
internal object ReleaseVersions {
    fun isNewer(remoteTag: String, localVersionName: String, localVersionCode: Long): Boolean {
        val remote = parse(remoteTag) ?: return false
        val local = parse(localVersionName) ?: return false
        val compared = compareParts(remote.parts, local.parts)
        if (compared > 0) return true
        if (compared < 0) return false
        val remoteCode = remote.versionCode ?: return false
        return remoteCode > localVersionCode
    }

    /** True when a downloaded APK's versionName is the version the release tag advertises. */
    fun sameVersion(tag: String, versionName: String): Boolean {
        val remote = parse(tag) ?: return false
        val apk = parse(versionName) ?: return false
        if (remote.parts.isEmpty() || apk.parts.isEmpty()) return false
        return compareParts(remote.parts, apk.parts) == 0
    }

    /** The versionCode carried by a "+N" or "(N)" tag suffix, if any. */
    fun declaredCode(tag: String): Long? = parse(tag)?.versionCode

    fun displayVersion(tag: String): String {
        val trimmed = tag.trim()
        if (trimmed.isEmpty()) return ""
        var core = if (trimmed[0] == 'v' || trimmed[0] == 'V') trimmed.substring(1) else trimmed
        core = core.replace(Regex("""(?:\+\d+|\s*\(\d+\))\s*$"""), "")
        return "v$core"
    }

    private data class Parsed(val parts: List<Int>, val versionCode: Long?)

    private fun parse(raw: String): Parsed? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (text[0] == 'v' || text[0] == 'V') text = text.substring(1).trim()
        var versionCode: Long? = null
        val suffix = SUFFIX.matchEntire(text)
        if (suffix != null) {
            text = suffix.groupValues[1].trim()
            val codeText = suffix.groupValues[2].ifEmpty { suffix.groupValues[3] }
            versionCode = codeText.toLongOrNull()
        }
        if (text.isEmpty()) {
            return if (versionCode != null) Parsed(emptyList(), versionCode) else null
        }
        val parts = mutableListOf<Int>()
        for (token in text.split('.')) {
            val digits = token.trim().takeWhile { it.isDigit() }
            if (digits.isEmpty()) return null
            parts.add(digits.toIntOrNull() ?: return null)
        }
        return Parsed(parts, versionCode)
    }

    private fun compareParts(left: List<Int>, right: List<Int>): Int {
        val count = maxOf(left.size, right.size)
        for (index in 0 until count) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private val SUFFIX = Regex("""^(.*?)(?:\+(\d+)|\s*\((\d+)\))\s*$""")
}
