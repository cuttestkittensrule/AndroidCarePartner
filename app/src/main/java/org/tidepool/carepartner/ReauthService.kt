package org.tidepool.carepartner

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import net.openid.appauth.AuthorizationService
import org.tidepool.carepartner.backend.PersistentData

private var TAG = ReauthService::class.java.simpleName

private const val PERIOD_MILLIS = 5_000L

/** Periodically refreshes the access token when needed. */
class ReauthService : JobService() {

    companion object {
        fun start(context: Context, jobId: Int) {
            schedule(context, jobId)
        }
    }

    private lateinit var params: JobParameters

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.v(TAG, "Starting")
        this.params = params!!
        sendRefreshAccessTokenRequestIfNeeded(this) { ex ->
            if (ex != null) {
                jobFinished(params, true)  // Request exponential retry
            } else {
                jobFinished(params, false) // Job done; reschedule
                schedule(applicationContext, params.jobId)
            }
        }
        return true // The job is not done yet; we will call jobFinished() when it is.
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.v(TAG, "Stopping")
        return true
    }
}

/** If there is an auth token refreshes the access token if it has expired. */
fun sendRefreshAccessTokenRequestIfNeeded(context: Context, callback: (ex: Exception?) -> Unit) {
    if (PersistentData.authState.needsTokenRefresh) {
        Log.v(TAG, "Performing a token refresh to get a new access token")
        val request = PersistentData.authState.createTokenRefreshRequest()
        AuthorizationService(context).performTokenRequest(request) { resp, ex ->
            PersistentData.authState.update(resp, ex)
            callback(ex)
        }
    } else {
        callback(null)
    }
}

private fun schedule(context: Context, jobId: Int) {
    val serviceComponent = ComponentName(context, ReauthService::class.java)
    val builder = JobInfo.Builder(jobId, serviceComponent)
        .setPeriodic(PERIOD_MILLIS)
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NOT_ROAMING)

    val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    jobScheduler.schedule(builder.build())
}
