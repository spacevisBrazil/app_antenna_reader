package com.uhflogger.backend

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.uhflogger.drive.DriveMonitorService
import java.util.concurrent.Executors

/**
 * Configuração do envio ao backend SpaceVis.
 *
 * Segue o mesmo estilo programático de GoogleSignInActivity — o app não usa
 * layouts XML pras telas secundárias, e misturar os dois padrões só pra esta
 * deixaria a base menos previsível.
 *
 * O Google Drive continua tendo a tela dele, intocada: são destinos
 * independentes e o aparelho pode usar um, outro ou os dois.
 */
class BackendSettingsActivity : AppCompatActivity() {

    private lateinit var etBaseUrl : EditText
    private lateinit var etFarmId  : EditText
    private lateinit var etCode    : EditText
    private lateinit var tvStatus  : TextView
    private lateinit var btnActivate: Button
    private lateinit var swEnabled : Switch

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Servidor SpaceVis"

        val scroll = ScrollView(this).apply { setBackgroundColor(0xFFFAFAFA.toInt()) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("Endereço do servidor"))
        etBaseUrl = field("https://...", InputType.TYPE_TEXT_VARIATION_URI).also { root.addView(it) }
        etBaseUrl.setText(BackendSettings.getBaseUrl(this))

        root.addView(label("Código da fazenda"))
        root.addView(hint("Preenchido sozinho ao ativar — a chave já sabe a fazenda dela."))
        etFarmId = field("Preenchido na ativação", InputType.TYPE_CLASS_NUMBER).also { root.addView(it) }
        BackendSettings.getFarmId(this).takeIf { it > 0 }?.let { etFarmId.setText(it.toString()) }

        root.addView(label("Chave de ativação"))
        root.addView(hint("Peça ao administrador. A chave vale uma vez só — depois disso o aparelho fica ativado."))
        etCode = field("XXXX-XXXX-XXXX", InputType.TYPE_CLASS_TEXT).also { root.addView(it) }

        tvStatus = TextView(this).apply {
            textSize = 14f
            gravity  = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }
        root.addView(tvStatus)

        btnActivate = Button(this).apply {
            text = "Ativar aparelho"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2E7D32.toInt())
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { onActivateClicked() }
        }
        root.addView(btnActivate)

        swEnabled = Switch(this).apply {
            text = "Enviar leituras para o servidor"
            setTextColor(0xFF111111.toInt())
            isChecked = BackendSettings.isEnabled(this@BackendSettingsActivity)
            setPadding(0, dp(24), 0, 0)
            setOnCheckedChangeListener { _, checked -> onEnabledChanged(checked) }
        }
        root.addView(swEnabled)

        root.addView(hint(
            "O envio para o Google Drive continua funcionando normalmente e é " +
            "independente deste. O arquivo local só é apagado depois que os " +
            "dois destinos configurados terminarem."
        ))

        // Renovação automática de sessão — opcional e escondida no fim de
        // propósito: quem opera em campo não digita isto. Sem preencher, a
        // sessão dura o que durar e a tela pede a chave de novo no vencimento.
        root.addView(label("Renovação automática (opcional)"))
        val etClientId = field("client_id do Keycloak", InputType.TYPE_CLASS_TEXT).also { root.addView(it) }
        etClientId.setText(BackendSettings.getClientId(this))
        val etClientSecret = field("client_secret", InputType.TYPE_TEXT_VARIATION_PASSWORD).also { root.addView(it) }
        etClientSecret.setText(BackendSettings.getClientSecret(this))
        root.addView(Button(this).apply {
            text = "Salvar renovação"
            setTextColor(0xFF555555.toInt())
            setBackgroundColor(0xFFE0E0E0.toInt())
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                BackendSettings.setClientId(this@BackendSettingsActivity, etClientId.text.toString())
                BackendSettings.setClientSecret(this@BackendSettingsActivity, etClientSecret.text.toString())
                toast("Salvo")
            }
        })

        updateStatus()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun onActivateClicked() {
        val baseUrl = etBaseUrl.text.toString().trim()
        val farmId  = etFarmId.text.toString().trim().toIntOrNull() ?: 0
        val code    = etCode.text.toString().trim()

        if (baseUrl.isEmpty()) { toast("Informe o endereço do servidor"); return }
        if (code.isEmpty())    { toast("Informe a chave de ativação"); return }

        // Salvo ANTES da chamada: a ativação usa a URL, e se o processo morrer
        // no meio o aparelho pelo menos não perde o que já foi digitado.
        BackendSettings.setBaseUrl(this, baseUrl)
        // A fazenda NÃO é mais exigida aqui: o servidor a resolve a partir da
        // própria chave (ver DeviceAuthManager.resolveFarm). Só respeita o que
        // foi digitado, como saída manual caso o servidor não consiga resolver.
        if (farmId > 0) BackendSettings.setFarmId(this, farmId)

        btnActivate.isEnabled = false
        tvStatus.text = "Ativando…"
        io.submit {
            val result = runCatching { DeviceAuthManager.activate(this, code) }
            runOnUiThread {
                // A ativação em si JÁ ACONTECEU e está persistida — se a tela
                // sumiu no meio, só a atualização visual é descartada.
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnActivate.isEnabled = true
                result.fold(
                    onSuccess = { activation ->
                        etCode.setText("")
                        BackendSettings.setEnabled(this, true)
                        swEnabled.isChecked = true
                        startMonitoring()
                        if (activation.farmId != null) {
                            etFarmId.setText(activation.farmId.toString())
                            val nome = activation.farmName?.let { n -> " — $n" } ?: ""
                            toast("Aparelho ativado (fazenda ${activation.farmId}$nome)")
                        } else if (BackendSettings.getFarmId(this) <= 0) {
                            // Ativou, mas o servidor não disse a fazenda e ninguém
                            // digitou: sem ela o envio não sai do lugar, então a
                            // tela precisa dizer isso AGORA, e não deixar o
                            // operador achar que terminou.
                            toast("Aparelho ativado, mas falta o código da fazenda")
                        } else {
                            toast("Aparelho ativado")
                        }
                    },
                    onFailure = { toast(it.message ?: "Falha na ativação") },
                )
                updateStatus()
            }
        }
    }

    private fun onEnabledChanged(checked: Boolean) {
        BackendSettings.setEnabled(this, checked)
        if (checked) {
            if (!BackendSettings.isEnabled(this)) {
                // Ligou sem ter ativado: o switch volta, senão a tela mentiria
                // dizendo que envia quando não há sessão nenhuma.
                swEnabled.isChecked = false
                toast("Ative o aparelho com a chave primeiro")
            } else {
                startMonitoring()
            }
        }
        updateStatus()
    }

    /** Garante FileObserver + agenda periódica mesmo sem conta Google no aparelho. */
    private fun startMonitoring() {
        DriveMonitorService.start(this)
        BackendUploadWorker.scheduleNow(this)
    }

    private fun updateStatus() {
        val active = BackendSettings.getAccessToken(this).isNotEmpty()
        when {
            !active -> {
                tvStatus.text = "Aparelho não ativado"
                tvStatus.setTextColor(0xFF888888.toInt())
            }
            BackendSettings.isEnabled(this) -> {
                val email = BackendSettings.getDeviceEmail(this)
                tvStatus.text = "✓ Ativado e enviando" + if (email.isNotEmpty()) "\n$email" else ""
                tvStatus.setTextColor(0xFF2E7D32.toInt())
            }
            else -> {
                tvStatus.text = "Ativado, envio desligado"
                tvStatus.setTextColor(0xFFEF6C00.toInt())
            }
        }

        // Quantos arquivos ainda não terminaram de subir. Sem este número, o
        // operador em campo não tem NENHUMA forma de saber se o envio está
        // funcionando ou parado há dias — o worker é silencioso por natureza.
        // Room não pode ser tocado na UI thread; daí o executor. O runCatching
        // em volta do submit não é decorativo: sair da tela durante a ativação
        // dispara io.shutdownNow(), e um submit posterior lançaria
        // RejectedExecutionException NA UI THREAD — ou seja, crash do app por
        // ter fechado a tela na hora errada.
        runCatching {
            io.submit {
                val pendentes = runCatching { BackendUploadStore.openCount(this) }.getOrNull()
                runOnUiThread {
                    if (pendentes != null && pendentes > 0 && !isFinishing && !isDestroyed) {
                        tvStatus.append("\n$pendentes arquivo(s) aguardando envio")
                    }
                }
            }
        }
    }

    // ── helpers de UI ─────────────────────────────────────────────────────────

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize  = 13f
        setTextColor(0xFF2E7D32.toInt())
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize  = 12f
        setTextColor(0xFF888888.toInt())
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun field(hint: String, inputType: Int) = EditText(this).apply {
        this.hint = hint
        this.inputType = inputType
        setTextColor(0xFF111111.toInt())
        setHintTextColor(0xFFAAAAAA.toInt())
        setBackgroundColor(0xFFFFFFFF.toInt())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        maxLines = 1
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
