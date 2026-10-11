package com.pranjalbudaniya.teradown

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pranjalbudaniya.teradown.ui.navigation.TeledownMainScreen
import com.pranjalbudaniya.teradown.ui.theme.TeledownTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TeledownTheme {
                TeledownMainScreen()
            }
        }
    }
}
