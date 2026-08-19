package com.uhflogger.backend

import android.content.Intent
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

    private lateinit var etFarmId  : EditText
    private lateinit var farmBox   : LinearLayout
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

        // O servidor NÃO é mais um campo: ele é do build (flavor hml/prd), como
        // no app de campo. Fica só a informação de para onde este aparelho manda
        // os dados — que é o que alguém precisa conferir, não editar.
        root.addView(label("Servidor"))
        root.addView(hint(ambienteDoBuild()))

        // Bloco da fazenda: ESCONDIDO por padrão. A chave já nasce atrelada a uma
        // fazenda e o servidor a informa na ativação — mostrar o campo só cria
        // uma pergunta que a pessoa não tem como responder, e um jeito a mais de
        // errar. Só aparece se a resolução automática falhar (ver updateStatus).
        farmBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        farmBox.addView(label("Código da fazenda"))
        farmBox.addView(hint("Não foi possível descobrir sozinho. Peça o número ao administrador."))
        etFarmId = field("Ex.: 85", InputType.TYPE_CLASS_NUMBER).also { farmBox.addView(it) }
        BackendSettings.getFarmId(this).takeIf { it > 0 }?.let { etFarmId.setText(it.toString()) }
        root.addView(farmBox)

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
        aplicarLinkDeAtivacao(intent)
    }

    /**
     * A tela é `singleTop`-friendly: se já estiver aberta quando o link for
     * tocado, o Android entrega aqui em vez de recriar. Sem isto, tocar no link
     * com a tela aberta não faria nada — e a pessoa acharia que o link não presta.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        aplicarLinkDeAtivacao(intent)
    }

    /**
     * Preenche a tela a partir de `uhflogger://ativar?chave=XXXX-XXXX-XXXX-XXXX`
     * (opcionalmente `&servidor=https://...`).
     *
     * É isto que tira a digitação do caminho: o administrador manda o link por
     * mensagem, a pessoa toca, e só resta apertar Ativar. Digitar uma chave de
     * 16 caracteres num celular em campo é onde a ativação falhava — e o erro
     * ("chave inválida") não diz qual caractere saiu errado.
     *
     * NÃO ativa sozinho de propósito: o toque no botão é a confirmação de que
     * a pessoa está diante do aparelho certo. A chave é de uso único; gastá-la
     * por um link aberto sem querer seria pior que a digitação.
     */
    private fun aplicarLinkDeAtivacao(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.scheme.equals("uhflogger", ignoreCase = true)) return

        // `&servidor=` é saída de emergência (apontar um aparelho pra outro
        // ambiente sem gerar build). Fora disso, o servidor é o do flavor.
        data.getQueryParameter("servidor")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            BackendSettings.setBaseUrl(this, it)
        }
        val chave = data.getQueryParameter("chave")?.trim().orEmpty()
        if (chave.isNotEmpty()) {
            etCode.setText(chave)
            toast("Chave recebida. Toque em Ativar aparelho.")
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /** Para onde ESTE build manda os dados — conferível, não editável. */
    private fun ambienteDoBuild(): String {
        val url = BackendSettings.getBaseUrl(this)
        val nome = if (url == com.uhflogger.BuildConfig.API_BASE_URL) {
            if (com.uhflogger.BuildConfig.FLAVOR == "prd") "Produção" else "Homologação"
        } else {
            "Servidor personalizado"
        }
        return "$nome\n$url"
    }

    private fun onActivateClicked() {
        val farmId  = etFarmId.text.toString().trim().toIntOrNull() ?: 0
        val code    = etCode.text.toString().trim()

        if (code.isEmpty()) { toast("Informe a chave de ativação"); return }
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
                        } else if (activation.options.size > 1) {
                            // Várias fazendas: a pessoa escolhe numa lista, nunca
                            // digitando um número que ela não tem como saber.
                            escolherFazenda(activation.options)
                        } else if (BackendSettings.getFarmId(this) <= 0) {
                            // Ativou, mas o servidor não disse a fazenda e ninguém
                            // digitou: sem ela o envio não sai do lugar. Revela o
                            // campo AGORA, em vez de deixar a pessoa achar que
                            // terminou — e sem ter onde consertar.
                            farmBox.visibility = View.VISIBLE
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

    /**
     * Escolha de fazenda quando o aparelho tem acesso a mais de uma.
     *
     * Lista de nomes, não campo numérico: quem está com o aparelho na mão sabe
     * em que fazenda está, não o id dela no banco. Mesma regra do app de campo,
     * que só decide sozinho quando existe uma única fazenda.
     */
    private fun escolherFazenda(opcoes: List<DeviceAuthManager.Farm>) {
        val nomes = opcoes.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Qual é a fazenda deste aparelho?")
            .setCancelable(false)
            .setItems(nomes) { _, i ->
                val f = opcoes[i]
                BackendSettings.setFarmId(this, f.id)
                etFarmId.setText(f.id.toString())
                toast("Fazenda: ${f.name}")
                updateStatus()
            }
            .show()
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
                // O motivo da parada, quando há um. Saber que há 12 arquivos na
                // fila não diz NADA sobre o que fazer; "lote grande demais (413)"
                // diz. Sem esta linha, todo diagnóstico começava com alguém
                // abrindo log de servidor — e em campo isso não acontece.
                val erro = runCatching { BackendUploadStore.lastError(this) }.getOrNull()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (pendentes != null && pendentes > 0) {
                        tvStatus.append("\n$pendentes arquivo(s) aguardando envio")
                    }
                    if (!erro.isNullOrEmpty()) {
                        tvStatus.append("\nÚltima falha: $erro")
                        tvStatus.setTextColor(0xFFEF6C00.toInt())
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
