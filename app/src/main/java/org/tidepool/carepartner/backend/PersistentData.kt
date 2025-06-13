package org.tidepool.carepartner.backend

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import net.openid.appauth.*
import org.tidepool.carepartner.FollowActivity
import org.tidepool.carepartner.MainActivity
import org.tidepool.carepartner.backend.jank.retrieveConfiguration
import org.tidepool.sdk.CommunicationHelper
import org.tidepool.sdk.Environment
import org.tidepool.sdk.Environments
import org.tidepool.sdk.model.BloodGlucose.Units
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

class PersistentData {
    private class DataHolder {
        
        var authState: AuthState = AuthState()
        var lastEmail: String? = null
        var lastName: String? = null
        var environment: Environment = Environments.Production
        var unit: Units = Units.milligramsPerDeciliter
    }
    
    companion object {
        
        private val redirectUri = Uri.parse("org.tidepool.carepartner://tidepool_service_callback")
        private const val FILENAME = "persistent-data"
        private val data = DataHolder()
        private var _authState by data::authState
        val authState by this::_authState
        var environment by data::environment
        private var _lastEmail by data::lastEmail
        val lastEmail by this::_lastEmail
        private var _lastName by data::lastName
        val lastName by this::_lastName
        var unit by data::unit

        val hasRefreshToken
            get() = authState.refreshToken?.isEmpty() != false
        
        private val lock = ReentrantLock()
        
        private val gson by lazy {
            GsonBuilder().apply {
                registerTypeAdapter(Uri::class.java, UriDeserializer())
                registerTypeAdapterFactory(EnvironmentTypeAdapter())
            }.create()
        }
        
        fun Context.readFromDisk() {
            Log.v(TAG, "Waiting for lock...")
            lock.withLock {
                if (fileList().contains(FILENAME)) {
                    Log.v(TAG, "Reading from disk...")
                    openFileInput(FILENAME).reader().use { reader ->
                        val tmpData = gson.fromJson(reader, DataHolder::class.java)
                        data.lastEmail = tmpData.lastEmail
                        data.authState = tmpData.authState
                        data.environment = tmpData.environment
                        data.lastName = tmpData.lastName
                        data.unit = tmpData.unit
                    }
                }
            }
            Log.v(TAG, "Done with lock")
        }
        
        fun Context.writeToDisk() {
            Log.v(TAG, "Waiting for lock...")
            lock.withLock {
                Log.v(TAG, "Writing to file...")
                
                openFileOutput(FILENAME, Context.MODE_PRIVATE).writer().use { writer ->
                    Log.v(TAG, "Starting Write...")
                    gson.toJson(data, writer)
                    Log.v(TAG, "Ending Write...")
                }
            }
            Log.v(TAG, "Done with lock")
        }
        
        private const val TAG = "PersistentData"
        
        private suspend fun getAuthState() {
            withTimeout(15.seconds) {
                val env = environment
                val uriString =
                    "${env.auth.url}/realms/${env.envCode}/.well-known/openid-configuration"
                Log.v(TAG, "URI: \"${uriString}\"")
                _authState = AuthState(retrieveConfiguration(Uri.parse(uriString)))
            }
        }
        
        fun Context.logout() {
            Log.v(TAG, "logout...")
            _lastEmail = null
            _lastName = null
            val authService = AuthorizationService(this)
            val endSessionRequest = _authState.authorizationServiceConfiguration?.let { config ->
                _authState.idToken?.let { idToken ->
                    EndSessionRequest.Builder(config)
                        .setIdTokenHint(idToken)
                        .setPostLogoutRedirectUri(redirectUri)
                        .build()
                }
            } ?: run {
                Log.w(TAG, "Can't logout! going directly to MainActivity")
                null
            }
            
            _authState = AuthState()
            writeToDisk()
            
            endSessionRequest?.let {
                authService.performEndSessionRequest(
                    it,
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    ),
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, FollowActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } ?: startActivity(Intent(this, MainActivity::class.java))
        }
        
        /**
         * Thrown if there is no last authorization response.
         * This should only be thrown if authorization isn't performed in the right order
         */
        class NoAuthorizationException : Exception("No Authorization Response")
        
        suspend fun Context.getAccessToken(): String {
            if (authState.accessToken == null) {
                throw NoAuthorizationException()
            }
            return suspendCancellableCoroutine { continuation ->
                val authService by lazy { AuthorizationService(this) }
                _authState.performActionWithFreshTokens(authService) { accessToken, _, ex ->
                    if (ex != null) {
                        continuation.resumeWithException(ex)
                    } else {
                        continuation.resume(accessToken!!)
                    }
                }
            }
        }
        
        suspend fun saveEmail(accessToken: suspend () -> String) {
            val helper = CommunicationHelper(environment)
            val (email, userId) = helper.users.getCurrentUserInfo(accessToken())
                .let { it.username to it.userid }
            val name = helper.metadata.getProfile(accessToken(), userId).fullName
            _lastName = name
            _lastEmail = email
            Log.v(TAG, "lastEmail: $_lastEmail, lastName: $name")
        }
        
        fun getAuthRequestBuilder(): AuthorizationRequest.Builder = runBlocking {
            getAuthState()
            return@runBlocking AuthorizationRequest.Builder(
                _authState.authorizationServiceConfiguration
                    ?: throw AssertionError("No Configuration"),
                "tidepool-carepartner-android",
                ResponseTypeValues.CODE,
                redirectUri
            ).apply {
                setScope("openid email offline_access")
                _lastEmail?.let {
                    setLoginHint(it)
                }
            }
        }
    }
}