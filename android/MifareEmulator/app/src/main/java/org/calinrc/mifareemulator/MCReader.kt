package org.calinrc.mifareemulator

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.util.SparseArray
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class MCReader {
    companion object {

        val NO_KEY = "------------"
        val NO_DATA = "--------------------------------"
        val DEFAULT_KEY = "FFFFFFFFFFFF"
        val LOG_TAG = MCReader::class.java.simpleName

        /**
         * Patch a possibly broken Tag object of HTC One (m7/m8) or Sony
         * Xperia Z3 devices (with Android 5.x.)
         *
         * HTC One: "It seems, the reason of this bug is TechExtras of NfcA is null.
         * However, TechList contains MifareClassic." -- bildin.
         * This method will fix this. For more information please refer to
         * https://github.com/ikarus23/MifareClassicTool/issues/52
         * This patch was provided by bildin (https://github.com/bildin).
         *
         * Sony Xperia Z3 (+ emulated MIFARE Classic tag): The buggy tag has
         * two NfcA in the TechList with different SAK values and a MifareClassic
         * (with the Extra of the second NfcA). Both, the second NfcA and the
         * MifareClassic technique, have a SAK of 0x20. According to NXP's
         * guidelines on identifying MIFARE tags (Page 11), this a MIFARE Plus or
         * MIFARE DESFire tag. This method creates a new Extra with the SAK
         * values of both NfcA occurrences ORed (as mentioned in NXP's
         * MIFARE type identification procedure guide) and replace the Extra of
         * the first NfcA with the new one. For more information please refer to
         * https://github.com/ikarus23/MifareClassicTool/issues/64
         * This patch was provided by bildin (https://github.com/bildin).
         *
         * @param tag The possibly broken tag.
         * @return The fixed tag.
         */
        fun patchTag(tag: Tag?): Tag? {
            val ntag = tag ?: return null
            val techList = tag.techList
            val oldParcel = Parcel.obtain()
            tag.writeToParcel(oldParcel, 0)
            oldParcel.setDataPosition(0)
            val len = oldParcel.readInt()
            var id = ByteArray(0)
            if (len >= 0) {
                id = ByteArray(len)
                oldParcel.readByteArray(id)
            }
            val oldTechList = IntArray(oldParcel.readInt())
            oldParcel.readIntArray(oldTechList)
            val oldTechExtras = oldParcel.createTypedArray(Bundle.CREATOR)
            val serviceHandle = oldParcel.readInt()
            val isMock = oldParcel.readInt()
            val tagService: IBinder? = if (isMock == 0) oldParcel.readStrongBinder() else null
            oldParcel.recycle()
            var nfcaIdx: Int = -1
            var mcIdx: Int = -1
            var sak = 0
            var isFirstSak = true
            for ((i, techItem) in techList.withIndex()) {
                if (techItem.equals(NfcA::class.java.name)) {
                    if (nfcaIdx == -1) {
                        nfcaIdx = i
                    }
                    if (oldTechExtras!![i] != null
                        && oldTechExtras[i].containsKey("sak")
                    ) {
                        sak = (sak or oldTechExtras[i].getShort("sak").toInt())
                        isFirstSak = nfcaIdx == i
                    }
                } else if (techItem.equals(MifareClassic::class.java.name)) {
                    mcIdx = i
                }
            }

            var modified = false
            // Patch the double NfcA issue(with different SAK) for
            // Sony Z3 devices.

            // Patch the double NfcA issue (with different SAK) for
            // Sony Z3 devices.
            if (!isFirstSak) {
                oldTechExtras!![nfcaIdx].putShort("sak", sak.toShort())
                modified = true
            }
            // Patch the wrong index issue for HTC One devices.

            // Patch the wrong index issue for HTC One devices.
            if (nfcaIdx != -1 && mcIdx != -1 && oldTechExtras!![mcIdx] == null) {
                oldTechExtras[mcIdx] = oldTechExtras[nfcaIdx]
                modified = true
            }
            if (!modified) {
                // Old tag was not modivied. Return the old one.
                return ntag
            }
            // Old tag was modified. Create a new tag with the new data.
            val newParcel = Parcel.obtain()
            newParcel.writeInt(id.size)
            newParcel.writeByteArray(id)
            newParcel.writeInt(oldTechList.size)
            newParcel.writeIntArray(oldTechList)
            newParcel.writeTypedArray(oldTechExtras, 0)
            newParcel.writeInt(serviceHandle)
            newParcel.writeInt(isMock)
            if (isMock == 0) {
                newParcel.writeStrongBinder(tagService)
            }
            newParcel.setDataPosition(0)
            val newTag = Tag.CREATOR.createFromParcel(newParcel)
            newParcel.recycle()
            return newTag

        }


        operator fun get(tag: Tag?): MCReader? {
            return if (tag != null)
                try {
                    val mcr = MCReader(tag)
                    if (!mcr.isMifareClassic())
                        null else mcr
                } catch (ex: RuntimeException) {
                    // Should not happen. However, it did happen for OnePlus5T
                    // user according to Google Play crash reports.
                    null
                } else null
        }

        /**
         * Check if key B is readable.
         * Key B is readable for the following configurations:
         *
         *  * C1 = 0, C2 = 0, C3 = 0
         *  * C1 = 0, C2 = 0, C3 = 1
         *  * C1 = 0, C2 = 1, C3 = 0
         *
         * @param ac The access conditions (4 bytes).
         * @return True if key B is readable. False otherwise.
         */
        private fun isKeyBReadable(ac: ByteArray?): Boolean {
            if (ac == null) {
                return false
            }
            val c1: Byte = (ac[1].toInt() and 0x80 ushr 7).toByte()
            val c2: Byte = (ac[2].toInt() and 0x08 ushr 3).toByte()
            val c3: Byte = (ac[2].toInt() and 0x80 ushr 7).toByte()
            return (c1.toInt() == 0 && c2.toInt() == 0 && c3.toInt() == 0 || c2.toInt() == 1 && c3.toInt() == 0
                    || c2.toInt() == 0 && c3.toInt() == 1)
        }


        /**
         * Return the sector that contains a given block.
         * (Taken from https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/nfc/tech/MifareClassic.java)
         * @param blockIndex index of block to lookup, starting from 0
         * @return sector index that contains the block
         */
        fun blockToSector(blockIndex: Int): Int {
            if (blockIndex < 0 || blockIndex >= 256) {
                throw IndexOutOfBoundsException(
                    "Block out of bounds: $blockIndex"
                )
            }
            return if (blockIndex < 32 * 4) {
                blockIndex / 4
            } else {
                32 + (blockIndex - 32 * 4) / 16
            }
        }

    }

    private var mMFC: MifareClassic? = null
    private val mKeyMap: SparseArray<ByteArray> = SparseArray()
    private val mKeyMapStatus = 0
    private val mLastSector = -1
    private val mFirstSector = 0
    private val mKeysWithOrder: ArrayList<String>? = null
    private val mHasAllZeroKey = false

    private constructor(tag: Tag) {
        this.mMFC = try {
            MifareClassic.get(tag)
        } catch (e: Exception) {
            Log.e(
                LOG_TAG, "Could not create MIFARE Classic reader for the"
                        + "provided tag (even after patching it)."
            )
            throw e
        }
    }

    fun isMifareClassic(): Boolean {
        return mMFC != null
    }

    /**
     * Check if the reader is connected to the tag.
     * This is NOT an indicator that the tag is in range.
     * @return True if the reader is connected. False otherwise.
     */
    fun isConnected(): Boolean {
        return mMFC?.isConnected ?: false
    }

    /**
     * Check if the reader is connected, but the tag is lost
     * (not in range anymore).
     * @return True if tag is lost. False otherwise.
     */
    fun isConnectedButTagLost(): Boolean {
        if (isConnected()) {
            try {
                mMFC?.readBlock(0)
            } catch (e: IOException) {
                return true
            }
        }
        return false
    }

    fun connect(){
        val error = AtomicBoolean(false)

        // Do not connect if already connected.
        if (!isConnected()) {
            // Connect in a worker thread. (connect() might be blocking).
            val t = Thread {
                try {
                    mMFC?.connect()
                } catch (ex: IOException) {
                    error.set(true)
                } catch (ex: IllegalStateException) {
                    error.set(true)
                }
            }
            t.start()

            // Wait for the connection (max 500millis).

            // Wait for the connection (max 500millis).
            try {
                t.join(500)
            } catch (ex: InterruptedException) {
                error.set(true)
            }

            // If there was an error log it and throw an exception.

            // If there was an error log it and throw an exception.
            if (error.get()) {
                Log.d(LOG_TAG, "Error while connecting to tag.")
                throw java.lang.Exception("Error while connecting to tag.")
            }
        }


    }

    fun close(){
        try {
            mMFC?.close()
        } catch (e: IOException) {
            Log.d(LOG_TAG, "Error on closing tag.")
        }
    }


}