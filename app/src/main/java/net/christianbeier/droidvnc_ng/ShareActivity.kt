package net.christianbeier.droidvnc_ng

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if(!MainService.isServerActive()) {
                Toast.makeText(this, R.string.share_activtiy_server_not_running, Toast.LENGTH_LONG).show()
            } else if (MainService.getClientCount() == 0) {
                Toast.makeText(this, R.string.share_activtiy_no_clients_connected, Toast.LENGTH_LONG).show()
            } else {
                MainService.vncSendCutText(sharedText)
                Toast.makeText(this, R.string.share_activtiy_shared_successfully, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

}