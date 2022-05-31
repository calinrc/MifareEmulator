package org.calinrc.mifareemulator

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

class MCReader {
    companion object {
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
                }else if(techItem.equals(MifareClassic::class.java.name)){
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
    }
}