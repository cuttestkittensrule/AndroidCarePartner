package org.tidepool.carepartner.backend.jank

import android.content.Context
import android.os.AsyncTask
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.*
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
    Class.forName("net.openid.appauth.AuthorizationService\$TokenRequestTask").run {
        getDeclaredConstructor(
            TokenRequest::class.java,
            ClientAuthentication::class.java,
            ConnectionBuilder::class.java,
            clockClass,
            AuthorizationService.TokenResponseCallback::class.java,
            java.lang.Boolean::class.java
        ).apply {
            isAccessible = true
        }
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
    val callback = AuthorizationService.TokenResponseCallback { response, ex ->
        if (response != null) {
            continuation.resume(response)
        } else {
            continuation.resumeWithException(ex!!)
        }
    }
    // TokenRequestTask extends AsyncTask<Void, Void, JSONObject>,
    // so this cast will succeed at runtime
    @Suppress("UNCHECKED_CAST")
    val task = tokenRequestTask.newInstance(
        request,
        clientAuthentication,
        connectionBuilder,
        systemClock,
        callback,
        skipIssuerHttpsCheck
    ) as AsyncTask<Void, Void, JSONObject>
    task.execute()
    continuation.invokeOnCancellation {
        task.cancel(true)
    }
}