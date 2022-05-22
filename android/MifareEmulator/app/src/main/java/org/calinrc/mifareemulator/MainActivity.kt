package org.calinrc.mifareemulator

import android.nfc.NfcAdapter
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.provider.Settings

class MainActivity : AppCompatActivity() {
    private enum class AppEvent {
        HasNfc, HasMifareClassicSupport, HasNfcEnabled, HandleNewIntent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onResume() {
        super.onResume()
        triggerEvent(AppEvent.HasNfc)
    }

    private fun triggerEvent(event: AppEvent) {
        when (event) {
            AppEvent.HasNfc -> {
                MifareEmulatorApp.setNfcAdaptor(NfcAdapter.getDefaultAdapter(this))
                if (MifareEmulatorApp.getNfcAdaptor() != null)
                    triggerEvent(AppEvent.HasMifareClassicSupport)
            }
            AppEvent.HasMifareClassicSupport ->
                if (MifareEmulatorApp.hasMifareClassicSupport()) {
                    triggerEvent(AppEvent.HasNfcEnabled)
                }
            AppEvent.HasNfcEnabled -> {
                MifareEmulatorApp.setNfcAdaptor(NfcAdapter.getDefaultAdapter(this))
                if (MifareEmulatorApp.getNfcAdaptor()?.isEnabled != true) {
                    createNfcEnableDialog()
                } else {
                    MifareEmulatorApp.enableNfcForegroundDispatch(this);
                }
            }


            AppEvent.HandleNewIntent -> print("HandleNewIntent") //TBD
        }
    }

    private fun createNfcEnableDialog(): AlertDialog {
        return AlertDialog.Builder(this)
            .setTitle(R.string.dialog_nfc_not_enabled_title)
            .setMessage(R.string.dialog_nfc_not_enabled)
            .setIcon(android.R.drawable.ic_dialog_info)
            .setPositiveButton(R.string.action_nfc
            ) { _, _ ->
                {
                    // Goto NFC Settings.
                    startActivity(
                        Intent(
                            Settings.ACTION_NFC_SETTINGS
                        )
                    )
                }
            }
            .setNegativeButton(R.string.action_exit_app
            ) { _, _ ->
                {
                    // Exit the App.
                    finish()
                }
            }
            .setCancelable(false)
            .create()
    }
}