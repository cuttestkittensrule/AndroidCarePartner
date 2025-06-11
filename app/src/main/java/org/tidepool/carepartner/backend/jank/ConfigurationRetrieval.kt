package org.tidepool.carepartner.backend.jank

import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.AuthorizationServiceDiscovery
import net.openid.appauth.connectivity.ConnectionBuilder
import net.openid.appauth.connectivity.DefaultConnectionBuilder
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val executor: ExecutorService = Executors.newSingleThreadExecutor()

// implementing this myself because the implementation in AppAuth Android likes to hang :)
suspend fun retrieveConfiguration(
    uri: Uri,
    connectionBuilder: ConnectionBuilder = DefaultConnectionBuilder.INSTANCE
): AuthorizationServiceConfiguration = suspendCancellableCoroutine { continuation ->
    val future = executor.submit(
        ConfigurationRetrievalTask(uri, connectionBuilder) { config, ex ->
            if (ex != null) {
                continuation.resumeWithException(ex)
            } else {
                continuation.resume(config!!)
            }
        }
    )
    continuation.invokeOnCancellation { future.cancel(true) }
}

private class ConfigurationRetrievalTask(
    val uri: Uri,
    val connectionBuilder: ConnectionBuilder,
    val callback: (AuthorizationServiceConfiguration?, Exception?) -> Unit
) : Runnable {
    
    override fun run() {
        try {
            connectionBuilder.openConnection(uri).apply {
                requestMethod = "GET"
                doInput = true
                connect()
            }.inputStream.bufferedReader().use {
                it.readText()
            }.let {
                AuthorizationServiceConfiguration(AuthorizationServiceDiscovery(JSONObject(it)))
            }.run { callback(this, null) }
        } catch (e: Exception) {
            callback(null, e)
        }
    }
}