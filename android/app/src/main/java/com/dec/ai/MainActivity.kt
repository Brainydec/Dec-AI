package com.dec.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dec.initialize(this)
        setContent { DecApp() }
    }

    override fun onResume() {
        super.onResume()
        Dec.resumeTick.value += 1
        if (Dec.state.value == DecState.OFF) Dec.online.value = Dec.isOnline(this)
    }
}
