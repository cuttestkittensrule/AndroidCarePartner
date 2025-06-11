package org.tidepool.carepartner.backend

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.SharingStarted.Companion
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationService
import org.tidepool.carepartner.backend.jank.performTokenRequest
import org.tidepool.sdk.CommunicationHelper
import org.tidepool.sdk.model.confirmations.Confirmation
import org.tidepool.sdk.model.metadata.users.TrustUser
import org.tidepool.sdk.model.metadata.users.TrustorUser
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

class DataState(
    private val authState: AuthState
): ViewModel() {
    companion object {
        const val TAG = "DataState"
        private val maxRate = 5.minutes
    }
    
    private val dataFlow = flow {
        setup()
        val map = HashMap<String, PillData>()
        try {
            while (true) {
                val start = TimeSource.Monotonic.markNow()
                update(map)
                val elapsed = start.elapsedNow()
                emit(Collections.unmodifiableMap(HashMap(map)))
                if (maxRate > elapsed) {
                    delay(maxRate - elapsed)
                }
            }
        } finally {
            shutdown()
        }
    }
    
    val data = dataFlow.stateIn(viewModelScope, SharingStarted.Eagerly, HashMap<String,PillData>())
    private val _invitations = MutableStateFlow(emptyArray<Confirmation>())
    val invitations = _invitations.asStateFlow()
    
    
    
    private val communicationHelper: CommunicationHelper by lazy {
        CommunicationHelper(
            PersistentData.environment
        )
    }
    
    private fun getIdFlow(): Flow<Pair<String, String?>> = flow {
        val userId = communicationHelper.users.getCurrentUserInfo(getAccessToken()).userid
        Log.v(TAG, "Listing users...")
        val trustUsers = communicationHelper.metadata.listUsers(getAccessToken(), userId)
        trustUsers.filterIsInstance<TrustorUser>().filter {
            it.permissions.contains(TrustUser.Permission.view)
        }.forEach {
            emit(it.userid to it.profile?.fullName)
        }
    }
    
    private suspend fun setup() {
        exchangeAuthCode()
    }
    
    private suspend fun update(map: MutableMap<String, PillData>) {
        TODO()
    }
    
    private suspend fun shutdown() {
        TODO()
    }
    
    private suspend fun getAccessToken(): String {
        if (authState.accessToken == null) {
            exchangeAuthCode()
        }
        return suspendCancellableCoroutine { continuation ->
            val authService by lazy { AuthorizationService(this) }
            authState.performActionWithFreshTokens(authService) { accessToken, _, ex ->
                if (ex != null) {
                    continuation.resumeWithException(ex)
                } else {
                    continuation.resume(accessToken!!)
                }
            }
        }
    }
    
    private suspend fun exchangeAuthCode() {
        val request = authState.lastAuthorizationResponse?.createTokenExchangeRequest()
            ?: throw RuntimeException("No last authorization response!")
        val response = performTokenRequest(request)
        authState.update(response, null)
    }
    
    
    
    private suspend fun updatePills() {
        TODO()
    }
    
    init {
        viewModelScope.launch {
        
        }
    }
}