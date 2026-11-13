package com.v2ray.ang.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.handler.UpdateNotifier

/**
 * One look at GitHub for a newer release. A single small request; a found release posts the
 * update notification. Failures are silent: the next run is 15 minutes away.
 */
class UpdateWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        UpdateNotifier.checkNow(applicationContext)
        return Result.success()
    }
}
