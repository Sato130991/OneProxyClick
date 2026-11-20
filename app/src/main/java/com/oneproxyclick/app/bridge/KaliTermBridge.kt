package com.oneproxyclick.app.bridge

import com.oneproxyclick.core.common.tr
import com.oneproxyclick.app.R
import androidx.annotation.StringRes
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.oneproxyclick.app.appGraph
import com.oneproxyclick.core.interceptor.codec.CurlCommand
import com.oneproxyclick.core.storage.TransactionDetail
import com.oneproxyclick.core.storage.export.ExportWriter

/**
 * OneProxyClick → KaliTerm.
 *
 * KaliTerm's public intent API isn't documented, so delivery is tried in order of directness and
 * always resolved against the package first (no blind startActivity crashes):
 * 1. Activity `com.kaliterm.app.RUN_COMMAND` (if KaliTerm exposes one)
 * 2. `ACTION_SEND text/plain` targeted at `com.kaliterm.app`
 * 3. Android share sheet
 * The command is also copied to the clipboard so it can always be pasted into the terminal.
 */
object KaliTermBridge {
    const val PACKAGE = "com.kaliterm.app"
    const val ACTION_RUN_COMMAND = "com.kaliterm.app.RUN_COMMAND"
    const val LAST_SENT_FILE = "last_sent.json"

    enum class Delivery(@StringRes val labelRes: Int) {
        RUN_COMMAND(R.string.delivery_run_command),
        SEND_TO_KALITERM(R.string.delivery_send),
        SHARE_SHEET(R.string.delivery_share_sheet),
    }

    class Outcome(val delivery: Delivery, val jsonPath: String)

    /** Exports `last_sent.json`, then hands the curl command to KaliTerm. Call from the UI. */
    suspend fun send(context: Context, detail: TransactionDetail): Outcome {
        val saved = context.appGraph.exporter.exportReplacing(detail, LAST_SENT_FILE).saved
        val curl = CurlCommand.build(detail.request)
        val text = buildString {
            append("# OneProxyClick #").append(detail.entity.id).append(" → ").append(saved.displayPath).append('\n')
            append("# jq . ").append(CurlCommand.quote(saved.displayPath)).append('\n')
            append(curl)
        }
        copyToClipboard(context, text)
        return Outcome(deliver(context, curl, text, saved.displayPath), saved.displayPath)
    }

    private fun deliver(context: Context, curl: String, text: String, jsonPath: String): Delivery {
        val pm = context.packageManager

        val run = Intent(ACTION_RUN_COMMAND)
            .setPackage(PACKAGE)
            .putExtra(Intent.EXTRA_TEXT, curl)
            .putExtra("command", curl)
            .putExtra("json_path", jsonPath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resolves(pm, run) && tryStart(context, run)) return Delivery.RUN_COMMAND

        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .setPackage(PACKAGE)
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, "OneProxyClick request")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resolves(pm, send) && tryStart(context, send)) return Delivery.SEND_TO_KALITERM

        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(share, context.tr(R.string.share_chooser_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return Delivery.SHARE_SHEET
    }

    @Suppress("DEPRECATION")
    private fun resolves(pm: PackageManager, intent: Intent): Boolean =
        pm.queryIntentActivities(intent, 0).isNotEmpty()

    private fun tryStart(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("OneProxyClick curl", text))
    }

    val exportDir: String get() = ExportWriter.PUBLIC_DIR
}

/** Exports `assets/opc-cli.sh` (with the current token baked in) to the export folder. */
object CliScript {
    const val FILE = "opc-cli.sh"

    fun export(context: Context, apiPort: Int): ExportWriter.Saved {
        val template = context.assets.open(FILE).bufferedReader().use { it.readText() }
        val script = template
            .replace("__OPC_TOKEN__", BridgeToken.get(context))
            .replace("__OPC_EXPORT_DIR__", ExportWriter.PUBLIC_DIR)
            .replace("__OPC_API_PORT__", apiPort.toString())
        return context.appGraph.exportWriter.writeOrReplace(FILE, ExportWriter.MIME_BINARY) {
            it.write(script.toByteArray(Charsets.UTF_8))
        }
    }
}
