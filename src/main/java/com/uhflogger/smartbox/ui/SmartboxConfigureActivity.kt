package com.uhflogger.smartbox.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.uhflogger.backend.BackendSettings
import com.uhflogger.smartbox.ConfigSession
import com.uhflogger.smartbox.ConfigTemplates
import com.uhflogger.smartbox.SmartboxActions
import com.uhflogger.smartbox.ble.SmartboxMode
import com.uhflogger.smartbox.ble.SmartboxTarget
import com.uhflogger.smartbox.ble.Status
import com.uhflogger.smartbox.ble.configReasonText
import com.uhflogger.smartbox.ble.decodeStateFlags
import com.uhflogger.smartbox.ble.displayName
import com.uhflogger.smartbox.collect.SmartboxCollector
import com.uhflogger.smartbox.domain.ConfigForm
import com.uhflogger.smartbox.domain.ConfigForms
import com.uhflogger.smartbox.domain.Envio
import com.uhflogger.smartbox.domain.FarmLabel
import com.uhflogger.smartbox.domain.LoadedConfig
import com.uhflogger.smartbox.domain.TemplateParseException
import com.uhflogger.smartbox.domain.WifiOptions
import com.uhflogger.smartbox.radio.RadioDecision
import com.uhflogger.smartbox.radio.ReaderRadio
import com.uhflogger.smartbox.sync.SmartboxApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela de técnico "Configurar" (seção 12 do guia). Uma sessão longa
 * ([ConfigSession]) para ler, procurar redes e salvar.
 *
 * Layout DT50: botões na largura toda ou lado a lado SEMPRE com peso (nunca
 * estouram a largura), nunca na mesma linha de texto flexível; erros em card.
 */
class SmartboxConfigureActivity : AppCompatActivity() {

    private val ink = Color.parseColor("#111111")
    private val muted = Color.parseColor("#666666")
    private val green = Color.parseColor("#2E7D32")
    private val red = Color.parseColor("#C62828")

    private lateinit var target: SmartboxTarget
    private lateinit var top: LinearLayout       // cabeçalho + avisos (redesenhado à vontade)
    private lateinit var formBox: LinearLayout   // formulário (montado uma vez por leitura)
    private var releaseCollector: (() -> Unit)? = null

    private var session: ConfigSession? = null
    private var loaded: LoadedConfig? = null
    private var form: ConfigForm = ConfigForms.factoryForm()
    private var networks: List<WifiOptions.Option> = emptyList()

    private enum class Phase { CONNECTING, READY, SAVED, CLOSED, ERROR }
    private var phase = Phase.CONNECTING
    private var phaseText = "Conectando e lendo a configuração…"
    private var closedTitle = ""
    /** Marcado ANTES do SET_CONFIG: se cair sem resposta, a tela sabe avisar que pode ter salvo. */
    private var maybeSaved = false
    private var savedOk = false
    private var savedNotes = emptyList<String>()
    private var notice: Pair<String, Boolean>? = null   // texto, éErro
    private var busy: String? = null

    // Partes dinâmicas do formulário.
    private var suppress = false
    private lateinit var wifiBox: LinearLayout
    private lateinit var windowFields: LinearLayout
    private lateinit var networkList: LinearLayout
    private lateinit var passwordField: EditText
    private lateinit var ssidField: EditText
    private lateinit var openCheck: CheckBox
    private lateinit var windowHint: TextView
    private lateinit var saveButton: Button
    private lateinit var scanBoxButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        val moduleId = intent.getStringExtra(EXTRA_MODULE_ID)
        if (address == null || moduleId == null) { finish(); return }
        target = SmartboxTarget(address, moduleId)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Configurar ${displayName(moduleId)}"
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#FAFAFA")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        formBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(top)
        root.addView(formBox)
        scroll.addView(root)
        setContentView(scroll)
        connect(keepTyped = false)
    }

    override fun onStart() {
        super.onStart()
        releaseCollector = SmartboxCollector.hold()
    }

    override fun onStop() {
        releaseCollector?.invoke()
        releaseCollector = null
        super.onStop()
    }

    override fun onDestroy() {
        session?.close()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Sessão ──────────────────────────────────────────────────────────────

    /** Abre (ou reabre) a sessão. [keepTyped]: Reconectar mantém o que foi digitado e troca só a base. */
    private fun connect(keepTyped: Boolean) {
        if (ReaderRadio.decision() == RadioDecision.BLOCK) {
            phase = Phase.ERROR
            phaseText = "O leitor está lendo pelo Bluetooth. Pare a captura para usar a smartbox."
            renderTop()
            return
        }
        phase = Phase.CONNECTING
        phaseText = if (keepTyped) "Reconectando…" else "Conectando e lendo a configuração…"
        notice = null
        renderTop()
        lifecycleScope.launch {
            try {
                val s = ConfigSession.open(applicationContext, target, lifecycleScope) { reason, msg -> onClosed(reason, msg) }
                session = s
                val l = s.readConfig()
                loaded = l
                if (!keepTyped) form = l.form
                maybeSaved = false
                savedOk = false
                phase = Phase.READY
                renderTop()
                buildForm()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = Phase.ERROR
                phaseText = "Não conectou: ${SmartboxActions.describe(e)}\nChegue mais perto e tente de novo."
                renderTop()
            }
        }
    }

    private fun onClosed(reason: ConfigSession.CloseReason, message: String?) {
        session = null
        if (isFinishing || isDestroyed) return
        if (savedOk) {
            closedTitle = "Smartbox reiniciando"
            phaseText = "A configuração nova vale a partir de agora."
        } else {
            closedTitle = if (reason == ConfigSession.CloseReason.IDLE) "Conexão fechada" else "Conexão caiu"
            val why = message?.let { "$it\n" }.orEmpty()
            phaseText = why + if (maybeSaved) {
                "Caiu no meio do salvar: a smartbox PODE ter gravado e estar reiniciando. Reconecte e confira antes de salvar de novo."
            } else {
                "Nada foi salvo. O que você digitou continua aqui."
            }
        }
        if (reason == ConfigSession.CloseReason.USER && savedOk) {
            finish()
            return
        }
        phase = Phase.CLOSED
        renderTop()
        refresh()
    }

    // ── Cabeçalho e avisos ──────────────────────────────────────────────────

    private fun renderTop() {
        top.removeAllViews()
        val c = session?.challenge
        if (c != null) {
            val farms = BackendSettings.getFarms(this)
            val flags = decodeStateFlags(c.state)
            val farm = when {
                c.farmId == 0L -> "Sem fazenda"
                else -> FarmLabel.of(c.farmId, farms).title + if (loaded?.farmSrc == 1 || flags.farmFromKey) " (da chave)" else " (do app)"
            }
            val mode = if (c.mode == SmartboxMode.OPERATION) "Operação" else "Configuração: ${configReasonText(c.reason)}"
            top.addView(text(displayName(c.moduleId), 18f, ink, bold = true))
            top.addView(text("${c.moduleId} · firmware ${c.firmware}", 13f, muted))
            top.addView(text("$mode · envio por ${if (flags.wifi) "Wi-Fi" else "Bluetooth"} · $farm", 13f, muted))
            if (!flags.wifi) top.addView(text("Nome da smartbox no aparelho: ${displayName(c.moduleId)}", 13f, muted))
        }
        when (phase) {
            Phase.CONNECTING -> top.addView(text(phaseText, 15f, ink))
            Phase.ERROR -> {
                top.addView(card(phaseText, error = true))
                if (ReaderRadio.decision() != RadioDecision.BLOCK) top.addView(button("Tentar de novo") { connect(keepTyped = loaded != null) })
            }
            Phase.CLOSED -> {
                top.addView(card("$closedTitle\n$phaseText", error = !savedOk))
                if (!savedOk) top.addView(button("Reconectar") { connect(keepTyped = true) })
            }
            Phase.SAVED -> {
                top.addView(card((listOf("Salvo na smartbox", "Ela reinicia com a configuração nova quando o aparelho desconectar.") + savedNotes).joinToString("\n"), error = false))
                renderSaveTemplate()
                top.addView(button("Desconectar e reiniciar") { session?.close() })
            }
            Phase.READY -> Unit
        }
        busy?.let { top.addView(text(it, 14f, ink)) }
        notice?.let { (m, err) -> top.addView(card(m, err)) }
    }

    private fun renderSaveTemplate() {
        top.addView(section("GUARDAR COMO MODELO"))
        val name = edit(ConfigForms.suggestedTemplateName(form), InputType.TYPE_CLASS_TEXT)
        top.addView(name)
        top.addView(button("Guardar como modelo") { saveTemplate(name.text.toString()) })
    }

    // ── Formulário ──────────────────────────────────────────────────────────

    private fun buildForm() {
        formBox.removeAllViews()
        suppress = true

        // Modelos (12.4): fábrica + salvos. Botões lado a lado sempre com peso.
        formBox.addView(section("COMEÇAR DE UM MODELO"))
        formBox.addView(button("Padrão de fábrica") {
            form = ConfigForms.applyFactoryDefaults(form)
            notice = "Padrão de fábrica aplicado. Confira os campos e toque em Salvar." to false
            renderTop(); buildForm()
        })
        ConfigTemplates.list(this).forEach { tpl ->
            formBox.addView(row(
                button("Aplicar ${tpl.name}") { applyTemplate(tpl) } to 1f,
                button("✕") { confirmRemoveTemplate(tpl) } to 0f,
            ))
        }

        // Envio.
        formBox.addView(section("ENVIO DAS LEITURAS"))
        val group = RadioGroup(this)
        val rbWifi = RadioButton(this).apply { text = "Wi-Fi: internet direto para a plataforma"; id = View.generateViewId() }
        val rbBt = RadioButton(this).apply { text = "Bluetooth: aparelho coletor"; id = View.generateViewId() }
        group.addView(rbWifi); group.addView(rbBt)
        group.check(if (form.envio == Envio.WIFI) rbWifi.id else rbBt.id)
        group.setOnCheckedChangeListener { _, id ->
            if (suppress) return@setOnCheckedChangeListener
            form = form.copy(envio = if (id == rbWifi.id) Envio.WIFI else Envio.BLUETOOTH)
            refresh()
        }
        formBox.addView(group)

        // Rede Wi-Fi + plataforma (só em Wi-Fi; em Bluetooth some e não é enviado).
        wifiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wifiBox.addView(section("REDE WI-FI"))
        wifiBox.addView(label("Nome da rede (SSID)"))
        ssidField = edit(form.ssid, InputType.TYPE_CLASS_TEXT).also { f ->
            f.onChange { v ->
                // Digitar à mão descarta os bytes originais.
                form = form.copy(ssid = v, ssidEncoded = null); refresh()
            }
        }
        wifiBox.addView(ssidField)
        scanBoxButton = button("Procurar pela smartbox") { scanFromSmartbox() }
        wifiBox.addView(scanBoxButton)
        wifiBox.addView(button("Procurar redes (pelo aparelho)") { scanFromPhone() })
        networkList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wifiBox.addView(networkList)
        openCheck = check("Essa rede não tem senha (rede aberta)", form.check("open_network")) { form = form.withCheck("open_network", it); refresh() }
        wifiBox.addView(openCheck)
        wifiBox.addView(label("Senha da rede"))
        passwordField = edit(form.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD).also { f ->
            f.onChange { v -> form = form.copy(password = v); refresh() }
        }
        wifiBox.addView(passwordField)
        wifiBox.addView(section("PLATAFORMA SPACEVIS"))
        wifiBox.addView(label("Chave de ativação (em branco = mantém a atual)"))
        val act = edit(ConfigForms.formatActCode(form.actCode), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        act.onChange { v ->
            form = form.copy(actCode = v)
            // Formata enquanto digita (XXXX-XXXX-…). Sem maxLength: cortaria chave válida.
            val f = ConfigForms.formatActCode(v)
            if (f != v && !suppress) { suppress = true; act.setText(f); act.setSelection(f.length); suppress = false }
            refresh()
        }
        wifiBox.addView(act)
        formBox.addView(wifiBox)

        // Leitor RFID.
        formBox.addView(section("LEITOR RFID"))
        for (i in 1..4) formBox.addView(check("Antena $i", form.check("ant$i")) { form = form.withCheck("ant$i", it); refresh() })
        formBox.addView(numField("Potência (5 a 33 dBm)", "power"))
        formBox.addView(numField("Intervalo entre varreduras (10 a 3600 s)", "sweep_sec"))
        formBox.addView(numField("Bloqueio da tag após envio (60 a 86400 s)", "valid_sec"))
        formBox.addView(check("Modo adaptativo com proteção térmica", form.check("adaptive")) { form = form.withCheck("adaptive", it); refresh() })

        // Modo Window: some quando desligado, mas é validado e enviado (o firmware valida sempre).
        formBox.addView(section("MODO WINDOW"))
        formBox.addView(check("Ligado (horários em UTC = Brasília + 3 h)", form.check("w_enabled")) { form = form.withCheck("w_enabled", it); refresh() })
        windowFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        windowFields.addView(label("Window 1 · Início (UTC): hora, min, s"))
        windowFields.addView(row(numInput("w1_sh", 2) to 1f, numInput("w1_sm", 2) to 1f, numInput("w1_ss", 2) to 1f))
        windowFields.addView(label("Window 1 · Fim (UTC): hora, min, s"))
        windowFields.addView(row(numInput("w1_eh", 2) to 1f, numInput("w1_em", 2) to 1f, numInput("w1_es", 2) to 1f))
        windowHint = text("", 13f, muted)
        windowFields.addView(windowHint)
        windowFields.addView(numField("Window 1 · Tempo lendo (s)", "w1_on"))
        windowFields.addView(numField("Window 1 · Tempo parado (s)", "w1_off"))
        windowFields.addView(check("Window 1 · Aplicar ciclos ao ESP32 também", form.check("w1_esp32")) { form = form.withCheck("w1_esp32", it); refresh() })
        windowFields.addView(numField("Window 2 · Tempo lendo (s)", "w2_on"))
        windowFields.addView(numField("Window 2 · Tempo parado (s)", "w2_off"))
        windowFields.addView(check("Window 2 · Aplicar ciclos ao ESP32 também", form.check("w2_esp32")) { form = form.withCheck("w2_esp32", it); refresh() })
        formBox.addView(windowFields)

        saveButton = button("Salvar") { onSaveClicked() }.apply {
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(green)
        }
        formBox.addView(saveButton)
        suppress = false
        renderNetworks()
        refresh()
    }

    /** Atualiza só o que depende do formulário, sem remontar (não tira o foco do campo). */
    private fun refresh() {
        if (!::saveButton.isInitialized) return
        val l = loaded ?: return
        wifiBox.visibility = if (form.envio == Envio.WIFI) View.VISIBLE else View.GONE
        windowFields.visibility = if (form.check("w_enabled")) View.VISIBLE else View.GONE
        scanBoxButton.visibility = if (session?.challenge?.mode == SmartboxMode.CONFIG) View.VISIBLE else View.GONE
        passwordField.isEnabled = !form.check("open_network")
        passwordField.hint = if (ConfigForms.canKeepPassword(l.form, form)) "em branco = mantém a salva" else "senha da rede (mín. 8)"

        val w = ConfigForms.windowTimes(form)
        val start = ConfigForms.brasiliaHint(form.num("w1_sh"), form.num("w1_sm"))
        val end = ConfigForms.brasiliaHint(form.num("w1_eh"), form.num("w1_em"))
        windowHint.text = buildString {
            if (start != null && end != null) append("Brasília: $start às $end")
            if (w.d1 != null && w.d2 != null) {
                if (isNotEmpty()) append(" · ")
                append("Window 1 dura ${ConfigForms.durationHint(w.d1)}, Window 2 ${ConfigForms.durationHint(w.d2)}")
            }
        }

        val changes = ConfigForms.summarizeChanges(l.form, form).lines.size
        saveButton.text = if (changes == 0) "Salvar" else "Salvar ($changes ${if (changes == 1) "alteração" else "alterações"})"
        saveButton.isEnabled = phase == Phase.READY && busy == null
    }

    // ── Redes Wi-Fi (12.3) ──────────────────────────────────────────────────

    private fun renderNetworks() {
        networkList.removeAllViews()
        networks.forEach { opt ->
            val label = "${opt.ssid} · ${opt.rssi} dBm" + if (opt.open) " · aberta" else ""
            networkList.addView(button(label, enabled = opt.compatible) { chooseNetwork(opt) })
            opt.incompatibleReason?.let { networkList.addView(text(it, 12f, red)) }
        }
    }

    /** Escolher da lista: bytes exatos do SSID, open = da rede, e LIMPA a senha. */
    private fun chooseNetwork(opt: WifiOptions.Option) {
        form = form.copy(ssid = opt.ssid, ssidEncoded = opt.ssidEncoded, password = "").withCheck("open_network", opt.open)
        suppress = true
        ssidField.setText(opt.ssid)
        passwordField.setText("")
        openCheck.isChecked = opt.open
        suppress = false
        refresh()
    }

    private fun scanFromSmartbox() {
        val s = session ?: return
        busy = "Procurando redes pela smartbox…"
        renderTop(); refresh()
        lifecycleScope.launch {
            try {
                networks = WifiOptions.fromSmartbox(s.wifiScan())
                notice = if (networks.isEmpty()) "Nenhuma rede Wi-Fi encontrada. A smartbox não achou nenhuma rede (pode ser falha do scan). Tente de novo." to true else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = "Procurar redes: ${SmartboxActions.describe(e)}" to true
            }
            busy = null
            renderTop(); renderNetworks(); refresh()
        }
    }

    @Suppress("DEPRECATION")
    private fun scanFromPhone() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notice = "Sem a permissão de localização o Android não mostra as redes Wi-Fi. Permita a localização para o UHF Logger." to true
            renderTop()
            return
        }
        busy = "Procurando redes pelo aparelho…"
        renderTop(); refresh()
        lifecycleScope.launch {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                try { wm.startScan() } catch (_: Exception) {}
                delay(3_000)
                val results = withContext(Dispatchers.Default) {
                    wm.scanResults.map { WifiOptions.PhoneScan(it.SSID.orEmpty(), it.frequency, it.capabilities.orEmpty(), it.level) }
                }
                networks = WifiOptions.fromPhone(results)
                notice = if (networks.isEmpty()) "O aparelho não achou nenhuma rede Wi-Fi. Confira se o Wi-Fi está ligado e tente de novo." to true else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = "Procurar redes: ${e.message ?: e.javaClass.simpleName}" to true
            }
            busy = null
            renderTop(); renderNetworks(); refresh()
        }
    }

    // ── Modelos (12.4) ──────────────────────────────────────────────────────

    private fun applyTemplate(tpl: ConfigTemplates.Stored) {
        try {
            val (t, password) = ConfigTemplates.load(this, tpl)
            form = ConfigForms.applyTemplate(t, password)
            networks = emptyList()
            notice = ("Modelo ${tpl.name} aplicado. Confira os campos e toque em Salvar." +
                if (form.envio == Envio.WIFI) "\nDigite a chave de ativação DESTA smartbox: o modelo não guarda a chave." else "") to false
            renderTop(); buildForm()
        } catch (_: TemplateParseException) {
            alert("Modelo estragado", "Não deu para ler este modelo. Apague e salve de novo.")
        }
    }

    private fun confirmRemoveTemplate(tpl: ConfigTemplates.Stored) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Apagar o modelo ${tpl.name}?")
            .setMessage("As smartboxes já configuradas não mudam.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar") { _, _ -> ConfigTemplates.remove(this, tpl.id); buildForm() }
            .show()
    }

    private fun saveTemplate(rawName: String) {
        val name = rawName.trim().ifEmpty { ConfigForms.suggestedTemplateName(form) }
        // A senha só vai se for Wi-Fi, não aberta e digitada nesta tela.
        val password = form.password.takeIf { form.envio == Envio.WIFI && !form.check("open_network") && it.isNotEmpty() }
        val doSave = {
            ConfigTemplates.save(this, name, form, password)
            notice = ("Modelo $name guardado." +
                if (form.envio == Envio.WIFI && !form.check("open_network") && password == null)
                    " A senha do Wi-Fi não foi digitada agora, então ele vai sem senha." else "") to false
            renderTop()
        }
        val existing = ConfigTemplates.findByName(ConfigTemplates.list(this), name)
        if (existing == null) { doSave(); return }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Modelo já existe")
            .setMessage("Já existe o modelo ${existing.name}. Quer trocar ele por esta configuração?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Substituir") { _, _ -> doSave() }
            .show()
    }

    // ── Salvar (12.5) ───────────────────────────────────────────────────────

    private fun onSaveClicked() {
        val l = loaded ?: return
        ConfigForms.validate(l, form)?.let { alert("Corrija antes de salvar", it); return }
        val changes = ConfigForms.summarizeChanges(l.form, form)
        if (changes.lines.isEmpty()) { alert("Salvar na smartbox?", "Nada mudou."); return }

        val myFarm = BackendSettings.getFarmId(this).toLong()
        val payload = try {
            ConfigForms.serialize(l, form, myFarm.takeIf { it > 0 })
        } catch (e: Exception) {
            alert("Corrija antes de salvar", e.message ?: "Configuração inválida.")
            return
        }
        val body = (if (changes.toWifi) listOf("ATENÇÃO: no modo Wi-Fi a smartbox para de falar com o aparelho depois de reiniciar.", "") else emptyList()) + changes.lines
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(if (changes.toWifi) "Passar para Wi-Fi?" else "Salvar na smartbox?")
            .setMessage(body.joinToString("\n"))
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(if (changes.toWifi) "Passar para Wi-Fi" else "Salvar") { _, _ -> save(payload, myFarm) }
            .show()
        // Ação destrutiva: tira a smartbox do Bluetooth.
        if (changes.toWifi) dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(red)
    }

    private fun save(payload: String, myFarm: Long) {
        val s = session ?: return
        busy = "Salvando na smartbox…"
        maybeSaved = true
        renderTop(); refresh()
        lifecycleScope.launch {
            try {
                val outcome = s.save(payload)
                busy = null
                val farms = BackendSettings.getFarms(this@SmartboxConfigureActivity)
                val notes = mutableListOf<String>()
                when (outcome) {
                    is ConfigSession.SaveOutcome.Refused -> {
                        maybeSaved = false
                        alert("A smartbox recusou", outcome.text)
                        renderTop(); refresh()
                        return@launch
                    }
                    is ConfigSession.SaveOutcome.Saved -> {
                        val farm = outcome.farm
                        when {
                            myFarm <= 0 -> notes += "O app não está ativado numa fazenda: a smartbox não foi cadastrada. Ative o app e cadastre na tela Buscar."
                            farm != null && farm.farmId == myFarm -> {
                                notes += "Cadastrada ${FarmLabel.of(myFarm, farms).inFarm}."
                                val backend = withContext(Dispatchers.IO) { SmartboxApi.registerModule(applicationContext, myFarm.toInt(), target.moduleId) }
                                if (backend is SmartboxApi.Result.Failed) notes += "No banco: ${backend.message} (tenta de novo no próximo envio)."
                            }
                        }
                    }
                    is ConfigSession.SaveOutcome.SavedFarmRefused -> {
                        val of = FarmLabel.of(outcome.farm.farmId, farms)
                        notes += if (outcome.status == Status.FARM_FROM_KEY) {
                            "A configuração foi salva, mas ela continua ${of.inFarm}: em Wi-Fi a fazenda vem da chave de ativação."
                        } else {
                            "A configuração foi salva, mas ela continua ${of.inFarm}. Só um gestor pode transferir, pelo SpaceVis Field."
                        }
                    }
                }
                if (form.envio == Envio.WIFI) {
                    notes += "Ela vai tentar conectar na rede ${form.ssid}; se não conectar em 30 s, volta ao modo configuração (motivo 3)."
                }
                savedOk = true
                savedNotes = notes
                phase = Phase.SAVED
                renderTop(); refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Caiu sem resposta: o onClosed avisa "pode ter salvo" (maybeSaved continua true).
                busy = null
                alert("Não salvou", SmartboxActions.describe(e))
                renderTop(); refresh()
            }
        }
    }

    // ── Peças de layout ─────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun alert(title: String, msg: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun label(t: String) = text(t, 12f, muted).apply { setPadding(0, dp(8), 0, 0) }

    private fun section(t: String) = text(t, 11f, green, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun card(t: String, error: Boolean) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(ink)
        val pad = dp(12)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(Color.parseColor(if (error) "#FFEBEE" else "#FFF8E1"))
            setStroke(dp(1), Color.parseColor(if (error) "#E53935" else "#FFB300"))
            cornerRadius = dp(8).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4); bottomMargin = dp(8) }
    }

    private fun button(label: String, enabled: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        isEnabled = enabled
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) }
    }

    /** Lado a lado DENTRO da largura: peso > 0 divide; peso 0 = largura fixa de 56 dp. */
    private fun row(vararg items: Pair<View, Float>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEach { (v, weight) ->
            v.layoutParams = if (weight > 0f) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            else LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(v)
        }
    }

    private fun edit(value: String, type: Int) = EditText(this).apply {
        setText(value)
        inputType = type
        setSingleLine()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun EditText.onChange(cb: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { if (!suppress) cb(s?.toString().orEmpty()) }
    })

    private fun numInput(key: String, maxLen: Int = 6) = edit(form.num(key), InputType.TYPE_CLASS_NUMBER).apply {
        filters = arrayOf(InputFilter.LengthFilter(maxLen))
        onChange { v -> form = form.withNum(key, v); refresh() }
    }

    private fun numField(title: String, key: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title))
        addView(numInput(key))
    }

    private fun check(label: String, value: Boolean, cb: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        isChecked = value
        setOnCheckedChangeListener { _, v -> if (!suppress) cb(v) }
    }

    companion object {
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_MODULE_ID = "module_id"

        fun intent(context: Context, address: String, moduleId: String): Intent =
            Intent(context, SmartboxConfigureActivity::class.java)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_MODULE_ID, moduleId)
    }
}
