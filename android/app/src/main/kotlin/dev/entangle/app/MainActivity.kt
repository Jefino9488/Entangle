package dev.entangle.app

import android.content.ComponentCallbacks2
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import dev.entangle.app.core.EntangleViewModel
import dev.entangle.app.ui.EntangleApp

class MainActivity : ComponentActivity() {
    private val viewModel: EntangleViewModel by viewModels()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EntangleApp(viewModel, calculateWindowSizeClass(this).widthSizeClass) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumePreview()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) viewModel.releasePreview()
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            viewModel.handleMemoryPressure()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        viewModel.handleMemoryPressure()
    }
}
