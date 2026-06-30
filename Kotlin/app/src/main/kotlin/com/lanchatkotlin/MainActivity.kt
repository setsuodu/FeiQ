package com.lanchatkotlin

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.lanchatkotlin.ui.screens.MainScreen
import com.lanchatkotlin.ui.theme.LanChatTheme
import com.lanchatkotlin.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 权限回调 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 申请权限
        permLauncher.launch(arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ))

        setContent {
            LanChatTheme {
                MainScreen(vm = vm)
            }
        }
    }
}
