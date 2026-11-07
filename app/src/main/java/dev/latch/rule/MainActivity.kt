package dev.latch.rule

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.latch.rule.ui.LatchApp
import dev.latch.rule.ui.LatchViewModel
import dev.latch.rule.ui.theme.LatchTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val viewModel: LatchViewModel by viewModels {
        LatchViewModel.factory((application as LatchApplication).container)
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        viewModel.onPermissionResult(grantResult == PackageManager.PERMISSION_GRANTED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        setContent {
            LatchTheme {
                LatchApp(
                    viewModel = viewModel,
                    onRequestPermission = ::requestShizukuPermission,
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        super.onDestroy()
    }

    private fun requestShizukuPermission() {
        runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE) }
    }

    private companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }
}
