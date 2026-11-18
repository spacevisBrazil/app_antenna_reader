package com.uhflogger.smartbox.collect

import com.uhflogger.smartbox.ble.DownloadControl
import com.uhflogger.smartbox.ble.DownloadDeps
import com.uhflogger.smartbox.ble.DownloadProgress
import com.uhflogger.smartbox.ble.DownloadResult
import com.uhflogger.smartbox.ble.OpenSession
import com.uhflogger.smartbox.ble.SmartboxDownload
import com.uhflogger.smartbox.data.SmartboxRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uma descarga numa sessão já aberta, gravando no banco local. Usada pela
 * coleta automática e pelo "Baixar agora" da tela de técnico: a mesma regra
 * nos dois (commit antes do ACK, para só entre lotes).
 */
object SmartboxDownloads {

    suspend fun run(
        open: OpenSession,
        moduleId: String,
        farmId: Long,
        repo: SmartboxRepository,
        control: DownloadControl,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        val result = SmartboxDownload.downloadAll(
            open.session,
            DownloadDeps(
                // Room é bloqueante: o commit roda no IO e só volta depois dele.
                persistBatch = { readings ->
                    withContext(Dispatchers.IO) { repo.persistBatch(moduleId, farmId, readings, System.currentTimeMillis()) }
                },
                now = { System.currentTimeMillis() },
                onProgress = onProgress,
                control = control,
            ),
        )
        if (!result.stoppedEarly) {
            withContext(Dispatchers.IO) { repo.markDownloadSuccess(moduleId, System.currentTimeMillis()) }
        }
        return result
    }
}
