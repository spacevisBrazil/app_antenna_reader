package com.uhflogger.smartbox.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.uhflogger.backend.BackendSettings
import com.uhflogger.smartbox.SmartboxActions
import com.uhflogger.smartbox.ble.FieldKey
import com.uhflogger.smartbox.collect.SmartboxCollector
import com.uhflogger.smartbox.ble.NearbySmartbox
import com.uhflogger.smartbox.ble.SmartboxConnection
import com.uhflogger.smartbox.ble.SmartboxScanner
import com.uhflogger.smartbox.ble.SmartboxScanner.Status
import com.uhflogger.smartbox.ble.configReasonText
import com.uhflogger.smartbox.ble.decodeStateFlags
import com.uhflogger.smartbox.ble.displayName
import com.uhflogger.smartbox.domain.FarmLabel
import com.uhflogger.smartbox.domain.FarmRules
import com.uhflogger.smartbox.radio.RadioDecision
import com.uhflogger.smartbox.radio.ReaderRadio
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Tela de técnico "Buscar smartbox" (seção 11.2 do guia), fora do caminho do
 * operador: entra pelas Configurações.
 *
 * Layout pensado para o DT50 (tela estreita): nenhum botão na mesma linha de
 * texto flexível, botões na largura toda, erros sempre visíveis num card.
 */
@OptIn(FlowPreview::class)
class SmartboxSearchActivity : AppCompatActivity() {

    private val green = Color.parseColor("#2E7D32")
    private val bg = Color.parseColor("#FAFAFA")
    private val card = Color.parseColor("#FFFFFF")
    private val border = Color.parseColor("#E0E0E0")
    private val ink = Color.parseColor("#111111")
    private val muted = Color.parseColor("#666666")
    private val amberBg = Color.parseColor("#FFF8E1")
    private val amberBorder = Color.parseColor("#FFB300")
    private val redBg = Color.parseColor("#FFEBEE")
    private val redBorder = Color.parseColor("#E53935")

    private lateinit var root: LinearLayout
    private var releaseScan: (() -> Unit)? = null
    private var releaseCollector: (() -> Unit)? = null

    /** Smartbox com ação em andamento e o texto de progresso. */
    private var busy: Pair<String, String>? = null
    /** Resultado da última ação (fica até a próxima). */
    private var lastResult: Pair<String, Boolean>? = null   // texto, éErro

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Smartbox"
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)
        setContentView(scroll)
        rerender()  // primeiro desenho sem esperar o sample

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(SmartboxScanner.nearby, SmartboxScanner.status, SmartboxConnection.activeModuleId) { n, s, a ->
                    Triple(n, s, a)
                }
                    // Cada anúncio atualiza o mapa (várias vezes por segundo):
                    // redesenhar a cada um tiraria o botão de baixo do dedo.
                    .sample(RENDER_INTERVAL_MS)
                    .collect { (nearby, status, activeId) -> render(nearby, status, activeId) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!FieldKey.isConfigured) return
        // A coleta automática cede o rádio enquanto esta tela está aberta.
        releaseCollector = SmartboxCollector.hold()
        if (!SmartboxScanner.hasScanPermission(this)) requestBlePermissions()
        releaseScan = SmartboxScanner.request(this, "search")
    }

    override fun onStop() {
        releaseScan?.invoke()
        releaseScan = null
        releaseCollector?.invoke()
        releaseCollector = null
        super.onStop()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun requestBlePermissions() = requestPermissions(requiredPermissions(), REQ_PERMISSIONS)

    @Deprecated("Activity Result API não é usada no resto do app")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            SmartboxScanner.retryNow()
        }
    }

    // ── Render ──────────────────────────────────────────────────────────────

    private fun render(nearby: Map<String, NearbySmartbox>, status: Status, activeId: String?) {
        root.removeAllViews()

        if (!FieldKey.isConfigured) {
            root.addView(noticeCard(FieldKey.NOT_CONFIGURED_MESSAGE, error = true))
            return
        }

        if (ReaderRadio.decision() == RadioDecision.BLOCK) {
            root.addView(noticeCard("O leitor está lendo pelo Bluetooth. Pare a captura para usar a smartbox.", error = false))
            return
        }

        val myFarmId = BackendSettings.getFarmId(this)
        val farms = BackendSettings.getFarms(this)
        val farmTitle = if (myFarmId > 0) FarmLabel.of(myFarmId.toLong(), farms).title else null

        renderStatus(nearby.size, status, activeId)
        lastResult?.let { (msg, isError) -> root.addView(noticeCard(msg, isError)) }
        if (farmTitle == null) {
            root.addView(noticeCard("Ative o app numa fazenda (Configurações → Servidor SpaceVis) para cadastrar smartboxes.", error = false))
        }

        val sorted = nearby.values.sortedByDescending { it.rssi }
        val mine = sorted.filter { myFarmId > 0 && it.adv.farmId == myFarmId.toLong() }
        val free = sorted.filter { it.adv.farmId == 0L }
        val other = sorted.filter { it.adv.farmId != 0L && it.adv.farmId != myFarmId.toLong() }

        section("${farmTitle ?: "Esta fazenda"} (${mine.size})")
        if (mine.isEmpty()) root.addView(mutedText("Nenhuma desta fazenda por perto."))
        mine.forEach { root.addView(boxCard(it, myFarmId, farms)) }

        section("Não cadastradas (${free.size})")
        if (free.isEmpty()) root.addView(mutedText("Nenhuma sem fazenda por perto. Ela some enquanto outro aparelho está conectado nela."))
        free.forEach { root.addView(boxCard(it, myFarmId, farms)) }

        if (other.isNotEmpty()) {
            section("De outra fazenda (${other.size})")
            other.forEach { root.addView(boxCard(it, myFarmId, farms)) }
        }
    }

    private fun renderStatus(count: Int, status: Status, activeId: String?) {
        when (status) {
            Status.NO_PERMISSION -> {
                root.addView(noticeCard("Sem permissão de Bluetooth. Permita \"Dispositivos próximos\" para o UHF Logger.", error = true))
                root.addView(fullButton("Pedir de novo") { requestBlePermissions() })
                root.addView(fullButton("Abrir configurações do app") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                })
            }
            Status.BLUETOOTH_OFF -> root.addView(noticeCard("Ligue o Bluetooth. A busca começa sozinha.", error = false))
            Status.ERROR -> {
                root.addView(noticeCard("A busca parou (${SmartboxScanner.lastError ?: "erro do Bluetooth"}). Tenta de novo sozinha em instantes.", error = true))
                root.addView(fullButton("Procurar de novo") { SmartboxScanner.retryNow() })
            }
            Status.PAUSED_FOR_CONNECTION ->
                root.addView(statusText(activeId?.let { "Esperando a conexão com ${displayName(it)} terminar…" } ?: "Esperando a conexão terminar…"))
            Status.BLOCKED_BY_READER ->
                root.addView(noticeCard("O leitor está lendo pelo Bluetooth. Pare a captura para usar a smartbox.", error = false))
            Status.SCANNING, Status.IDLE ->
                root.addView(statusText(if (count == 0) "Procurando smartboxes…" else "$count por perto · procurando…"))
        }
    }

    private fun boxCard(box: NearbySmartbox, myFarmId: Int, farms: List<Pair<Int, String>>): View {
        val adv = box.adv
        val name = displayName(adv.moduleId)
        val flags = decodeStateFlags(adv.state)
        val actions = FarmRules.allowedActions(adv, myFarmId.toLong())
        val c = cardLayout()

        c.addView(TextView(this).apply {
            this.text = name
            textSize = 16f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })
        c.addView(mutedText("${box.rssi} dBm · ${adv.pending} leitura(s) para baixar · ${adv.moduleId}"))

        val badges = buildList {
            if (flags.configMode) add("Modo configuração: ${configReasonText(adv.reason)}")
            if (flags.memoryFull) add("Memória cheia")
            if (!flags.realTime) add("Sem hora")
            add(if (flags.wifi) "Envio por Wi-Fi" else "Envio por Bluetooth")
        }
        c.addView(mutedText(badges.joinToString(" · ")))

        val busyHere = busy?.takeIf { it.first == adv.moduleId }
        when {
            actions.needsAppUpdate ->
                c.addView(noticeCard("Smartbox no protocolo ${adv.proto}; este app fala o 2. Atualize o app.", error = true))
            busyHere != null -> c.addView(statusText(busyHere.second))
            actions.download -> c.addView(fullButton("Baixar agora", enabled = busy == null) { download(box, myFarmId) })
            actions.register -> c.addView(fullButton("Cadastrar nesta fazenda", enabled = busy == null) { assign(box, myFarmId, farms) })
            actions.transferBlockedByWifi -> c.addView(mutedText(
                "Está em Wi-Fi e é ${FarmLabel.of(adv.farmId, farms).of}. Para trazer para esta fazenda, configure com uma chave de ativação desta fazenda.",
            ))
            adv.farmId != 0L && adv.farmId != myFarmId.toLong() -> c.addView(mutedText(
                "É ${FarmLabel.of(adv.farmId, farms).of}. Só um gestor pode transferir, pelo SpaceVis Field.",
            ))
        }
        // Configurar e Diagnóstico: minha ou sem fazenda (seção 14.3), cada um na largura toda.
        if (actions.configure && busyHere == null) {
            c.addView(fullButton("Configurar", enabled = busy == null) {
                startActivity(SmartboxConfigureActivity.intent(this, box.address, adv.moduleId))
            })
        }
        if (actions.diagnose && busyHere == null) {
            c.addView(fullButton("Diagnóstico", enabled = busy == null) {
                startActivity(SmartboxDiagnosticActivity.intent(this, box.address, adv.moduleId))
            })
        }
        return c
    }

    private fun assign(box: NearbySmartbox, myFarmId: Int, farms: List<Pair<Int, String>>) {
        val name = displayName(box.adv.moduleId)
        busy = box.adv.moduleId to "Gravando a fazenda em $name…"
        lastResult = null
        rerender()
        lifecycleScope.launch {
            val outcome = SmartboxActions.assignFarm(applicationContext, box, myFarmId)
            busy = null
            lastResult = when (outcome) {
                is SmartboxActions.AssignOutcome.Registered -> {
                    val where = FarmLabel.of(outcome.farmId, farms).inFarm
                    if (outcome.backendError == null) "$name cadastrada $where." to false
                    else "$name cadastrada $where. No banco: ${outcome.backendError} (tenta de novo no próximo envio)." to true
                }
                is SmartboxActions.AssignOutcome.OtherFarm ->
                    "$name já é ${FarmLabel.of(outcome.farmId, farms).of}. Só um gestor pode transferir, pelo SpaceVis Field." to true
                is SmartboxActions.AssignOutcome.WifiFarm ->
                    "$name está em Wi-Fi e é ${FarmLabel.of(outcome.farmId, farms).of}. Para trazer para esta fazenda, configure com uma chave de ativação desta fazenda." to true
                is SmartboxActions.AssignOutcome.Failed -> outcome.message to true
            }
            rerender()
        }
    }

    private fun download(box: NearbySmartbox, myFarmId: Int) {
        val name = displayName(box.adv.moduleId)
        busy = box.adv.moduleId to "Baixando $name…"
        lastResult = null
        rerender()
        lifecycleScope.launch {
            val (msg, isError) = SmartboxActions.downloadNow(applicationContext, box, myFarmId) { received, remaining ->
                runOnUiThread {
                    busy = box.adv.moduleId to ("Baixando $name: $received" + (remaining?.let { " de ${received + it}" } ?: ""))
                    rerender()
                }
            }
            busy = null
            lastResult = msg to isError
            rerender()
        }
    }

    private fun rerender() = render(SmartboxScanner.nearby.value, SmartboxScanner.status.value, SmartboxConnection.activeModuleId.value)

    // ── Peças de layout ─────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun section(title: String) {
        root.addView(TextView(this).apply {
            this.text = title.uppercase()
            textSize = 11f
            setTextColor(green)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, dp(16), 0, dp(8))
        })
    }

    private fun statusText(t: String) = TextView(this).apply {
        this.text = t
        textSize = 14f
        setTextColor(ink)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun mutedText(t: String) = TextView(this).apply {
        this.text = t
        textSize = 13f
        setTextColor(muted)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun cardLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(12)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(card)
            setStroke(dp(1), border)
            cornerRadius = dp(8).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) }
    }

    /** Card de aviso/erro na largura toda, texto sempre visível (nada de toast). */
    private fun noticeCard(t: String, error: Boolean) = TextView(this).apply {
        this.text = t
        textSize = 14f
        setTextColor(ink)
        val pad = dp(12)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(if (error) redBg else amberBg)
            setStroke(dp(1), if (error) redBorder else amberBorder)
            cornerRadius = dp(8).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4); bottomMargin = dp(8) }
    }

    private fun fullButton(label: String, enabled: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        this.text = label
        isEnabled = enabled
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            .apply { topMargin = dp(4) }
    }

    companion object {
        private const val REQ_PERMISSIONS = 4101
        private const val RENDER_INTERVAL_MS = 1_000L
    }
}
