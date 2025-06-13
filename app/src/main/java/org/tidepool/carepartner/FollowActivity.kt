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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.MutableCreationExtras
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tidepool.carepartner.backend.PersistentData.Companion.authState
import org.tidepool.carepartner.backend.PersistentData.Companion.writeToDisk
import org.tidepool.carepartner.backend.data.DataRepository
import org.tidepool.carepartner.backend.data.DataState
import org.tidepool.carepartner.backend.data.RealBackendDataSource
import org.tidepool.carepartner.ui.theme.LoopFollowTheme
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class FollowActivity : ComponentActivity() {
    companion object {
        const val TAG = "FollowActivity"
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                delay(30.seconds)
                while (lifecycleScope.isActive) {
                    applicationContext.writeToDisk()
                    delay(5.minutes)
                }
            }
        }

        enableEdgeToEdge()
        val viewModelStoreOwner: ViewModelStoreOwner = this
        val dataState: DataState = ViewModelProvider.create(
            viewModelStoreOwner,
            factory = DataState.Factory,
            extras = MutableCreationExtras().apply {
                set(DataState.DATA_REPOSITORY_KEY, DataRepository(RealBackendDataSource(authState)))
            },
        )[DataState::class]
        setContent {
            LoopFollowTheme {
                ui.App(modifier = Modifier.fillMaxSize(), backPressed = backPressed, dataState = dataState)
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
