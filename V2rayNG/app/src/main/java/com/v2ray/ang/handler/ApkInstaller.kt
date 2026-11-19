package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Hands an APK to the phone's installer.
 *
 * The app used to put the update into the Download folder and hope the folder's own screen
 * would install it. That hope is misplaced twice over: the folder's screen is missing on
 * older Android, and every app that tries to install needs the phone's permission to do so,
 * which RX VPN never asked for — so the tap led nowhere and the update looked broken.
 *
 * The app asks for that permission now and opens the installer itself. And because a page
 * that was downloaded instead of the file looks exactly like a failed install, the file is
 * first checked to be an APK at all, so the phone can say what is wrong instead of nothing.
 */
object ApkInstaller {

    /**
     * Says, for the log, which certificate this phone's own copy of the app is
     * signed with.
     *
     * An install that is refused for a signature mismatch is refused with the same
     * words as one refused for an unfinished download, and the signature is the one
     * difference that cannot be argued with: if the installed copy was signed with
     * another key — one from before the key was made permanent, or a build from
     * another line — no build from this repository will ever replace it without an
     * uninstall first. Recording it means the answer is in the log the next time
     * this is asked, instead of in a guess.
     */
    fun logInstalledSignature(context: Context) {
        val printed = runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            info.signatures?.firstOrNull()?.toByteArray()?.let { signature ->
                java.security.MessageDigest.getInstance("SHA-256").digest(signature)
                    .joinToString("") { "%02x".format(it) }
            }
        }.getOrNull()
        AppLog.info(
            AppLog.Section.Update,
            "Installed copy: ${context.packageName} versionName ${BuildConfig.VERSION_NAME} versionCode ${BuildConfig.VERSION_CODE}, " +
                "signed with ${printed ?: "an unreadable certificate"}",
        )
    }

    /** Every zip file, and therefore every APK, starts with these two letters. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B)

    /**
     * Our builds are far larger than this; an HTML page saved by mistake is far
     * smaller. It used to be five megabytes, which is true of the builds with the
     * engine in them and not of every build that has ever been handed out — and the
     * check's job is to tell an APK from a web page, not to tell one build from
     * another.
     */
    private const val MIN_APK_BYTES = 2L * 1024 * 1024

    /** What the phone reports for the file behind [uri], or -1 when it cannot say. */
    fun sizeOf(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && index >= 0) cursor.getLong(index) else -1L
        } ?: -1L

    /**
     * Whether [uri] really holds an APK: big enough, and beginning like a zip file.
     * A download that was refused — a draft release is invisible to anyone who is not
     * signed in — arrives as a small page and would otherwise fail as "couldn't install".
     */
    fun looksLikeApk(context: Context, uri: Uri): Boolean {
        val size = sizeOf(context, uri)
        if (size in 0 until MIN_APK_BYTES) return false
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val head = ByteArray(ZIP_MAGIC.size)
                val read = stream.read(head)
                read == ZIP_MAGIC.size && head[0] == ZIP_MAGIC[0] && head[1] == ZIP_MAGIC[1]
            } ?: false
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "APK could not be inspected", e)
            false
        }
    }

    /** Whether the phone will let this app install anything at all. */
    fun mayInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Opens the one screen where the phone's owner allows installs from this app. */
    fun openPermissionSetting(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Install permission screen could not be opened", e)
        }
    }

    /** A URI for a file the app keeps itself, so the installer may read it. */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.cache", file)

    /**
     * Asks the phone to install [uri]. Returns false when the phone first wants its
     * owner's permission — the setting is opened for them in that case.
     */
    fun install(context: Context, uri: Uri): Boolean {
        // Written before the hand-off, so the log always holds the two facts an
        // "app not installed" has to be explained with: what this phone is running
        // and what signed it.
        logInstalledSignature(context)
        if (!mayInstall(context)) {
            openPermissionSetting(context)
            return false
        }
        return try {
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                }
            )
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Installer could not be opened", e)
            false
        }
    }
}
