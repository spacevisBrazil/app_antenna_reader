package com.uhflogger.backend

import android.content.Context
import android.util.Log
import com.uhflogger.CsvExporter
import java.io.File

/**
 * Quem pode apagar o CSV local, e quando.
 *
 * O PROBLEMA QUE ISTO RESOLVE
 * Com dois destinos, o primeiro a terminar não pode apagar o arquivo debaixo do
 * outro. Antes havia um só (o Drive), então ele apagava assim que subia; manter
 * esse comportamento agora significaria perder as leituras que o backend ainda
 * não recebeu — silenciosamente, e só em campo, que é onde ninguém vê.
 *
 * A regra é: o arquivo some quando os DOIS destinos terminaram com ele. Quem
 * chegar por último apaga. Se o envio ao backend estiver desligado, o Drive
 * volta a apagar sozinho, exatamente como sempre fez.
 *
 * E, em qualquer caso, o arquivo da sessão EM ANDAMENTO nunca é apagado — havia
 * um caminho por onde isso acontecia (a varredura de inicialização enfileirava
 * o arquivo aberto pro Drive, que subia parcial e apagava com a captura ainda
 * escrevendo nele).
 */
object FileRetention {

    private const val TAG = "FileRetention"

    /**
     * O Drive terminou de subir este arquivo.
     *
     * O Drive SOZINHO nunca apaga. Antes havia um atalho aqui: com o envio ao
     * backend desligado, o Drive voltava a apagar na hora — e era por ele que se
     * perdia dado de verdade. O aparelho que ainda não foi ativado é
     * indistinguível do que nunca vai ser, então "backend desligado" era lido
     * como "ninguém mais quer este arquivo"; quem capturava antes de ativar (a
     * ordem natural: testar a leitura primeiro, ativar depois) via as leituras
     * daquele período sumirem sem aviso nenhum.
     *
     * A regra agora é uma só: o CSV local morre quando o SERVIDOR confirmou —
     * nunca antes. O Drive é destino paralelo, não substituto: ter uma cópia lá
     * não prova que a leitura chegou ao backend, e é o backend que a transforma
     * em passagem de animal.
     *
     * Consequência aceita: um aparelho que nunca for ativado acumula CSV até
     * encher. É o lado certo pra errar — disco cheio é visível e reversível,
     * leitura apagada não volta.
     */
    fun onDriveDone(context: Context, file: File) {
        // Cria a linha se o worker do backend ainda nem viu este arquivo —
        // senão a marcação se perderia e o arquivo ficaria preso pra sempre.
        BackendUploadStore.ensure(
            context, file.absolutePath, CsvReadingParser.captureClientId(file.name)
        )
        BackendUploadStore.markDriveDone(context, file.absolutePath)

        if (BackendUploadStore.get(context, file.absolutePath)?.closed == true) {
            deleteIfIdle(context, file, "Drive (backend já havia confirmado)")
        } else {
            Log.i(TAG, "${file.name} mantido: o servidor ainda não confirmou")
        }
    }

    /** O backend fechou a captura deste arquivo. */
    fun onBackendDone(context: Context, file: File) {
        // Simétrico ao caso de cima: cada destino só espera pelo OUTRO se o
        // outro estiver de fato configurado. Sem isto, um aparelho que use só o
        // backend esperaria pra sempre por um `driveDone` que nunca chega, e os
        // CSVs se acumulariam no disco até encher o aparelho.
        if (!com.uhflogger.drive.DriveHelper.isSignedIn(context)) {
            deleteIfIdle(context, file, "backend (Drive não configurado)")
            return
        }

        if (BackendUploadStore.get(context, file.absolutePath)?.driveDone == true) {
            deleteIfIdle(context, file, "backend (Drive já havia terminado)")
        } else {
            Log.i(TAG, "${file.name} mantido: Drive ainda não terminou")
        }
    }

    /**
     * Apaga só se o arquivo não for o da captura em andamento. Um arquivo aberto
     * apagado não libera espaço nem interrompe a escrita — a sessão segue
     * gravando num inode sem nome, e tudo que vier depois se perde sem erro
     * nenhum aparecer.
     */
    private fun deleteIfIdle(context: Context, file: File, reason: String) {
        if (CsvExporter.activeFilePath() == file.absolutePath) {
            Log.w(TAG, "${file.name} NÃO apagado: é a captura em andamento ($reason)")
            return
        }
        if (file.delete()) {
            Log.i(TAG, "${file.name} apagado — $reason")
            BackendUploadStore.delete(context, file.absolutePath)
        } else {
            Log.w(TAG, "Falha ao apagar ${file.name}")
        }
    }
}
