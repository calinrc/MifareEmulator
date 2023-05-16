package org.calinrc.mifareemulator

import android.nfc.cardemulation.HostNfcFService
import android.os.Bundle
import android.util.Log

class MifareHostNfcFCardEmulatorService : HostNfcFService() {
    companion object {
        private const val TAG = "NfcFHce"
        private val STATUS_SUCCESS = byteArrayOf(0x90u.toByte(), 0x00)
        private val STATUS_FAILED = byteArrayOf(0x6F, 0x00)
        private val CLA_NOT_SUPPORTED = byteArrayOf(0x6E, 0x00)
        private val INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        private val AID = byteArrayOf(0xF0u.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        private val SELECT_INS: Byte = 0xA4u.toByte()
        private val DEFAULT_CLA: Byte = 0x00
        private val TEST_CLA: Byte = 0xF0u.toByte()
        private val MIN_APDU_LENGTH = 6
    }
    override fun processNfcFPacket(commandPacket: ByteArray?, extras: Bundle?): ByteArray {
        Log.d(TAG, "processCommandApdu")
        if (commandPacket == null || commandPacket.size < MIN_APDU_LENGTH) {
            return STATUS_FAILED
        }


        Log.d(TAG, "apdu: $commandPacket")
        return STATUS_SUCCESS
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "Deactivated: $reason")
    }
}