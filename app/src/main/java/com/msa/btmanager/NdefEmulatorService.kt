package com.msa.btmanager

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Emulates a read-only NFC Forum **Type 4 Tag** carrying one NDEF message.
 *
 * Another phone (or an NFC reader such as a Flipper Zero) that taps this phone
 * performs the normal NDEF detection: select NDEF application D2760000850101,
 * read the capability container (E103), then read the NDEF file (E104).
 * If all three answer, the reader sees a normal NDEF tag.
 *
 * What this can do: share a text/URL/vCard-style NDEF payload from this phone.
 * What it cannot do: emulate MIFARE Classic/Ultralight "badge" tags or spoof a
 * UID. Stock Android exposes only ISO-DEP APDU emulation, and never lets an
 * app choose the UID presented on the air.
 */
class NdefEmulatorService : HostApduService() {

    private enum class Selected { NONE, APP, CC, NDEF }

    private var selected = Selected.NONE
    private var ndefFile: ByteArray = byteArrayOf(0x00, 0x00)

    override fun onCreate() {
        super.onCreate()
        reloadFile()
    }

    /** Re-read the payload so edits made in the UI apply to the next tap. */
    private fun reloadFile() {
        val message = NdefEmulator.load(this)
        ndefFile = if (message == null || message.isEmpty()) {
            byteArrayOf(0x00, 0x00)
        } else {
            ByteArray(2 + message.size).also { file ->
                file[0] = ((message.size shr 8) and 0xFF).toByte()
                file[1] = (message.size and 0xFF).toByte()
                System.arraycopy(message, 0, file, 2, message.size)
            }
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) return SW_WRONG_LENGTH

        val ins = commandApdu[1].toInt() and 0xFF
        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF

        var lc = 0
        var data = ByteArray(0)
        var le = 256
        if (commandApdu.size == 5) {
            val raw = commandApdu[4].toInt() and 0xFF
            le = if (raw == 0) 256 else raw
        } else if (commandApdu.size >= 6) {
            lc = commandApdu[4].toInt() and 0xFF
            if (commandApdu.size >= 5 + lc) {
                data = commandApdu.copyOfRange(5, 5 + lc)
                if (commandApdu.size > 5 + lc) {
                    val raw = commandApdu[5 + lc].toInt() and 0xFF
                    le = if (raw == 0) 256 else raw
                }
            }
        }

        return when (ins) {
            INS_SELECT -> select(data)
            INS_READ_BINARY -> readBinary(p1, p2, le)
            INS_UPDATE_BINARY -> SW_WRITE_DENIED
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    override fun onDeactivated(reason: Int) {
        selected = Selected.NONE
    }

    private fun select(data: ByteArray): ByteArray {
        if (data.isEmpty()) return SW_FILE_NOT_FOUND
        return when {
            data.contentEquals(NDEF_APPLICATION_AID) -> {
                selected = Selected.APP
                SW_OK
            }
            data.contentEquals(CC_FILE_ID) -> {
                reloadFile()
                selected = Selected.CC
                SW_OK
            }
            data.contentEquals(NDEF_FILE_ID) -> {
                selected = Selected.NDEF
                SW_OK
            }
            else -> {
                selected = Selected.NONE
                SW_FILE_NOT_FOUND
            }
        }
    }

    private fun readBinary(p1: Int, p2: Int, le: Int): ByteArray {
        val source = when (selected) {
            Selected.CC -> CC_FILE
            Selected.NDEF -> ndefFile
            else -> return SW_FILE_NOT_FOUND
        }
        val offset = (p1 shl 8) or p2
        if (offset >= source.size) return SW_WRONG_LENGTH
        val count = minOf(le, source.size - offset)
        return source.copyOfRange(offset, offset + count) + SW_OK
    }

    companion object {
        /** NDEF Tag Application AID (NFC Forum Type 4 Tag). */
        private val NDEF_APPLICATION_AID = byteArrayOf(
            0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01
        )

        /** Capability Container file id. */
        private val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03)

        /** NDEF data file id. */
        private val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x04)

        /**
         * 15-byte CC: mapping version 2.0, MLe/MLc = 59, one NDEF file control
         * TLV for E104 with a 1024-byte capacity that is readable but not
         * writable (0xFF = no write access).
         */
        private val CC_FILE = byteArrayOf(
            0x00, 0x0F,                          // CCLEN
            0x20,                               // mapping version 2.0
            0x00, 0x3B,                         // MLe
            0x00, 0x3B,                         // MLc
            0x04, 0x06,                         // NDEF File Control TLV, length 6
            0xE1.toByte(), 0x04,                // NDEF file id
            0x04, 0x00,                         // max NDEF size = 1024 bytes
            0x00,                               // read access granted
            0xFF.toByte()                       // write access denied (read-only)
        )

        private const val INS_SELECT = 0xA4
        private const val INS_READ_BINARY = 0xB0
        private const val INS_UPDATE_BINARY = 0xD6

        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x6B.toByte(), 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)
        private val SW_WRITE_DENIED = byteArrayOf(0x69.toByte(), 0x81.toByte())
    }
}
