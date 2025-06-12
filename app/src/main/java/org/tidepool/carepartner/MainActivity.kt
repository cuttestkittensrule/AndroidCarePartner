package org.tidepool.carepartner

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationService
import org.tidepool.carepartner.backend.PersistentData
import org.tidepool.carepartner.backend.PersistentData.Companion.NoAuthorizationException
import org.tidepool.carepartner.backend.PersistentData.Companion.getAccessToken
import org.tidepool.carepartner.backend.PersistentData.Companion.readFromDisk
import kotlin.time.Duration.Companion.seconds

private const val REAUTH_SERVICE_JOB_ID = 1

private var TAG = MainActivity::class.java.simpleName

class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @SuppressLint("SourceLockedOrientationActivity")
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        numRetries = 0
        setContent {
            HomeUI()
            LaunchedEffect(true) {
                baseContext.readFromDisk()
                try {
                    withTimeout(15.seconds) {
                        getAccessToken()
                    }
                } catch (_: TimeoutCancellationException) {
                    // don't care if it times out
                } catch (_: AuthorizationException) {
                    // don't care if access token get fails
                } catch (_: NoAuthorizationException) {
                    // don't care if it can't perform the authorization
                }
                
                ReauthService.start(baseContext, REAUTH_SERVICE_JOB_ID)
                if (PersistentData.hasRefreshToken) {
                    sendRefreshAccessTokenRequestIfNeeded(baseContext) { ex ->
                        if (ex != null) {
                            Log.w(TAG, ex)
                        } else {
                            // We either had a valid token, or we just created one.
                            baseContext.startActivity(
                                Intent(baseContext, FollowActivity::class.java))
                        }
                    }
                }
            }
        }
    }
}

fun Context.authorize() {
    AuthorizationService(this).performAuthorizationRequest(
        PersistentData.getAuthRequestBuilder().build(),
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, FollowActivity::class.java),
            PendingIntent.FLAG_MUTABLE
        ),
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_MUTABLE
        )
    )
}