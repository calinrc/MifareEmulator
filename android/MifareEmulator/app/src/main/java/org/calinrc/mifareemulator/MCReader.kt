package org.calinrc.mifareemulator

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.util.SparseArray
import java.io.IOException
import java.util.*
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

        public enum class ContentElement {
            BytesData, EmptyData, KeyData, NoKeyA, NoKeyB
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

    fun connect() {
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

    fun close() {
        try {
            mMFC?.close()
        } catch (e: IOException) {
            Log.d(LOG_TAG, "Error on closing tag.")
        }
    }


    // TODO: Make this a function with three return values.
    // 0 = Auth. successful.
    // 1 = Auth. not successful.
    // 2 = Error. Most likely tag lost.
    // Once done, update the code of buildNextKeyMapPart().
    /**
     * Authenticate with given sector of the tag.
     * @param sectorIndex The sector with which to authenticate.
     * @param key Key for the authentication.
     * @param useAsKeyB If true, key will be treated as key B
     * for authentication.
     * @return True if authentication was successful. False otherwise.
     */
    private fun authenticate(sectorIndex: Int, key: ByteArray?, useAsKeyB: Boolean): Boolean {

        return if (key == null) {
            false
        } else {
            var ret = false
            try {
                ret = if (!useAsKeyB) {
                    // Key A.
                    mMFC!!.authenticateSectorWithKeyA(sectorIndex, key)
                } else {
                    // Key B.
                    mMFC!!.authenticateSectorWithKeyB(sectorIndex, key)
                }
            } catch (e: IOException) {
                Log.d(LOG_TAG, "Error authenticating with tag.")
            } catch (e: ArrayIndexOutOfBoundsException) {
                Log.d(LOG_TAG, "Error authenticating with tag.")
            }
            ret
        }
    }

    /**
     * Merge the result of two {@link #readSector(int, byte[], boolean)}
     * calls on the same sector (with different keys or authentication methods).
     * In this case merging means empty blocks will be overwritten with non
     * empty ones and the keys will be added correctly to the sector trailer.
     * The access conditions will be taken from the first (firstResult)
     * parameter if it is not null.
     * @param firstResult First
     * {@link #readSector(int, byte[], boolean)} result.
     * @param secondResult Second
     * {@link #readSector(int, byte[], boolean)} result.
     * @return Array (sector) as result of merging the given
     * sectors. If a block is {@link #NO_DATA} it
     * means that none of the given sectors contained data from this block.
     * @see #readSector(int, byte[], boolean)
     * @see #authenticate(int, byte[], boolean)
     */
    private fun mergeSectorData(
        firstResult: Array<Pair<ContentElement, ByteArray?>>?,
        secondResult: Array<Pair<ContentElement, ByteArray?>>?
    ): Array<Pair<ContentElement, ByteArray?>>? {
        return if (firstResult != null || secondResult != null) {
            if (firstResult != null && secondResult != null
                && firstResult.size != secondResult.size
            ) {
                null
            } else {
                val length = firstResult?.size ?: secondResult!!.size
                val blocks = ArrayList<Pair<ContentElement, ByteArray?>>()
                for (i in 0 until length - 1) {
                    if (firstResult != null && firstResult[i] != null && firstResult[i].first != ContentElement.EmptyData
                    ) {
                        blocks.add(firstResult[i])
                    }else if (secondResult != null && secondResult[i] != null && secondResult[i].first != ContentElement.EmptyData){
                        blocks.add(secondResult[i])
                    }else{
                        blocks.add(Pair(ContentElement.EmptyData, null))
                    }
                }
                blocks.toTypedArray()
            }
        } else
            null

    }

    /**
     * Read as much as possible from a sector with the given key.
     * Best results are gained from a valid key B (except key B is marked as
     * readable in the access conditions).
     * @param sectorIndex Index of the Sector to read. (For MIFARE Classic 1K:
     * 0-63)
     * @param key Key for authentication.
     * @param useAsKeyB If true, key will be treated as key B
     * for authentication.
     * @return Array of blocks (index 0-3 or 0-15). If a block or a key is
     * marked with {@link #NO_DATA} or {@link #NO_KEY}
     * it means that this data could not be read or found. On authentication error
     * "null" will be returned.
     * @throws TagLostException When connection with/to tag is lost.
     * @see #mergeSectorData(String[], String[])
     */
    fun readSector(
        sectorIndex: Int,
        key: ByteArray,
        useAsKeyB: Boolean
    ): Array<Pair<ContentElement, ByteArray?>>? {
        val auth = authenticate(sectorIndex, key, useAsKeyB)
        return if (auth) {
            // Read all blocks.

            // Read all blocks.
            val blocks: MutableList<Pair<ContentElement, ByteArray?>> =
                mutableListOf<Pair<ContentElement, ByteArray?>>()
            val firstBlock = mMFC!!.sectorToBlock(sectorIndex)
            var lastBlock = firstBlock + 4
            if (mMFC!!.size == MifareClassic.SIZE_4K
                && sectorIndex > 31
            ) {
                lastBlock = firstBlock + 16
            }
            for (i in firstBlock until lastBlock) {
                try {
                    var blockBytes = mMFC!!.readBlock(i)
                    // mMFC.readBlock(i) must return 16 bytes or throw an error.
                    // At least this is what the documentation says.
                    // On Samsung's Galaxy S5 and Sony's Xperia Z2 however, it
                    // sometimes returns < 16 bytes for unknown reasons.
                    // Update: Aaand sometimes it returns more than 16 bytes...
                    // The appended byte(s) are 0x00.
                    if (blockBytes.size < 16) {
                        throw IOException()
                    }
                    if (blockBytes.size > 16) {
                        blockBytes = Arrays.copyOf(blockBytes, 16)
                    }
                    blocks.add(Pair(ContentElement.BytesData, blockBytes))
                } catch (e: TagLostException) {
                    throw e
                } catch (e: IOException) {
                    // Could not read block.
                    // (Maybe due to key/authentication method.)
                    Log.d(
                        LOG_TAG, "(Recoverable) Error while reading block "
                                + i + " from tag."
                    )
                    blocks.add(Pair(ContentElement.EmptyData, null))
                    if (!mMFC!!.isConnected) {
                        throw TagLostException(
                            "Tag removed during readSector(...)"
                        )
                    }
                    // After an error, a re-authentication is needed.
                    authenticate(sectorIndex, key, useAsKeyB)
                }
            }
            var ret = blocks.toTypedArray()
            val last = ret.size - 1

            // Validate if it was possible to read any data.
            var noData = true
            for (s in ret) {
                if (s.first != ContentElement.EmptyData) {
                    noData = false
                    break
                }
            }
            if (noData) {
                // Was is possible to read any data (especially with key B)?
                // If Key B may be read in the corresponding Sector Trailer,
                // it cannot serve for authentication (according to NXP).
                // What they mean is that you can authenticate successfully,
                // but can not read data. In this case the
                // readBlock() result is 0 for each block.
                // Also, a tag might be bricked in a way that the authentication
                // works, but reading data does not.
                return null
            } else {
                // Merge key in last block (sector trailer).

                // Merge key in last block (sector trailer).
                if (!useAsKeyB) {
                    if (ret[last].first != ContentElement.EmptyData && isKeyBReadable(
                            ret[last].second!!.copyOfRange(12, 20)
                        )
                    ) {
                        ret[last] = Pair(
                            ContentElement.KeyData,
                            key + ret[last].second!!.copyOfRange(12, 32)
                        )
                    } else {
                        ret[last] = Pair(ContentElement.NoKeyB, key)
                    }
                } else {
                    ret[last] =
                        Pair(ContentElement.NoKeyA, ret[last].second!!.copyOfRange(12, 20) + key)
                }
            }
            ret
        } else
            null
    }


    /**
     * Read as much as possible from the tag with the given key information.
     * @param keyMap Keys (A and B) mapped to a sector.
     * See {@link #buildNextKeyMapPart()}.
     * @return A Key-Value Pair. Keys are the sector numbers, values
     * are the tag data. This tag data (values) are arrays containing
     * one block per field (index 0-3 or 0-15).
     * If a block is "null" it means that the block couldn't be
     * read with the given key information.<br />
     * On Error, "null" will be returned (tag was removed during reading or
     * keyMap is null). If none of the keys in the key map are valid for reading
     * (and therefore no sector is read), an empty set (SparseArray.size() == 0)
     * will be returned.
     * @see #buildNextKeyMapPart()
     */

    fun readAsMuchAsPossible(keyMap: SparseArray<Array<ByteArray>>?): SparseArray<Array<Pair<ContentElement, ByteArray?>>>? {
        return if (keyMap != null && keyMap?.size() > 0) {
            var resultSparseArray: SparseArray<Array<Pair<ContentElement, ByteArray?>>> =
                SparseArray<Array<Pair<ContentElement, ByteArray?>>>(keyMap?.size())
            for (i in 0..keyMap.size()) {
                val results: Array<Array<Pair<ContentElement, ByteArray?>>?> = arrayOfNulls(2)
                try {
                    if (keyMap.valueAt(i)[0] != null) {
                        // Read with key A.
                        results[0] = readSector(
                            keyMap.keyAt(i), keyMap.valueAt(i)[0], false
                        )
                    }
                    if (keyMap.valueAt(i)[1] != null) {
                        // Read with key B.
                        results[1] = readSector(
                            keyMap.keyAt(i), keyMap.valueAt(i)[1], true
                        )
                    }
                } catch (e: TagLostException) {
                    return null
                }

                // Merge results.
                if (results[0] != null || results[1] != null) {
                    resultSparseArray.put(
                        keyMap.keyAt(i), mergeSectorData(
                            results[0], results[1]
                        )
                    )
                }
            }
            resultSparseArray
        } else
            null
    }


}