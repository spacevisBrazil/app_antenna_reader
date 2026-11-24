package com.uhflogger.drive

import android.content.Intent
import com.uhflogger.ui.Brand
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MenuItem
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import com.uhflogger.R

class GoogleSignInActivity : AppCompatActivity() {

    private lateinit var signInClient: GoogleSignInClient
    private lateinit var btnSignIn   : Button
    private lateinit var btnSignOut  : Button
    private lateinit var tvStatus    : TextView

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            onSignInSuccess(account?.email ?: "")
        } catch (e: ApiException) {
            toast("Erro ao entrar: ${e.statusCode}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Google Drive Sync"

        signInClient = GoogleSignIn.getClient(this, DriveHelper.getSignInOptions())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            val pad     = dp(24)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(c(R.color.sv_background))
        }

        val tvTitle = TextView(this).apply {
            text     = "Sincronização com Google Drive"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(c(R.color.sv_foreground))
            gravity  = Gravity.CENTER
            val lp   = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        val tvInfo = TextView(this).apply {
            text     = "Os arquivos CSV serão enviados automaticamente para:\n\n" +
                    "My Drive → UHF Logger → ${DriveHelper.getDeviceName(this@GoogleSignInActivity)}\n\n" +
                    "O login é feito uma única vez."
            textSize = 13f
            setTextColor(c(R.color.sv_muted_foreground))
            gravity  = Gravity.CENTER
            val lp   = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(24)
            layoutParams = lp
        }

        tvStatus = TextView(this).apply {
            textSize = 14f
            gravity  = Gravity.CENTER
            val lp   = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(16)
            layoutParams = lp
        }

        btnSignIn = Button(this).apply {
            text = "Entrar com Google"
            Brand.styleButton(this, Brand.ButtonKind.PRIMARY)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            lp.bottomMargin = dp(12)
            layoutParams = lp
            setOnClickListener { signInLauncher.launch(signInClient.signInIntent) }
        }

        btnSignOut = Button(this).apply {
            text = "Sair da conta Google"
            Brand.styleButton(this, Brand.ButtonKind.SECONDARY)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { signOut() }
        }

        val btnBattery = Button(this).apply {
            text = "Desativar otimização de bateria"
            Brand.styleButton(this, Brand.ButtonKind.SECONDARY)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            lp.topMargin = dp(24)
            layoutParams = lp
            setOnClickListener { requestBatteryOptimizationExemption() }
        }

        root.addView(tvTitle)
        root.addView(tvInfo)
        root.addView(tvStatus)
        root.addView(btnSignIn)
        root.addView(btnSignOut)
        root.addView(btnBattery)
        setContentView(Brand.withTopBar(this, root))

        updateUI()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun updateUI() {
        val account = DriveHelper.getSignedInAccount(this)
        if (account != null) {
            tvStatus.text      = "✓ Conectado: ${account.email}"
            tvStatus.setTextColor(c(R.color.sv_primary))
            btnSignIn.isEnabled  = false
            btnSignOut.isEnabled = true
            DriveMonitorService.start(this)
        } else {
            tvStatus.text      = "Não conectado"
            tvStatus.setTextColor(c(R.color.sv_muted_foreground))
            btnSignIn.isEnabled  = true
            btnSignOut.isEnabled = false
        }
    }

    private fun onSignInSuccess(email: String) {
        toast("Conectado: $email")
        DriveHelper.clearFolderCache(this)
        DriveMonitorService.start(this)
        DriveUploadWorker.scheduleNow(this)
        updateUI()
    }

    private fun signOut() {
        signInClient.signOut().addOnCompleteListener {
            DriveMonitorService.stop(this)
            DriveHelper.clearFolderCache(this)
            toast("Conta desconectada")
            updateUI()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun c(id: Int) = Brand.color(this, id)
}