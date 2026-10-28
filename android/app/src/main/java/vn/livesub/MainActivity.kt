package vn.livesub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import vn.livesub.ui.LiveSubTheme
import vn.livesub.ui.MainScreen
import vn.livesub.ui.MainViewModel
import vn.livesub.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiveSubTheme {
                val vm: MainViewModel = viewModel()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(vm, onBack = { showSettings = false })
                } else {
                    MainScreen(vm, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
