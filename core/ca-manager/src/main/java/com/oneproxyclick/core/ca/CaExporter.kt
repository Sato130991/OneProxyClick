package com.oneproxyclick.core.ca

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.security.KeyChain
import com.oneproxyclick.core.common.tr

/** Export / installation helpers for the root CA. */
object CaExporter {

    /**
     * Writes the CA (PEM) to `/sdcard/Download/OneProxyClick_CA.crt` via MediaStore
     * (no storage permission required on API 29+). Previous copies owned by this app are replaced.
     */
    fun exportToDownloads(context: Context, ca: CertificateAuthority): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(CertificateAuthority.EXPORT_FILE_NAME),
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, CertificateAuthority.EXPORT_FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: error(context.tr(R.string.ca_export_create_failed, CertificateAuthority.EXPORT_FILE_NAME))
        resolver.openOutputStream(uri)?.use { it.write(ca.caPem().toByteArray(Charsets.US_ASCII)) }
            ?: error(context.tr(R.string.ca_export_open_failed, uri.toString()))
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /**
     * Classic credential installer intent. On Android 11+ the system no longer installs CA
     * certificates from this intent and instead tells the user to use Settings; use
     * [securitySettingsIntent] then and pick the exported file under
     * "Encryption & credentials > Install a certificate > CA certificate".
     */
    fun installIntent(ca: CertificateAuthority): Intent =
        KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, ca.caDer())
            putExtra(KeyChain.EXTRA_NAME, CertificateAuthority.CA_COMMON_NAME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun securitySettingsIntent(): Intent =
        Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** True if the CA is present in the user ("added by user") trust store. */
    fun isInstalledInUserStore(ca: CertificateAuthority): Boolean = runCatching {
        val ks = java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val target = ca.caDer()
        ks.aliases().toList().any { alias ->
            alias.startsWith("user:") && ks.getCertificate(alias)?.encoded?.contentEquals(target) == true
        }
    }.getOrDefault(false)
}
