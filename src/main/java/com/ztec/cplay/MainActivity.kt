package com.ztec.cplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ztec.cplay.ui.theme.NexusCpTheme

/**
 * Placeholder diagnostic activity. The main entry point is NexusCpActivity.
 * Uses bundled offline MFi authentication - no external hardware diagnostics needed.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexusCpTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Column(
                        modifier = Modifier.padding(padding).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.app_name))
                        Text(stringResource(R.string.using_offline_mfi))
                        Text(stringResource(R.string.main_entry_point))
                    }
                }
            }
        }
    }
}
