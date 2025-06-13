package org.tidepool.carepartner

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import org.tidepool.carepartner.ui.theme.LoopFollowTheme
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

class FollowActivity : ComponentActivity() {
    companion object {
        
        const val TAG = "FollowActivity"
        val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    }
    
    private lateinit var ui: FollowUI
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = FollowUI().apply { lifecycle.addObserver(this) }
        @SuppressLint("SourceLockedOrientationActivity")
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val backPressed = mutableStateOf(false)
        onBackPressedDispatcher.addCallback(this) {
            backPressed.value = true
        }
        enableEdgeToEdge()
        setContent {
            LoopFollowTheme {
                ui.App(modifier = Modifier.fillMaxSize(), backPressed = backPressed)
            }
        }
    }
}

fun Instant.until(other: Instant): Duration {
    return until(other, ChronoUnit.NANOS).nanoseconds
}

operator fun Instant.plus(duration: Duration): Instant {
    return plusNanos(duration.inWholeNanoseconds)
}

operator fun Instant.minus(duration: Duration): Instant {
    return minusNanos(duration.inWholeNanoseconds)
}
