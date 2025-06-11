package org.tidepool.carepartner.backend.jank

import android.content.Context
import android.os.AsyncTask
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.*
import net.openid.appauth.AuthState.AuthStateAction
import net.openid.appauth.AuthorizationException.AuthorizationRequestErrors
import net.openid.appauth.connectivity.ConnectionBuilder
import net.openid.appauth.connectivity.DefaultConnectionBuilder
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val systemClock by lazy {
    Class.forName("net.openid.appauth.SystemClock").run {
        getDeclaredField("INSTANCE").run {
            isAccessible = true
            get(null)
        }
    }
}

private val clockClass by lazy {
    Class.forName("net.openid.appauth.Clock")
}

private val tokenRequestTask by lazy {
    Class.forName("net.openid.appauth.AuthorizationService.TokenRequestTask").run {
        getDeclaredConstructor(
            TokenRequest::class.java,
            ClientAuthentication::class.java,
            ConnectionBuilder::class.java,
            clockClass,
            AuthorizationService.TokenResponseCallback::class.java,
            java.lang.Boolean.TYPE
        )
    }
}

/**
 * Perform a [TokenRequest] without an [AuthorizationService].
 * This is necessary because you need a [Context] to create that (which isn't used in [AuthorizationService.performTokenRequest]).
 * So, this method can be used in the situation that you do not have access to a [Context], but need to perform a token request.
 */
suspend fun performTokenRequest(
    request: TokenRequest,
    clientAuthentication: ClientAuthentication = NoClientAuthentication.INSTANCE,
    connectionBuilder: ConnectionBuilder = DefaultConnectionBuilder.INSTANCE,
    skipIssuerHttpsCheck: Boolean = false
): TokenResponse = suspendCancellableCoroutine { continuation ->
    val callback = { response: TokenResponse?, ex: AuthorizationException? ->
        if (response != null) {
            continuation.resume(response)
        } else {
            continuation.resumeWithException(ex!!)
        }
    } as AuthorizationService.TokenResponseCallback
    // TokenRequestTask extends AsyncTask<Unit, Unit, JSONObject>,
    // so this cast will succeed at runtime
    @Suppress("UNCHECKED_CAST")
    val task = tokenRequestTask.newInstance(
        request,
        clientAuthentication,
        connectionBuilder,
        systemClock,
        callback,
        skipIssuerHttpsCheck
    ) as AsyncTask<Unit, Unit, JSONObject>
    task.execute()
    continuation.invokeOnCancellation {
        task.cancel(true)
    }
}

suspend fun AuthState.performActionWithFreshTokens(
    service: AuthorizationService,
    clientAuth: ClientAuthentication,
    refreshTokenAdditionalParams: Map<String, String>,
    action: AuthStateAction
) {
    Preconditions.checkNotNull(service, "service cannot be null")
    Preconditions.checkNotNull(clientAuth, "client authentication cannot be null")
    Preconditions.checkNotNull(
        refreshTokenAdditionalParams,
        "additional params cannot be null"
    )
    Preconditions.checkNotNull(action, "action cannot be null")
    
    if (!needsTokenRefresh) {
        action.execute(accessToken, getIdToken(), null)
        return
    }
    
    if (refreshToken == null) {
        val ex = AuthorizationException.fromTemplate(
            AuthorizationRequestErrors.CLIENT_ERROR,
            IllegalStateException("No refresh token available and token have expired")
        )
        action.execute(null, null, ex)
        return
    }
    val response = try {
        performTokenRequest(createTokenRefreshRequest(refreshTokenAdditionalParams))
    } catch (ex: AuthorizationException) {
        update(null as TokenResponse?, ex)
        throw ex
    }
    
    service.performTokenRequest(
        createTokenRefreshRequest(refreshTokenAdditionalParams),
        clientAuth
    ) { response, ex ->
        update(response, ex)
    }
}
