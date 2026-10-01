package com.ztec.cplay

import android.content.Context
import com.ztec.cplay.airplay.AirPlayIdentity
import com.ztec.cplay.mfi.LocalMfiAuthenticationClient
import com.ztec.cplay.orchestration.MfiTarget
import java.security.MessageDigest

/** Validates the source-embedded offline MFi identity and forces LOCAL MFi mode. */
internal object OfflineMfiBootstrap {
    @Volatile private var ready = false

    @Synchronized fun ensure(context: Context) {
        if (ready) return
        // Prove the embedded key/cert pair is consistent before first connection.
        LocalMfiAuthenticationClient.loadEmbedded()
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        // Drop any leftover on-disk copy from older builds that used APK assets.
        runCatching {
            java.io.File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
                .deleteRecursively()
        }
        ready = true
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal object NexusCpPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("nexuscp", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: context.getString(R.string.your_iphone)
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
}
