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

    // Device name: uses the user-configured name from Settings → About phone → Device name
    // Falls back to Build.MODEL if not set
    // No extra permissions required
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
     * Returns the Drive folder ID for this device, creating the hierarchy if needed.
     * Hierarchy: My Drive → "UHF Logger" → "[Device Model]"
     * Caches IDs in SharedPreferences to avoid repeated API calls.
     */
    fun getOrCreateDeviceFolder(context: Context, drive: Drive): String {
        val p = prefs(context)

        // Check cache first
        val cachedId = p.getString(KEY_DEVICE_ID, null)
        if (cachedId != null) return cachedId

        // Get or create root "UHF Logger" folder
        val rootId = p.getString(KEY_ROOT_ID, null)
            ?: findOrCreateFolder(drive, ROOT_FOLDER_NAME, "root").also {
                p.edit().putString(KEY_ROOT_ID, it).apply()
            }

        // Get or create device subfolder using user-configured device name
        val deviceId = findOrCreateFolder(drive, getDeviceName(context), rootId).also {
            p.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        return deviceId
    }

    private fun findOrCreateFolder(drive: Drive, name: String, parentId: String): String {
        // Search for existing folder
        val query = "name='$name' and mimeType='application/vnd.google-apps.folder' " +
                "and '$parentId' in parents and trashed=false"
        val result = drive.files().list()
            .setQ(query)
            .setFields("files(id)")
            .execute()

        result.files.firstOrNull()?.id?.let { return it }

        // Create if not found
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
     * Uploads a CSV file to the device folder on Google Drive.
     * Returns the Drive file ID on success, throws on failure.
     */
    fun uploadCsv(context: Context, drive: Drive, localFile: File): String {
        val folderId = getOrCreateDeviceFolder(context, drive)

        val metadata = DriveFile().apply {
            name    = localFile.name
            parents = listOf(folderId)
        }
        val content = FileContent("text/csv", localFile)
        val uploaded = drive.files().create(metadata, content)
            .setFields("id,name,size")
            .execute()

        Log.i(TAG, "Uploaded ${localFile.name} → Drive ID: ${uploaded.id} (${uploaded.getSize()} bytes)")
        return uploaded.id
    }
}