package org.calinrc.mifareemulator

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Build
import android.util.Log
import java.io.File

class MifareEmulatorApp : Application() {
    companion object {
        val LOG_TAG: String = MifareEmulatorApp.javaClass.simpleName
        val HOME_DIR = "/MifareEmulator"
        val TMP_DIR = "tmp"
        val KEYS_DIR = "key-files"
        val DUMPS_DIR = "dump-files"
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
                } catch (ex: IllegalStateException) {
                    Log.d(
                        LOG_TAG,
                        "Error: Could not enable the NFC foreground" +
                                "dispatch system. The activity was not in foreground."
                    )
                }
            }
        }

        enum class Operation {
            Read, Write, Increment, DecTransRest, ReadKeyA, ReadKeyB, ReadAC,
            WriteKeyA, WriteKeyB, WriteAC
        }

    }

    /**
     * Possible operations the on a MIFARE Classic Tag.
     */
    override fun onCreate() {
        super.onCreate()
        MifareEmulatorApp.mAppContext = applicationContext
    }
}