package com.uhflogger

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import java.util.concurrent.Executors

/**
 * Configura o WorkManager com executor de thread única.
 *
 * Por padrão o WorkManager pode rodar múltiplos workers concorrentemente (ex: um scheduleNow()
 * e uma verificação periódica ao mesmo tempo). Forçar thread única garante que DriveUploadWorker.doWork()
 * nunca rode em paralelo consigo mesmo, eliminando a condição de corrida que criava pastas e
 * arquivos duplicados no Drive.
 *
 * Combinado com os locks em DriveUploadWorker e DriveHelper, fornece defesa em profundidade.
 */
class UHFLoggerApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(Executors.newSingleThreadExecutor())
            .build()
}
