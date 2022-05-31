package org.calinrc.mifareemulator

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    companion object{
        val LOG_TAG:String = MainActivity::class.java.simpleName
    }



    private enum class AppEvent {
        HasNfc, HasMifareClassicSupport, HasNfcEnabled, HandleNewIntent
    }

    var mOldIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mOldIntent = savedInstanceState?.getParcelable("old_intent")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable("old_intent", mOldIntent)
    }

    override fun onResume() {
        super.onResume()
        triggerEvent(AppEvent.HasNfc)
    }

    override fun onPause() {
        super.onPause()
        MifareEmulatorApp.disableNfcForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null){
            val typeCheck: Int = MifareEmulatorApp.treatAsNewTag(intent, this)
            Log.d(LOG_TAG, "typeCheck:$typeCheck")
        }

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
                    MifareEmulatorApp.enableNfcForegroundDispatch(this)
                    triggerEvent(AppEvent.HandleNewIntent)
                }
            }


            AppEvent.HandleNewIntent -> {
                if (intent != null) {
                    val isIntentWithTag = intent.action ==
                            NfcAdapter.ACTION_TECH_DISCOVERED
                    if (isIntentWithTag && intent !== mOldIntent) {
                        // If MCT was called by another app or the dispatch
                        // system with a tag delivered by intent, handle it as
                        // new tag intent.
                        mOldIntent = intent
                        onNewIntent(intent)
                    }
                }
            }
        }
    }

    private fun createNfcEnableDialog(): AlertDialog {
        return AlertDialog.Builder(this)
            .setTitle(R.string.dialog_nfc_not_enabled_title)
            .setMessage(R.string.dialog_nfc_not_enabled)
            .setIcon(android.R.drawable.ic_dialog_info)
            .setPositiveButton(R.string.action_nfc
            ) { _, _ ->
                run {
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
                run {
                    // Exit the App.
                    finish()
                }
            }
            .setCancelable(false)
            .create()
    }
}