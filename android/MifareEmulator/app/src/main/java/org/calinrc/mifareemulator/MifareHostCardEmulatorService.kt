package org.calinrc.mifareemulator

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

class MifareHostCardEmulatorService : HostApduService(){
    companion object {
        private const val TAG = "hce"
        private val STATUS_SUCCESS = ubyteArrayOf(0x90u, 0x00u)
        private val STATUS_FAILED = byteArrayOf(0x6F, 0x00)
        private val CLA_NOT_SUPPORTED = byteArrayOf(0x6E, 0x00)
        private val INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        private val AID = ubyteArrayOf(0xF0u, 0x01u, 0x02u, 0x03u, 0x04u, 0x05u, 0x06u)
        private val SELECT_INS:UByte = 0xA4u
        private val DEFAULT_CLA:Byte = 0x00
        private val TEST_CLA:UByte = 0xF0u
        private val MIN_APDU_LENGTH = 6
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        return STATUS_FAILED
//        if (commandApdu == null) {
//            return STATUS_FAILED
//        }
//
//
//        if (commandApdu?.length < MIN_APDU_LENGTH) {
//            return STATUS_FAILED
//        }
//
//        Log.d(TAG, "apdu: $commandApdu")
//
//        val cla = commandApdu[0]
//
//        if (cla != DEFAULT_CLA) {
//            return if (cla == TEST_CLA) {
//                AID + STATUS_SUCCESS
//            } else {
//                CLA_NOT_SUPPORTED
//            }
//        }
//
//        if (commandApdu[1] != SELECT_INS) {
//            return INS_NOT_SUPPORTED
//        }
//
//        return if (hexCommandApdu.substring(10, 24) == AID)  {
//            STATUS_SUCCESS
//        } else {
//            STATUS_FAILED
//        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "Deactivated: $reason")
    }
}