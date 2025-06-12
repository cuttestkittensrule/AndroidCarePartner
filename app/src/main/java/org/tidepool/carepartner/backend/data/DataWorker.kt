package org.tidepool.carepartner.backend.data

import android.content.Context
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import net.openid.appauth.AuthorizationService

class DataWorker(appContext: Context, workerParams: WorkerParameters): CoroutineWorker(appContext, workerParams) {
    private val authService by lazy { AuthorizationService(appContext) }
    override suspend fun doWork(): Result {
        try {
            update()
        } catch (ex: Exception) {
            return Result.failure()
        }
        return Result.success()
    }
    
    private suspend fun update() {
        TODO("Implement update")
    }
}