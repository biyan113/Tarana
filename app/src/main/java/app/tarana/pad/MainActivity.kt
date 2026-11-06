package app.tarana.pad

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.tarana.pad.player.PlayerController
import app.tarana.pad.ui.TaranaRoot
import app.tarana.pad.ui.TaranaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PlayerController.connect(applicationContext)
        // Android 13+ 媒体通知需要通知权限
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { TaranaTheme { TaranaRoot() } }
    }
}
