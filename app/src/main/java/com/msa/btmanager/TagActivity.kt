package com.msa.btmanager

import android.nfc.Ndef
import android.nfc.NdefFormatable
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.msa.btmanager.data.AppDatabase
import com.msa.btmanager.data.TagDump

class TagActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var tvTagLog: TextView
    private lateinit var etNdefText: EditText

    private val db by lazy { AppDatabase.get(this) }

    private var copiedPayload: String? = null
    private var pendingWrite: String? = null
    private var lastReadPayload: String? = null
    private var lastReadUid: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tag)

        tvTagLog = findViewById(R.id.tvTagLog)
        etNdefText = findViewById(R.id.etNdefText)

        findViewById<Button>(R.id.btnWrite).setOnClickListener {
            val text = etNdefText.text.toString()
            if (text.isBlank()) {
                tagLog("Type some text first.")
            } else {
                pendingWrite = text
                tagLog("Armed: will write \"$text\" to the next tapped tag.")
            }
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val payload = lastReadPayload
            if (payload.isNullOrEmpty()) {
                tagLog("Nothing to copy — read a tag first.")
            } else {
                copiedPayload = payload
                tagLog("Copied payload of tag $lastReadUid. Tap 'Write copied payload', then present the target tag.")
            }
        }

        findViewById<Button>(R.id.btnWriteCopied).setOnClickListener {
            val payload = copiedPayload
            if (payload.isNullOrEmpty()) {
                tagLog("No copied payload. Read a tag and tap Copy.")
            } else {
                pendingWrite = payload
                tagLog("Armed: will write copied payload to the next tapped tag.")
            }
        }

        findViewById<Button>(R.id.btnDumps).setOnClickListener {
            Thread {
                val dumps = db.tagDumpDao().getAll()
                val sb = StringBuilder()
                if (dumps.isEmpty()) sb.appendLine("No saved tag dumps yet.")
                else {
                    sb.appendLine("--- Saved tags (${dumps.size}) ---")
                    dumps.forEach { d ->
                        sb.appendLine("${d.uidHex} | ${d.tagType} | ${d.payload.ifBlank { "(no text)" }}")
                    }
                    sb.appendLine("--- end ---")
                }
                runOnUiThread { tagLog(sb.toString()) }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        val nfc = NfcAdapter.getDefaultAdapter(this)
        if (nfc == null) {
            tagLog("This phone has no NFC hardware.")
            return
        }
        nfc.enableReaderMode(
            this, this, NfcAdapter.FLAG_READER_NFC_A
                    or NfcAdapter.FLAG_READER_NFC_B
                    or NfcAdapter.FLAG_READER_NFC_F
                    or NfcAdapter.FLAG_READER_NFC_V, null
        )
    }

    override fun onPause() {
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        super.onPause()
    }

    override fun onTagDiscovered(tag: Tag?) {
        tag ?: return
        val uidHex = tag.id?.joinToString("") { "%02X".format(it) } ?: "unknown"

        // Pending write first, if armed
        val toWrite = pendingWrite
        if (toWrite != null) {
            val error = tryWrite(tag, toWrite)
            pendingWrite = null
            if (error == null) {
                runOnUiThread { tagLog("Wrote successfully to tag $uidHex!") }
            } else {
                runOnUiThread { tagLog("Write failed: $error") }
            }
        }

        readAndSave(tag, uidHex)
    }

    private fun readAndSave(tag: Tag, uidHex: String) {
        val sb = StringBuilder()
        if (lastReadUid != uidHex) sb.appendLine("Tag tapped!")
        sb.appendLine("UID: $uidHex")

        var payloadText = ""
        var typeText = "unknown"
        var size = 0

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                typeText = ndef.type
                size = ndef.maxSize
                sb.appendLine("Type: $typeText | Max ${ndef.maxSize} bytes | writable=${ndef.isWritable}")
                val msg = ndef.ndefMessage
                if (msg == null) {
                    sb.appendLine("NDEF: empty tag")
                } else {
                    val text = extractText(msg)
                    payloadText = text
                    sb.appendLine(if (text.isBlank()) "NDEF: ${msg.records.size} record(s), no text" else "Content: $text")
                }
            } catch (e: Exception) {
                sb.appendLine("Read error: ${e.message}")
            } finally {
                try { ndef.close() } catch (_: Exception) {}
            }
        } else {
            val fmt = NdefFormatable.get(tag)
            typeText = "techs: ${tag.techList.joinToString()}"
            if (fmt != null) {
                sb.appendLine("Tag type: NDEF formatable (can be formatted when writing)")
            } else {
                sb.appendLine("Tag type: not NDEF-compatible")
            }
        }

        lastReadPayload = payloadText.ifBlank { null }
        lastReadUid = uidHex

        // Memory: save the dump
        Thread {
            db.tagDumpDao().insert(
                TagDump(uidHex = uidHex, tagType = typeText, sizeBytes = size, payload = payloadText)
            )
        }.start()

        runOnUiThread { tagLog(sb.toString()) }
    }

    /** Decodes RTD_TEXT records of an NDEF message. */
    private fun extractText(msg: NdefMessage): String {
        val sb = StringBuilder()
        msg.records.forEach { rec ->
            val payload = rec.payload ?: return@forEach
            if (payload.isEmpty()) return@forEach
            val langLen = (payload.get(0).toInt() and 0x3F)
            if (payload.size > langLen) {
                val text = String(payload, 1 + langLen, payload.size - 1 - langLen, Charsets.UTF_8)
                sb.append(text).append(" ")
            }
        }
        return sb.toString().trim()
    }

    /** Returns null on success, an error description otherwise. */
    private fun tryWrite(tag: Tag, text: String): String? {
        return try {
            val record = NdefRecord.createTextRecord("en", text)
            val message = NdefMessage(arrayOf(record))

            val ndef = Ndef.get(tag)
            if (ndef != null) {
                if (!ndef.isWritable) return "Tag is read-only"
                val needed = message.toByteArray().size
                if (ndef.maxSize < needed) return "Message ($needed bytes) does not fit tag (${ndef.maxSize} bytes)"
                ndef.connect()
                ndef.writeNdefMessage(message)
                try { ndef.close() } catch (_: Exception) {}
                null
            } else {
                val fmt = NdefFormatable.get(tag)
                    ?: return "Tag does not support NDEF (type: ${tag.techList.joinToString()})"
                fmt.connect()
                fmt.format(message)
                try { fmt.close() } catch (_: Exception) {}
                null
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun tagLog(message: String) {
        tvTagLog.append("\n$message")
    }
}
