package com.example.lyretuner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.example.lyretuner.presentation.TunerViewModel
import com.example.lyretuner.ui.tuner.TunerScreen
import com.example.lyretuner.ui.theme.LyreTunerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: TunerViewModel by viewModels()

    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onMicrophonePermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsState()

            LyreTunerTheme(dynamicColor = false) {
                TunerScreen(
                    state = uiState,
                    onListeningClick = ::handleListeningClick,
                    onStringClick = viewModel::toggleStringSelection,
                    onAutoClick = viewModel::useAutomaticSelection,
                )
            }
        }
    }

    private fun handleListeningClick() {
        if (viewModel.uiState.value.isListening) {
            viewModel.stopListening()
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        viewModel.startListening(hasPermission)
        if (!hasPermission) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.enterForeground()
    }

    override fun onStop() {
        viewModel.leaveForeground()
        super.onStop()
    }
}
