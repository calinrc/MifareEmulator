package org.calinrc.mifareemulator

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import java.io.File
import java.math.BigInteger
import java.util.*

class MifareEmulatorApp : Application() {
    companion object {
        private val LOG_TAG: String = MifareEmulatorApp::class.java.simpleName

        //        val HOME_DIR = "/MifareEmulator"
//        val TMP_DIR = "tmp"
//        val KEYS_DIR = "key-files"
//        val DUMPS_DIR = "dump-files"
        var mTag: Tag? = null
        var mUID: ByteArray? = null
        var mHasMifareClassicSupport = 0
        var mNfcAdapter: NfcAdapter? = null
        var mAppContext: Context? = null
        fun getTag(): Tag? {
            return mTag
        }

        fun getUID(): ByteArray? {
            return mUID
        }

        fun setTag(tag: Tag) {
            mTag = tag
            mUID = tag.id
        }

        fun getNfcAdaptor(): NfcAdapter? {
            return mNfcAdapter
        }

        fun setNfcAdaptor(nfcAdaptor: NfcAdapter) {
            mNfcAdapter = nfcAdaptor
        }

        /**
         * Check if the device supports the MIFARE Classic technology.
         * In order to do so, there is a first check ensure the device actually has
         * a NFC hardware (if not, [.mUseAsEditorOnly] is set to true).
         * After this, this function will check if there are files
         * like "/dev/bcm2079x-i2c" or "/system/lib/libnfc-bcrm*". Files like
         * these are indicators for a NFC controller manufactured by Broadcom.
         * Broadcom chips don't support MIFARE Classic.
         * @return True if the device supports MIFARE Classic. False otherwise.
         * @see .mHasMifareClassicSupport
         *
         * @see .mUseAsEditorOnly
         */
        fun hasMifareClassicSupport(): Boolean {
            if (mHasMifareClassicSupport != 0) {
                return mHasMifareClassicSupport == 1
            }

            // Check for the MifareClassic class.
            // It is most likely there on all NFC enabled phones.
            // Therefore this check is not needed.
            /*
        try {
            Class.forName("android.nfc.tech.MifareClassic");
        } catch( ClassNotFoundException e ) {
            // Class not found. Devices does not support MIFARE Classic.
            return false;
        }
        */

            // Check if there is any NFC hardware at all.

            if (NfcAdapter.getDefaultAdapter(mAppContext!!) == null) {
                mHasMifareClassicSupport = -1
                return false
            }

            // Check if there is the NFC device "bcm2079x-i2c".
            // Chips by Broadcom don't support MIFARE Classic.
            // This could fail because on a lot of devices apps don't have
            // the sufficient permissions.
            // Another exception:
            // The Lenovo P2 has a device at "/dev/bcm2079x-i2c" but is still
            // able of reading/writing MIFARE Classic tags. I don't know why...
            // https://github.com/ikarus23/MifareClassicTool/issues/152
            val isLenovoP2 = Build.MANUFACTURER == "LENOVO" && Build.MODEL == "Lenovo P2a42"
            var device = File("/dev/bcm2079x-i2c")
            if (!isLenovoP2 && device.exists()) {
                mHasMifareClassicSupport = -1
                return false
            }

            // Check if there is the NFC device "pn544".
            // The PN544 NFC chip is manufactured by NXP.
            // Chips by NXP support MIFARE Classic.
            device = File("/dev/pn544")
            if (device.exists()) {
                mHasMifareClassicSupport = 1
                return true
            }

            // Check if there are NFC libs with "brcm" in their names.
            // "brcm" libs are for devices with Broadcom chips. Broadcom chips
            // don't support MIFARE Classic.
            val libsFolder = File("/system/lib")
            val libs = libsFolder.listFiles()
            for (lib in libs) {
                if (lib.isFile
                    && lib.name.startsWith("libnfc")
                    && lib.name.contains("brcm") // Add here other non NXP NFC libraries.
                ) {
                    mHasMifareClassicSupport = -1
                    return false
                }
            }
            mHasMifareClassicSupport = 1
            return true
        }

        /**
         * Enables the NFC foreground dispatch system for the given Activity.
         * @param targetActivity The Activity that is in foreground and wants to
         * have NFC Intents.
         * @see #disableNfcForegroundDispatch(Activity)
         */
        fun enableNfcForegroundDispatch(targetActivity: Activity) {
            if (mNfcAdapter?.isEnabled == true) {
                val intent = Intent(
                    targetActivity, targetActivity.javaClass
                ).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pendingIntent = PendingIntent.getActivity(
                    targetActivity, 0, intent, PendingIntent.FLAG_MUTABLE
                )
                try {
                    mNfcAdapter?.enableForegroundDispatch(
                        targetActivity, pendingIntent, null, arrayOf(
                            arrayOf(
                                NfcA::class.java.name
                            )
                        )
                    )
                } catch (ex: Exception) {
                    Log.d(
                        LOG_TAG,
                        "Error: Could not enable the NFC foreground" +
                                "dispatch system. The activity was not in foreground."
                    )
                }
            }
        }

        /**
         * Disable the NFC foreground dispatch system for the given Activity.
         * @param targetActivity An Activity that is in foreground and has
         * NFC foreground dispatch system enabled.
         * @see #enableNfcForegroundDispatch(Activity)
         */
        fun disableNfcForegroundDispatch(targetActivity: Activity) {
            if (mNfcAdapter?.isEnabled == true) {
                try {
                    mNfcAdapter?.disableForegroundDispatch(targetActivity)
                } catch (ex: IllegalStateException) {
                    Log.d(
                        LOG_TAG,
                        "Error: Could not disable the NFC foreground" +
                                "dispatch system. The activity was not in foreground."
                    )
                }
            }
        }

        fun getPreferences(): SharedPreferences {
            return PreferenceManager.getDefaultSharedPreferences(mAppContext!!)
        }


        /**
         * Check if the tag and the device support the MIFARE Classic technology.
         * @param tag The tag to check.
         * @param context The context of the package manager.
         * @return
         *
         *  * 0 - Device and tag support MIFARE Classic.
         *  * -1 - Device does not support MIFARE Classic.
         *  * -2 - Tag does not support MIFARE Classic.
         *  * -3 - Error (tag or context is null).
         *
         */
        fun checkMifareClassicSupport(tag: Tag?, context: Context?): Int {
            if (tag == null || context == null) {
                // Error.
                return -3
            }

            return if (tag.techList.asList().contains(MifareClassic::class.java.name)) {
                // Device and tag should support MIFARE Classic.
                // But is there something wrong with the tag?
                try {
                    MifareClassic.get(tag)
                } catch (ex: RuntimeException) {
                    // Stack incorrectly reported a MifareClassic.
                    // Most likely not a MIFARE Classic tag.
                    // See: https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/nfc/tech/MifareClassic.java#196
                    return -2
                }
                0

                // This is no longer valid. There are some devices (e.g. LG's F60)
                // that have this system feature but no MIFARE Classic support.
                // (The F60 has a Broadcom NFC controller.)
                /*
             } else if (context.getPackageManager().hasSystemFeature(
                     "com.nxp.mifare")){
                 // Tag does not support MIFARE Classic.
                 return -2;
             */
            } else {
                // Check if device does not support MIFARE Classic.
                // For doing so, check if the SAK of the tag indicate that
                // it's a MIFARE Classic tag.
                // See: https://www.nxp.com/docs/en/application-note/AN10833.pdf (page 6)
                val nfca = NfcA.get(tag)
                val sak = nfca.sak.toByte().toInt()
                if (sak shr 1 and 1 == 1) {
                    // RFU.
                    -2
                } else {
                    if (sak shr 3 and 1 == 1) { // SAK bit 4 = 1?
                        // Note: Other SAK bits are irrelevant. Tag is MIFARE Classic compatible.
                        // MIFARE Mini
                        // MIFARE Classic 1K/2K/4K
                        // MIFARE SmartMX 1K/4K
                        // MIFARE Plus S 2K/4K SL1
                        // MIFARE Plus X 2K/4K SL1
                        // MIFARE Plus SE 1K
                        // MIFARE Plus EV1 2K/4K SL1
                        -1
                    } else { // SAK bit 4 = 0
                        // Note: Other SAK bits are irrelevant. Tag is *not* MIFARE Classic compatible.
                        // Tags like MIFARE Plus in SL2, MIFARE Ultralight, MIFARE DESFire, etc.
                        -2
                    }
                }

                // Old MIFARE Classic support check. No longer valid.
                // Check if the ATQA + SAK of the tag indicate that it's a MIFARE Classic tag.
                // See: http://www.nxp.com/documents/application_note/AN10833.pdf
                // (Table 5 and 6)
                // 0x28 is for some emulated tags.
                /*
                 NfcA nfca = NfcA.get(tag);
                 byte[] atqa = nfca.getAtqa();
                 if (atqa[1] == 0 &&
                         (atqa[0] == 4 || atqa[0] == (byte)0x44 ||
                          atqa[0] == 2 || atqa[0] == (byte)0x42)) {
                     // ATQA says it is most likely a MIFARE Classic tag.
                     byte sak = (byte)nfca.getSak();
                     if (sak == 8 || sak == 9 || sak == (byte)0x18 ||
                                                 sak == (byte)0x88 ||
                                                 sak == (byte)0x28) {
                         // SAK says it is most likely a MIFARE Classic tag.
                         // --> Device does not support MIFARE Classic.
                         return -1;
                     }
                 }
                 // Nope, it's not the device (most likely).
                 // The tag does not support MIFARE Classic.
                 return -2;
                 */
            }
        }


        /**
         * Reverse a byte Array (e.g. Little Endian -> Big Endian).
         * Hmpf! Java has no Array.reverse(). And I don't want to use
         * Commons.Lang (ArrayUtils) from Apache....
         * @param array The array to reverse (in-place).
         */
        fun reverseByteArrayInPlace(array: ByteArray) {
            for (i in 0 until array.size / 2) {
                val temp = array[i]
                array[i] = array[array.size - i - 1]
                array[array.size - i - 1] = temp
            }
        }

        fun byte2FmtString(bytes: ByteArray, fmt: Int): String? {
            when (fmt) {
                2 -> {
                    val revBytes = bytes.clone()
                    reverseByteArrayInPlace(revBytes)
                    return hex2Dec(bytes2Hex(revBytes))
                }
                1 -> return hex2Dec(bytes2Hex(bytes))
            }
            return bytes2Hex(bytes)
        }

        /**
         * Convert a hexadecimal string to a decimal string.
         * Uses BigInteger only if the hexadecimal string is longer than 7 bytes.
         * @param hex The hexadecimal value to convert.
         * @return String representation of the decimal value of hexString.
         */
        fun hex2Dec(hex: String): String? {
            if (!(hex.length % 2 == 0 && hex.matches(Regex("[0-9A-Fa-f]+")))) {
                return null
            }
            val ret = if (hex.isEmpty()) {
                "0"
            } else if (hex.length <= 14) {
                java.lang.Long.toString(hex.toLong(16))
            } else {
                val bigInteger = BigInteger(hex, 16)
                bigInteger.toString()
            }
            return ret
        }

        /**
         * Convert an array of bytes into a string of hex values.
         * @param bytes Bytes to convert.
         * @return The bytes in hex string format.
         */
        fun bytes2Hex(bytes: ByteArray?): String {
            val ret = StringBuilder()
            if (bytes != null) {
                for (b in bytes) {
                    ret.append(String.format("%02X", b.toInt() and 0xFF))
                }
            }
            return ret.toString()
        }

        /**
         * Convert a string of hex data into a byte array.
         * Original author is: Dave L. (http://stackoverflow.com/a/140861).
         * @param hex The hex string to convert
         * @return An array of bytes with the values of the string.
         */
        fun hex2Bytes(hex: String?): ByteArray? {
            if (!(hex != null && hex.length % 2 == 0 && hex.matches(Regex("[0-9A-Fa-f]+")))) {
                return null
            }
            val len = hex.length
            val data = ByteArray(len / 2)
            try {
                var i = 0
                while (i < len) {
                    data[i / 2] = ((hex[i].digitToIntOrNull(16) ?: -1 shl 4)
                    + hex[i + 1].digitToIntOrNull(16)!! ?: -1).toByte()
                    i += 2
                }
            } catch (e: java.lang.Exception) {
                Log.d(
                    LOG_TAG, "Argument(s) for hexStringToByteArray(String s)"
                            + "was not a hex string"
                )
            }
            return data
        }

        fun treatAsNewTag(intent: Intent, context: Context): Int {
            if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
                var tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                tag = MCReader.patchTag(tag)
                if (tag == null) {
                    return -3
                }
                setTag(tag)

                // Show Toast message with UID.
                var id = context.resources.getString(
                    R.string.info_new_tag_found
                ) + " (UID: "
                id += bytes2Hex(tag.id)
                id += ")"
                Toast.makeText(context, id, Toast.LENGTH_LONG).show()
                return checkMifareClassicSupport(tag, context)

            }
            return -4
        }

        enum class Operation {
            Read, Write, Increment, DecTransRest, ReadKeyA, ReadKeyB, ReadAC,
            WriteKeyA, WriteKeyB, WriteAC
        }

        /**
         * Create a connected [MCReader] if there is a present MIFARE Classic
         * tag. If there is no MIFARE Classic tag an error
         * message will be displayed to the user.
         * @param context The Context in which the error Toast will be shown.
         * @return A connected [MCReader] or "null" if no tag was present.
         */
        fun checkForTagAndCreateReader(context: Context?): MCReader? {
            var tagLost = false
            // Check for tag.
            val reader = MCReader[mTag]
            if (reader != null) {

                try {
                    reader.connect()
                } catch (e: Exception) {
                    tagLost = true
                }
                if (!tagLost && !reader.isConnected()) {
                    reader.close()
                    tagLost = true
                }
                if (!tagLost) {
                    return reader
                }
            }

            // Error. The tag is gone.
            Toast.makeText(context, R.string.info_no_tag_found, Toast.LENGTH_LONG).show()
            return null
        }
    }

        /**
         * Possible operations the on a MIFARE Classic Tag.
         */
        override fun onCreate() {
            super.onCreate()
            mAppContext = applicationContext
        }
    }