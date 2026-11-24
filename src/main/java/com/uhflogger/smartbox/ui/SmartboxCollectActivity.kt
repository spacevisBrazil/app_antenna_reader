package com.uhflogger.smartbox.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.uhflogger.R
import com.uhflogger.smartbox.ble.displayName
import com.uhflogger.ui.Brand
import com.uhflogger.smartbox.collect.AutoCollectRules
import com.uhflogger.smartbox.collect.SmartboxCollector
import com.uhflogger.smartbox.collect.SmartboxCollector.Phase
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Tela de progresso da coleta com o leitor PARADO (regras do Igor). Trava a
 * navegação: o operador não precisa decidir nada, só esperar ou tocar o único
 * botão, "Parar e ler brincos".
 *
 * O botão NÃO tem lógica própria: devolve [RESULT_READ_TAGS] e a MainActivity
 * faz o mesmo que o "Iniciar leitura" (startCaptureYieldingSmartbox: a smartbox
 * termina o lote em mãos, desconecta, e a captura começa). Nada se perde.
 *
 * Fecha sozinha quando a coleta termina (depois de ~3 s de "Pronto") ou falha
 * (falha é silenciosa: só telemetria).
 */
class SmartboxCollectActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var bar: ProgressBar
    private lateinit var count: TextView
    private lateinit var eta: TextView
    private lateinit var stopButton: Button
    private val nf = java.text.NumberFormat.getIntegerInstance(Locale("pt", "BR"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Smartbox"
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        // Trava a navegação: voltar não sai daqui.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        val pad = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(c(R.color.sv_background))
        }
        title = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(c(R.color.sv_foreground))
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            minimumHeight = dp(24)
            progressTintList = android.content.res.ColorStateList.valueOf(c(R.color.sv_primary))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(c(R.color.sv_muted))
        }
        count = TextView(this).apply { textSize = 18f; setTextColor(c(R.color.sv_foreground)) }
        eta = TextView(this).apply { textSize = 14f; setTextColor(c(R.color.sv_muted_foreground)) }
        stopButton = Button(this).apply {
            text = "Parar e ler brincos"
            textSize = 17f
            Brand.styleButton(this, Brand.ButtonKind.PRIMARY)
            setOnClickListener {
                isEnabled = false
                setResult(RESULT_READ_TAGS)
                finish()
            }
        }
        val full = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(title, full)
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).apply { topMargin = dp(24) })
        root.addView(count, LinearLayout.LayoutParams(full).apply { topMargin = dp(12) })
        root.addView(eta, LinearLayout.LayoutParams(full).apply { topMargin = dp(4) })
        root.addView(stopButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(40) })
        setContentView(Brand.withTopBar(this, root))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SmartboxCollector.state.collect { render(it) }
            }
        }
    }

    private fun render(s: SmartboxCollector.State) {
        val name = s.moduleId?.let { displayName(it) } ?: "Smartbox"
        when (s.phase) {
            Phase.CONNECTING, Phase.DOWNLOADING -> {
                title.text = "Baixando leituras do bebedouro $name"
                val total = s.total ?: 0
                bar.isIndeterminate = total <= 0
                if (total > 0) bar.progress = ((s.received.toLong() * 1000) / total).toInt().coerceIn(0, 1000)
                count.text = if (total > 0) "${nf.format(s.received)} de ${nf.format(total)} leituras" else "${nf.format(s.received)} leituras"
                val remaining = (total - s.received).coerceAtLeast(0)
                eta.text = if (s.ratePerSecond > 0 && remaining > 0) {
                    "Falta cerca de ${AutoCollectRules.formatEta(remaining.toLong() / s.ratePerSecond)}"
                } else ""
            }
            Phase.DONE -> {
                title.text = "Pronto"
                bar.isIndeterminate = false
                bar.progress = 1000
                count.text = "${nf.format(s.doneReadings)} leituras guardadas, enviando ao servidor"
                eta.text = ""
                stopButton.isEnabled = false
            }
            // Terminou, falhou (silencioso) ou o leitor pegou o rádio: fecha sozinha.
            else -> if (!isFinishing) finish()
        }
    }

    private fun c(id: Int) = Brand.color(this, id)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** "Parar e ler brincos": a MainActivity inicia a captura (que faz a smartbox ceder). */
        const val RESULT_READ_TAGS = Activity.RESULT_FIRST_USER + 1
    }
}
