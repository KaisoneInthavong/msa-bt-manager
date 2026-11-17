package com.msa.btmanager

import android.nfc.Ndef
import android.nfc.NdefFormatable
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.msa.btmanager.data.AppDatabase
import com.msa.btmanager.data.TagDump
import java.util.Locale

/**
 * Flipper-inspired NFC tool running on the phone's own 13.56 MHz radio:
 * read + hex dump + MIFARE Classic dictionary attack + clone/restore + NDEF write.
 */
class TagActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var tvTagLog: TextView
    private lateinit var etNdefText: EditText

    private val db by lazy { AppDatabase.get(this) }

    private var copiedPayload: String? = null
    private var copiedDump: String? = null       // "XX: HEX..." lines of the last dump
    private var copiedDumpUid: String = ""
    private var copiedDumpKeys: List<String> = emptyList()

    private var pendingWrite: String? = null
    private var pendingRestoreDump: String? = null
    private var lastReadPayload: String? = null
    private var lastReadUid: String = ""

    /** Flipper-style default key dictionary for MIFARE Classic attacks. */
    private val keyDictionary = listOf(
        "FFFFFFFFFFFF", "A0A1A2A3A4A5", "D3F7D3F7D3F7", "000000000000",
        "B0B1B2B3B4B5", "4D3A99C351DD", "1A982C7E459A", "AABBCCDDEEFF",
        "713C570432CD", "584A30D2CBA5", "44796173732E", "6321CDB4A4B8",
        "7D8E758F41D3", "96A301A03CC6", "9E3631188C9F", "A4973D9BC0AF",
        "AA5F92BFC456", "AB5C47BE7B75", "B5FF67CBA952", "E8AB53F125B7",
        "C71C5B9B8E3B", "D67E4497C502", "0BE795ACB373", "4E36A2572BCE"
    )

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

        // Flipper-style: copy the full hex dump of the last tag for cloning
        findViewById<Button>(R.id.btnDumpCopy).setOnClickListener {
            val dump = lastDump
            if (dump == null) {
                tagLog("No dump to copy — tap a tag first.")
            } else {
                copiedDump = dump.dumpHex
                copiedDumpUid = dump.uidHex
                copiedDumpKeys = dump.foundKeys.split(",").filter { it.isNotBlank() }
                tagLog("Copied full dump of tag ${dump.uidHex} (${dump.dumpHex.length / 3} block entries, ${dump.foundKeys.split(",").count { it.isNotBlank() }} key(s)).")
            }
        }

        findViewById<Button>(R.id.btnRestoreDump).setOnClickListener {
            val dump = copiedDump
            if (dump == null) {
                tagLog("No copied dump. Tap a tag, then 'Copy full dump'.")
            } else {
                pendingRestoreDump = dump
                tagLog("Armed: will restore the copied dump ($copiedDumpUid) to the next tapped tag.")
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
                        sb.appendLine("${d.uidHex} | ${d.tagType.take(28)} | key(s): ${d.foundKeys.ifBlank { "-" }}")
                    }
                    sb.appendLine("--- end ---")
                }
                runOnUiThread { tagLog(sb.toString()) }
            }.start()
        }
    }

    private var lastDump: TagDump? = null

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

        // 1. Pending full-dump restore
        pendingRestoreDump?.let { dump ->
            val error = tryRestoreDump(tag, dump)
            pendingRestoreDump = null
            runOnUiThread {
                if (error == null) tagLog("Dump restored to $uidHex (writable regions).")
                else tagLog("Restore failed: $error")
            }
        }

        // 2. Pending NDEF write
        pendingWrite?.let { text ->
            val error = tryWrite(tag, text)
            pendingWrite = null
            runOnUiThread {
                if (error == null) tagLog("Wrote \"$text\" to tag $uidHex!")
                else tagLog("Write failed: $error")
            }
        }

        // 3. Read + dump + save
        readAndSave(tag, uidHex)
    }

    // ---------- READ / DUMP ----------

    private fun readAndSave(tag: Tag, uidHex: String) {
        val sb = StringBuilder()
        sb.appendLine("Tag tapped!")
        sb.appendLine("UID: $uidHex")
        sb.appendLine("Techs: ${tag.techList.joinToString()}")

        var payloadText = ""
        var ndefInfo = "no NDEF"

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                ndefInfo = "${ndef.type} ${ndef.maxSize}B wr=${ndef.isWritable}"
                sb.appendLine("NDEF: $ndefInfo")
                val msg = ndef.ndefMessage
                if (msg == null) {
                    sb.appendLine("NDEF: empty tag")
                } else {
                    val text = extractText(msg)
                    payloadText = text
                    sb.appendLine(if (text.isBlank()) "NDEF: ${msg.records.size} record(s), no text" else "NDEF content: $text")
                }
            } catch (e: Exception) {
                sb.appendLine("NDEF read error: ${e.message}")
            } finally {
                try { ndef.close() } catch (_: Exception) {}
            }
        } else {
            sb.appendLine("NDEF: ${if (NdefFormatable.get(tag) != null) "not formatted yet" else "not supported"}")
        }

        // ---- Full dump: MIFARE Classic dictionary attack, or Ultralight page sweep ----
        var dumpLines = ""
        var foundKeys = ""
        val mfc = MifareClassic.get(tag)
        if (mfc != null) {
            sb.appendLine("\n--- MIFARE CLASSIC DICTIONARY ATTACK (${keyDictionary.size} keys) ---")
            val result = dumpMifareClassic(mfc)
            dumpLines = result.first
            foundKeys = result.second
            sb.append(dumpLines.ifBlank { "no data" })
            sb.appendLine("\nFound keys: ${foundKeys.ifBlank { "none — tag fully locked" }}")
        } else {
            val mul = MifareUltralight.get(tag)
            if (mul != null) {
                sb.appendLine("\n--- MIFARE ULTRALIGHT / NTAG PAGE DUMP ---")
                dumpLines = dumpMifareUltralight(mul)
                sb.append(dumpLines.ifBlank { "no data" })
            }
        }

        // Memory: save the dump
        val dump = TagDump(
            uidHex = uidHex,
            tagType = ndefInfo,
            sizeBytes = dumpLines.toByteArray().size,
            payload = payloadText,
            dumpHex = dumpLines,
            foundKeys = foundKeys
        )
        lastDump = dump
        lastReadPayload = payloadText.ifBlank { null }
        lastReadUid = uidHex
        Thread { db.tagDumpDao().insert(dump) }.start()

        runOnUiThread { tagLog(sb.toString()) }
    }

    private fun dumpMifareClassic(mfc: MifareClassic): Pair<String, String> {
        val out = StringBuilder()
        val found = mutableSetOf<String>()
        val keys = keyDictionary.map { hexToBytes(it) }
        try {
            mfc.connect()
            for (sector in 0 until mfc.sectorCount) {
                var authed = false
                for (k in keys) {
                    if (mfc.authenticateSectorWithKeyA(sector, k)) {
                        authed = true; found.add("%012X".format(toLong(k))); break
                    }
                }
                if (!authed) {
                    for (k in keys) {
                        if (mfc.authenticateSectorWithKeyB(sector, k)) {
                            authed = true; found.add("%012X".format(toLong(k))); break
                        }
                    }
                }
                if (!authed) {
                    out.appendLine(">> $sector: LOCKED (no dictionary key)")
                    continue
                }
                val first = mfc.sectorToBlock(sector)
                for (b in first until first + mfc.getBlockCountInSector(sector)) {
                    out.appendLine(blockLine(b, try { mfc.readBlock(b) } catch (e: Exception) { ByteArray(16) }))
                }
            }
        } catch (e: Exception) {
            out.appendLine("MIFARE error: ${e.message}")
        } finally {
            try { mfc.close() } catch (_: Exception) {}
        }
        return out.toString() to found.joinToString(",").also { mfcKeysCache = it }
    }

    private var mfcKeysCache: String = ""

    private fun dumpMifareUltralight(mul: MifareUltralight): String {
        val out = StringBuilder()
        try {
            mul.connect()
            var page = 0
            while (page < 160) {
                val data = try { mul.readPages(page) } catch (e: Exception) { break }
                for (i in 0 until 4) {
                    if (page + i >= 160) break
                    val slice = data.copyOfRange(i * 4, i * 4 + 4)
                    if (slice.all { it == 0x00.toByte() }) continue // skip empty trailing pages
                    out.appendLine(blockLine(page + i, slice))
                }
                page += 4
            }
        } catch (e: Exception) {
            out.appendLine("ULTRALIGHT error: ${e.message}")
        } finally {
            try { mul.close() } catch (_: Exception) {}
        }
        return out.toString()
    }

    private fun blockLine(index: Int, data: ByteArray): String =
        "%02X: ".format(index) + data.joinToString("") { "%02X".format(it) }

    // ---------- WRITE / RESTORE ----------

    private fun tryWrite(tag: Tag, text: String): String? {
        return try {
            val record: NdefRecord = NdefRecord.createTextRecord("en", text)
            val message = NdefMessage(arrayOf(record))
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                if (!ndef.isWritable) return "Tag is read-only"
                val needed = message.toByteArray().size
                if (ndef.maxSize < needed) return "Message ($needed B) does not fit (${ndef.maxSize} B)"
                ndef.connect()
                ndef.writeNdefMessage(message)
                try { ndef.close() } catch (_: Exception) {}
                null
            } else {
                val fmt = NdefFormatable.get(tag)
                    ?: return "Tag does not support NDEF (${tag.techList.joinToString()})"
                fmt.connect()
                fmt.format(message)
                try { fmt.close() } catch (_: Exception) {}
                null
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Restores a copied dump onto a compatible tag. Skips UID/lock/config sectors. */
    private fun tryRestoreDump(tag: Tag, dump: String): String? {
        return try {
            val blocks = parseDump(dump)
            if (blocks.isEmpty()) return "dump is empty"

            val mul = MifareUltralight.get(tag)
            if (mul != null) {
                mul.connect()
                var written = 0
                blocks.forEach { (page, data) ->
                    // Skip manufacturer (0), lock (2), OTP (3) and any trailing config pages
                    if (page < 4) return@forEach
                    try {
                        mul.writePage(page, data)
                        written++
                    } catch (e: Exception) {
                        skipLog("page $page skipped: ${e.message}")
                    }
                }
                mul.close()
                skipLog("ULTRALIGHT restore: $written page(s) written, rest skipped (lock/OTP/config are factory-protected).")
                return null
            }

            val mfc = MifareClassic.get(tag)
            if (mfc != null) {
                mfc.connect()
                val keys = (copiedDumpKeys + keyDictionary).map { hexToBytes(it) }.distinct()
                var written = 0
                blocks.forEach { (block, data) ->
                    // Skip manufacturer block 0..1 and every sector trailer
                    if (block == 0 || block == 1) return@forEach
                    val trailer = (block % 4 == 3 && block < 256)
                    if (trailer) { skipLog("block $block is a sector trailer — skipped"); return@forEach }
                    val sector = if (block < 128) block / 4 else 32 + (block - 128) / 16
                    var ok = false
                    for (k in keys) {
                        try {
                            if (mfc.authenticateSectorWithKeyA(sector, k) ||
                                mfc.authenticateSectorWithKeyB(sector, k)
                            ) {
                                mfc.writeBlock(block, data)
                                ok = true
                                break
                            }
                        } catch (_: Exception) {}
                    }
                    if (ok) written++ else skipLog("block $block locked — skipped")
                }
                mfc.close()
                skipLog("MIFARE restore: $written block(s) written.")
                return null
            }

            "unsupported tag type for restore (${tag.techList.joinToString()})"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun skipLog(note: String) { runOnUiThread { tagLog(note) } }

    private fun parseDump(dump: String): Map<Int, ByteArray> {
        val map = mutableMapOf<Int, ByteArray>()
        dump.lineSequence().forEach { line ->
            if (line.startsWith(">>") || line.contains("LOCKED")) return@forEach
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val index = line.substring(0, colon).trim().toIntOrNull() ?: return@forEach
            val hex = line.substring(colon + 1).replace(" ", "").trim()
            if (hex.isEmpty()) return@forEach
            map[index] = hexToBytes(hex)
        }
        return map
    }

    // ---------- HELPERS ----------

    private fun extractText(msg: NdefMessage): String {
        val sb = StringBuilder()
        msg.records.forEach { rec ->
            val payload = rec.payload ?: return@forEach
            if (payload.isEmpty()) return@forEach
            val langLen = (payload.get(0).toInt() and 0x3F)
            if (payload.size > langLen) {
                sb.append(String(payload, 1 + langLen, payload.size - 1 - langLen, Charsets.UTF_8))
                sb.append(" ")
            }
        }
        return sb.toString().trim()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        val out = ByteArray(clean.length / 2)
        for (i in 0 until out.size) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4)
                    + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun toLong(b: ByteArray): Long {
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }

    private fun tagLog(message: String) {
        tvTagLog.append("\n$message")
    }
}
