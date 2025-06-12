package org.tidepool.carepartner

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.MutableCreationExtras
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import org.tidepool.carepartner.backend.PersistentData.Companion.authState
import org.tidepool.carepartner.backend.data.DataRepository
import org.tidepool.carepartner.backend.data.DataState
import org.tidepool.carepartner.backend.data.RealBackendDataSource
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
        val resp = AuthorizationResponse.fromIntent(intent)
        val ex = AuthorizationException.fromIntent(intent)
        @SuppressLint("SourceLockedOrientationActivity")
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (resp != null) {
            val authService = AuthorizationService(this)
            authService.performTokenRequest(
                resp.createTokenExchangeRequest()
            ) { newResp, newEx ->
                authState.update(newResp, newEx)
            }
        }
        val backPressed = mutableStateOf(false)
        onBackPressedDispatcher.addCallback(this) {
            backPressed.value = true
        }
        if ((resp == null).xor(ex == null)) {
            authState.update(resp, ex)
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
