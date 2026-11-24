package com.uhflogger.smartbox.ui

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.uhflogger.R
import com.uhflogger.backend.BackendSettings
import com.uhflogger.ui.Brand
import com.uhflogger.smartbox.SmartboxActions
import com.uhflogger.smartbox.ble.SmartboxMode
import com.uhflogger.smartbox.ble.SmartboxTarget
import com.uhflogger.smartbox.ble.configReasonText
import com.uhflogger.smartbox.ble.displayName
import com.uhflogger.smartbox.collect.SmartboxCollector
import com.uhflogger.smartbox.domain.DiagFormat
import com.uhflogger.smartbox.domain.FarmLabel
import com.uhflogger.smartbox.radio.RadioDecision
import com.uhflogger.smartbox.radio.ReaderRadio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Diagnóstico (seção 13 do guia), tela de técnico. Conexão curta: conecta,
 * GET_DIAG, desconecta. Reiniciar abre uma conexão nova. Padrão de fábrica NÃO
 * existe no UHF Logger (só gestor, no SpaceVis Field: FarmRules.IS_MANAGER).
 *
 * Layout DT50: botões na largura toda, nunca na linha de texto; erros em card.
 */
class SmartboxDiagnosticActivity : AppCompatActivity() {

    // Tokens SpaceVis (res/values/colors.xml); lazy porque o Context só
    // existe depois do attach.
    private val ink by lazy { Brand.color(this, R.color.sv_foreground) }
    private val muted by lazy { Brand.color(this, R.color.sv_muted_foreground) }
    private val toneColor by lazy {
        mapOf(
            DiagFormat.Tone.OK to Brand.color(this, R.color.sv_primary),
            DiagFormat.Tone.WARN to Brand.color(this, R.color.sv_warning_text),
            DiagFormat.Tone.BAD to Brand.color(this, R.color.sv_error),
            DiagFormat.Tone.NEUTRAL to ink,
        )
    }

    private lateinit var root: LinearLayout
    private lateinit var target: SmartboxTarget
    private var releaseCollector: (() -> Unit)? = null

    private var result: SmartboxActions.DiagResult? = null
    private var busyText: String? = null
    private var message: Pair<String, Boolean>? = null   // texto, éErro
    private var raw = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        val moduleId = intent.getStringExtra(EXTRA_MODULE_ID)
        if (address == null || moduleId == null) { finish(); return }
        target = SmartboxTarget(address, moduleId)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Diagnóstico ${displayName(moduleId)}"
        val scroll = ScrollView(this).apply { setBackgroundColor(Brand.color(this@SmartboxDiagnosticActivity, R.color.sv_background)) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root)
        setContentView(Brand.withTopBar(this, scroll))
        read()
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun read() {
        if (ReaderRadio.decision() == RadioDecision.BLOCK) { render(); return }
        busyText = "Conectando e lendo…"
        message = null
        render()
        lifecycleScope.launch {
            try {
                result = SmartboxActions.readDiagnostic(applicationContext, target)
                if (result?.text.isNullOrBlank()) message = "A smartbox não devolveu nenhum campo." to false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Não leu o diagnóstico: ${SmartboxActions.describe(e)}. Chegue mais perto e tente de novo." to true
            }
            busyText = null
            render()
        }
    }

    private fun confirmReboot() {
        AlertDialog.Builder(this, Brand.DIALOG_THEME)
            .setTitle("Reiniciar a smartbox?")
            .setMessage("Ela desliga e liga de novo (leva uns segundos). Não muda a configuração e não apaga as leituras.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Reiniciar") { _, _ -> reboot() }
            .show()
    }

    private fun reboot() {
        busyText = "Reiniciando ${displayName(target.moduleId)}…"
        message = null
        render()
        lifecycleScope.launch {
            message = try {
                SmartboxActions.reboot(applicationContext, target)
                result = null   // o diagnóstico mostrado é de antes do reinício
                "Reiniciando. Espere uns 10 s e toque em Ler de novo." to false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Não reiniciou: ${SmartboxActions.describe(e)}. Chegue mais perto e tente de novo." to true
            }
            busyText = null
            render()
        }
    }

    private fun render() {
        root.removeAllViews()
        if (ReaderRadio.decision() == RadioDecision.BLOCK) {
            root.addView(card("O leitor está lendo pelo Bluetooth. Pare a captura para usar a smartbox.", error = false))
            return
        }
        busyText?.let { root.addView(text(it, 15f, ink)) }
        message?.let { (m, err) -> root.addView(card(m, err)) }

        val r = result
        if (r != null) {
            val c = r.challenge
            val farms = BackendSettings.getFarms(this)
            val myFarm = BackendSettings.getFarmId(this).toLong()
            val mode = if (c.mode == SmartboxMode.OPERATION) "Operação" else "Configuração: ${configReasonText(c.reason)}"
            val farm = if (c.farmId == 0L) "Sem fazenda" else FarmLabel.of(c.farmId, farms).title
            root.addView(text(displayName(c.moduleId), 18f, ink, bold = true))
            root.addView(text("Firmware ${c.firmware} · protocolo ${c.proto} · ${c.moduleId}", 13f, muted))
            root.addView(text("$mode · ${c.pending} leitura(s) para baixar · $farm", 13f, muted))

            // Reiniciar só em caixa minha ou sem fazenda (o CHALLENGE é o que vale).
            val mineOrFree = c.farmId == 0L || c.farmId == myFarm
            val enabled = busyText == null
            if (mineOrFree) {
                root.addView(button("Configurar", enabled) {
                    startActivity(SmartboxConfigureActivity.intent(this, target.address, target.moduleId))
                })
            }
            root.addView(button("Ler de novo", enabled) { read() })
            root.addView(button(if (raw) "Ver legível" else "Ver texto cru", enabled) { raw = !raw; render() })
            if (mineOrFree) root.addView(button("Reiniciar smartbox", enabled, danger = true) { confirmReboot() })
            // Padrão de fábrica: só gestor (FarmRules.IS_MANAGER = false), fica no SpaceVis Field.

            section("ESTADO INTERNO")
            if (raw) {
                root.addView(text(r.text.ifBlank { "(vazio)" }, 12f, ink).apply { typeface = Typeface.MONOSPACE })
            } else {
                DiagFormat.readable(r.text, r.readAtMs).forEach { row ->
                    root.addView(text(row.label, 12f, muted).apply { setPadding(0, dp(8), 0, 0) })
                    if (row.value.isNotEmpty()) root.addView(text(row.value, 15f, toneColor.getValue(row.tone)))
                }
            }
        } else if (busyText == null) {
            root.addView(button("Tentar de novo", true) { read() })
        }
    }

    // ── Peças ───────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun section(t: String) = root.addView(text(t, 11f, muted, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(0, dp(20), 0, dp(4))
    })

    private fun card(t: String, error: Boolean) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(ink)
        val pad = dp(12)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(Brand.color(this@SmartboxDiagnosticActivity, if (error) R.color.sv_error_soft else R.color.sv_warning_soft))
            setStroke(dp(1), Brand.color(this@SmartboxDiagnosticActivity, if (error) R.color.sv_error else R.color.sv_warning))
            cornerRadius = dp(10).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4); bottomMargin = dp(8) }
    }

    private fun button(label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isEnabled = enabled
        Brand.styleButton(this, if (danger) Brand.ButtonKind.DANGER else Brand.ButtonKind.SECONDARY)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) }
    }

    companion object {
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_MODULE_ID = "module_id"

        fun intent(context: Context, address: String, moduleId: String): Intent =
            Intent(context, SmartboxDiagnosticActivity::class.java)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_MODULE_ID, moduleId)
    }
}
