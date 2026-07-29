package com.uhflogger.drive

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import java.io.File

object DriveHelper {

    private const val TAG              = "DriveHelper"
    private const val APP_NAME         = "UHF Logger"
    private const val ROOT_FOLDER_NAME = "UHF Logger"
    private const val PREFS_NAME       = "drive_prefs"
    private const val KEY_ROOT_ID      = "root_folder_id"
    private const val KEY_DEVICE_ID    = "device_folder_id"

    // Nome do dispositivo: usa o nome configurado pelo usuário em Configurações → Sobre o dispositivo.
    // Usa Build.MODEL como fallback. Não requer permissões extras.
    fun getDeviceName(context: Context): String {
        val userSetName = android.provider.Settings.Global.getString(
            context.contentResolver, "device_name"
        )
        val name = if (!userSetName.isNullOrBlank()) userSetName else Build.MODEL
        return name.replace(Regex("[^a-zA-Z0-9 _\\-]"), "_").trim()
    }

    // ─── Auth ─────────────────────────────────────────────────────────────────

    fun getSignInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

    fun getSignedInAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun isSignedIn(context: Context): Boolean =
        getSignedInAccount(context) != null

    // ─── Drive Service ────────────────────────────────────────────────────────

    fun getDriveService(context: Context): Drive? {
        val account = getSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
        ).apply { selectedAccount = account.account }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName(APP_NAME)
            .build()
    }

    // ─── Folder helpers ───────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Retorna o ID da pasta do dispositivo no Drive, criando a hierarquia se necessário.
     * Hierarquia: My Drive → "UHF Logger" → "[Nome do dispositivo]"
     * IDs em cache no SharedPreferences para evitar chamadas repetidas à API.
     */
    // Trava de processo para criação de pasta — previne a condição de corrida clássica
    // "list() então create()" que duplica pastas quando chamada de múltiplas threads simultaneamente.
    private val folderLock = Any()

    fun getOrCreateDeviceFolder(context: Context, drive: Drive): String = synchronized(folderLock) {
        val p = prefs(context)

        // Verifica cache — mas confirma que a pasta ainda existe no Drive
        val cachedId = p.getString(KEY_DEVICE_ID, null)
        if (cachedId != null && folderExists(drive, cachedId)) return@synchronized cachedId

        // Cache inválido ou pasta deletada — limpa e reconstrói
        if (cachedId != null) {
            Log.w(TAG, "Cached device folder no longer exists — rebuilding")
            p.edit().remove(KEY_DEVICE_ID).remove(KEY_ROOT_ID).apply()
        }

        // Obtém ou cria a pasta raiz "UHF Logger"
        val rootId = p.getString(KEY_ROOT_ID, null)
            ?.takeIf { folderExists(drive, it) }
            ?: findOrCreateFolder(drive, ROOT_FOLDER_NAME, "root").also {
                p.edit().putString(KEY_ROOT_ID, it).apply()
            }

        val deviceId = findOrCreateFolder(drive, getDeviceName(context), rootId).also {
            p.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        Log.i(TAG, "Device folder ready: $deviceId")
        deviceId
    }

    /**
     * Verifica se uma pasta no Drive ainda existe (não foi deletada nem movida para lixeira).
     * Usado para validar IDs de pasta em cache.
     */
    private fun folderExists(drive: Drive, folderId: String): Boolean {
        return try {
            val file = drive.files().get(folderId)
                .setFields("id,trashed")
                .execute()
            file.trashed != true
        } catch (e: Exception) {
            Log.w(TAG, "Folder $folderId not found: ${e.message}")
            false
        }
    }

    private fun findOrCreateFolder(drive: Drive, name: String, parentId: String): String {
        val query = "name='$name' and mimeType='application/vnd.google-apps.folder' " +
                "and '$parentId' in parents and trashed=false"
        val result = drive.files().list()
            .setQ(query)
            .setFields("files(id)")
            .execute()

        result.files.firstOrNull()?.id?.let { return it }

        val metadata = DriveFile().apply {
            this.name    = name
            mimeType     = "application/vnd.google-apps.folder"
            parents      = listOf(parentId)
        }
        return drive.files().create(metadata).setFields("id").execute().id
    }

    fun clearFolderCache(context: Context) {
        prefs(context).edit()
            .remove(KEY_ROOT_ID)
            .remove(KEY_DEVICE_ID)
            .apply()
    }

    // ─── Upload ───────────────────────────────────────────────────────────────

    /**
     * Verifica se um arquivo com o nome dado já existe na pasta do dispositivo.
     * Retorna o ID do arquivo existente, ou null se não encontrado.
     * Usado para evitar uploads duplicados em caso de retry.
     */
    fun findExistingFile(drive: Drive, context: Context, fileName: String): String? {
        val folderId = getOrCreateDeviceFolder(context, drive)
        val query = "name='$fileName' and '$folderId' in parents and trashed=false"
        val result = drive.files().list()
            .setQ(query)
            .setFields("files(id,name,size)")
            .execute()
        return result.files.firstOrNull()?.id?.also {
            Log.i(TAG, "File already exists in Drive: $fileName → $it")
        }
    }

    // Trava de processo para a sequência check-then-upload.
    // Evita que duas threads passem por findExistingFile() simultaneamente
    // (nenhuma vê o upload em andamento da outra) e criem o arquivo duplicado no Drive.
    private val uploadLock = Any()

    /**
     * Envia um arquivo CSV para a pasta do dispositivo no Google Drive.
     * Retorna o ID do arquivo no Drive em caso de sucesso; lança exceção em caso de falha.
     */
    fun uploadCsv(context: Context, drive: Drive, localFile: File): String = synchronized(uploadLock) {
        val existing = findExistingFile(drive, context, localFile.name)
        if (existing != null) {
            Log.i(TAG, "Skipping upload — file already in Drive: ${localFile.name}")
            return@synchronized existing
        }

        val folderId = getOrCreateDeviceFolder(context, drive)
        val metadata = DriveFile().apply {
            name    = localFile.name
            parents = listOf(folderId)
        }
        val content  = FileContent("text/csv", localFile)
        val uploaded = drive.files().create(metadata, content)
            .setFields("id,name,size")
            .execute()

        Log.i(TAG, "Uploaded ${localFile.name} → Drive ID: ${uploaded.id} (${uploaded.getSize()} bytes)")
        uploaded.id
    }
}