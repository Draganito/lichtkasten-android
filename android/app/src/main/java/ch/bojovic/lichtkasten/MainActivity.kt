package ch.bojovic.lichtkasten

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.bojovic.lichtkasten.ui.App
import ch.bojovic.lichtkasten.ui.LichtkastenTheme

class MainActivity : ComponentActivity() {

    private val vm: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LichtkastenTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                App(
                    state = state,
                    vm = vm,
                    mediaPermissions = mediaPermissions(),
                    onPermissionsResult = { vm.onAccess(checkAccess()) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed the permission in the system settings.
        vm.onAccess(checkAccess())
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun checkAccess(): MediaAccess = when {
        granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.Full
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.Partial
        else -> MediaAccess.Denied
    }

    private fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
        } else {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        }
}
