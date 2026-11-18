package com.uhflogger.smartbox.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.uhflogger.backend.BackendSettings
import com.uhflogger.smartbox.SmartboxPrefs
import com.uhflogger.smartbox.data.RoomSmartboxRepository
import java.util.concurrent.TimeUnit

/**
 * Envio das leituras da smartbox ao backend, na mesma esteira do app
 * (WorkManager, como o BackendUploadWorker): só roda com rede, um por vez
 * (trabalho único), e tenta de novo sozinho com backoff quando para por rede ou
 * servidor. A coleta só avisa no fim de cada descarga ([scheduleNow]).
 */
class SmartboxUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val farmId = BackendSettings.getFarmId(ctx).toLong()
        if (farmId <= 0) return Result.success()
        val repo = RoomSmartboxRepository.get(ctx)

        // Cadastro no backend é por fazenda: trocou a fazenda, recadastra tudo.
        if (SmartboxPrefs.lastUploadFarm(ctx) != farmId) {
            repo.resetRegistrations()
            SmartboxPrefs.setLastUploadFarm(ctx, farmId)
        }

        val summary = SmartboxUploader.uploadPending(farmId, repo, SmartboxApi.Backend(ctx)) { System.currentTimeMillis() }
        Log.i(
            TAG,
            "envio: ${summary.sentReadings} enviadas (${summary.accepted} aceitas), ${summary.rejectedReadings} recusadas, " +
                "${summary.otherFarmPending} de outra fazenda paradas, ${summary.errors.size} erro(s), parou=${summary.stoppedBecause}",
        )
        SmartboxPrefs.setLastUpload(ctx, summary)
        return if (summary.retryLater) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "SmartboxUploadWorker"
        private const val WORK_NAME = "smartbox_upload"

        fun scheduleNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmartboxUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
