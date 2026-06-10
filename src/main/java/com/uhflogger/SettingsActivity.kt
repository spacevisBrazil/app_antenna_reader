package com.uhflogger

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private val GREEN  = Color.parseColor("#2E7D32")
    private val BG     = Color.parseColor("#FAFAFA")
    private val CARD   = Color.parseColor("#FFFFFF")
    private val BORDER = Color.parseColor("#E0E0E0")
    private val TEXT   = Color.parseColor("#111111")
    private val MUTED  = Color.parseColor("#888888")
    private val LABEL  = Color.parseColor("#2E7D32")

    // Root layout reference — needed to show/hide Winnix section
    private lateinit var root: LinearLayout
    // Winnix-only container — shown/hidden based on antenna type selection
    private lateinit var winnixSection: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Configurações"

        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(BG)
        }
        scroll.addView(root)
        setContentView(scroll)

        // --- AUTO-SAVE --------------------------------------------------
        buildSectionLabel(root, "AUTO-SAVE")
        buildRow(root, "Por quantidade de tags",
            "A cada ${SettingsManager.getAutoSaveTags(this)} tags",
            ROW_TAGS) {
            showNumberDialog("Auto-save por quantidade",
                "Entre 1.000 e 100.000 tags",
                SettingsManager.getAutoSaveTags(this).toString(),
                1_000, 100_000) { v ->
                SettingsManager.setAutoSaveTags(this, v)
                updateRowValue(root, ROW_TAGS, "A cada $v tags")
                toast("Salvo")
            }
        }
        buildRow(root, "Por tempo",
            "A cada ${SettingsManager.getAutoSaveMinutes(this)} min",
            ROW_MINUTES) {
            showNumberDialog("Auto-save por tempo",
                "Entre 1 e 60 minutos",
                SettingsManager.getAutoSaveMinutes(this).toString(),
                1, 60) { v ->
                SettingsManager.setAutoSaveMinutes(this, v)
                updateRowValue(root, ROW_MINUTES, "A cada $v min")
                toast("Salvo")
            }
        }

        // --- LOCALIZAÇÃO ------------------------------------------------
        root.addView(spacer(16))
        buildSectionLabel(root, "LOCALIZAÇÃO")
        val locLabel = if (SettingsManager.getLocationMode(this) == SettingsManager.LOCATION_MODE_GNSS)
            "Somente GNSS" else "GNSS + Rede"
        buildRow(root, "Modo de localização", locLabel, ROW_LOCATION) {
            showLocationDialog(root)
        }

        // --- ANTENA -----------------------------------------------------
        root.addView(spacer(16))
        buildSectionLabel(root, "ANTENA")

        val currentType  = SettingsManager.getAntennaType(this)
        val antennaLabel = if (currentType == SettingsManager.ANTENNA_TYPE_WINNIX) "Winnix HYM750E" else "Jietong"
        buildRow(root, "Tipo de antena", antennaLabel, ROW_ANTENNA_TYPE) {
            showAntennaTypeDialog(root)
        }

        // Winnix-only section — visible only when Winnix is selected
        winnixSection = buildWinnixSection()
        root.addView(winnixSection)
        winnixSection.visibility = if (currentType == SettingsManager.ANTENNA_TYPE_WINNIX)
            View.VISIBLE else View.GONE

        // --- RESTAURAR --------------------------------------------------
        root.addView(spacer(24))
        buildRestoreButton(root)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // -------------------------------------------------------------------------
    // Winnix section
    // -------------------------------------------------------------------------

    private fun buildWinnixSection(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        section.addView(spacer(8))
        buildSectionLabel(section, "CONFIGURAÇÃO WINNIX")

        // Antenna count (1-4)
        val antCount = SettingsManager.getWinnixAntCount(this)
        buildRow(section, "Número de antenas",
            "$antCount antena${if (antCount > 1) "s" else ""}",
            ROW_WINNIX_ANT_COUNT) {
            showWinnixAntCountDialog(section)
        }

        // Power
        val power = SettingsManager.getWinnixPowerDbm(this)
        buildRow(section, "Potência",
            "$power dBm",
            ROW_WINNIX_POWER) {
            showNumberDialog("Potência Winnix",
                "Entre 5 e 33 dBm",
                SettingsManager.getWinnixPowerDbm(this).toString(),
                5, 33) { v ->
                SettingsManager.setWinnixPowerDbm(this, v)
                updateRowValue(section, ROW_WINNIX_POWER, "$v dBm")
                toast("Salvo")
            }
        }

        // Working time
        val workMs = SettingsManager.getWinnixWorkingMs(this)
        buildRow(section, "Tempo por antena",
            "${workMs}ms",
            ROW_WINNIX_WORKING) {
            showNumberDialog("Tempo por antena (ms)",
                "Entre 10 e 65535ms (padrão: 100ms)",
                SettingsManager.getWinnixWorkingMs(this).toString(),
                10, 65535) { v ->
                SettingsManager.setWinnixWorkingMs(this, v)
                updateRowValue(section, ROW_WINNIX_WORKING, "${v}ms")
                toast("Salvo")
            }
        }

        // Inventory mode
        val invMode = SettingsManager.getWinnixInventoryMode(this)
        buildRow(section, "Modo de inventário",
            SettingsManager.winnixInventoryModeLabel(invMode),
            ROW_WINNIX_INV_MODE) {
            showWinnixInventoryModeDialog(section)
        }

        return section
    }

    // -------------------------------------------------------------------------
    // Dialogs
    // -------------------------------------------------------------------------

    private fun showAntennaTypeDialog(parent: LinearLayout) {
        val current = SettingsManager.getAntennaType(this)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = dp(20)
            setPadding(p, dp(8), p, dp(8))
        }

        val radioGroup = RadioGroup(this)
        val rbJietong = RadioButton(this).apply {
            text      = "Jietong"
            id        = 20
            setTextColor(TEXT)
            textSize  = 14f
            isChecked = current == SettingsManager.ANTENNA_TYPE_JIETONG
        }
        val rbWinnix = RadioButton(this).apply {
            text      = "Winnix HYM750E"
            id        = 21
            setTextColor(TEXT)
            textSize  = 14f
            isChecked = current == SettingsManager.ANTENNA_TYPE_WINNIX
        }
        radioGroup.addView(rbJietong)
        radioGroup.addView(rbWinnix)
        wrapper.addView(radioGroup)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Tipo de antena")
            .setView(wrapper)
            .setPositiveButton("Salvar") { _, _ ->
                val type = if (radioGroup.checkedRadioButtonId == 21)
                    SettingsManager.ANTENNA_TYPE_WINNIX else SettingsManager.ANTENNA_TYPE_JIETONG
                SettingsManager.setAntennaType(this, type)
                val label = if (type == SettingsManager.ANTENNA_TYPE_WINNIX) "Winnix HYM750E" else "Jietong"
                updateRowValue(parent, ROW_ANTENNA_TYPE, label)
                // Show/hide Winnix section
                winnixSection.visibility = if (type == SettingsManager.ANTENNA_TYPE_WINNIX)
                    View.VISIBLE else View.GONE
                toast("Salvo: $label")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showWinnixInventoryModeDialog(parent: LinearLayout) {
        val current = SettingsManager.getWinnixInventoryMode(this)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = dp(20)
            setPadding(p, dp(8), p, dp(8))
        }

        data class ModeOption(val id: Int, val mode: Int, val label: String, val desc: String)
        val options = listOf(
            ModeOption(40, SettingsManager.WINNIX_INV_MODE_MULTITAG,
                "Multi-tag", "Alta precisão para grande quantidade de tags"),
            ModeOption(41, SettingsManager.WINNIX_INV_MODE_FAST,
                "Fast read", "Máxima velocidade de leitura — ideal para tags em movimento"),
            ModeOption(42, SettingsManager.WINNIX_INV_MODE_ADAPTIVE,
                "Adaptive", "Modo adaptativo — recomendado para a maioria dos cenários")
        )

        val radioGroup = RadioGroup(this)
        for (opt in options) {
            val rb = RadioButton(this).apply {
                id        = opt.id
                text      = "${opt.label} — ${opt.desc}"
                setTextColor(TEXT)
                textSize  = 13f
                isChecked = current == opt.mode
            }
            radioGroup.addView(rb)
        }
        wrapper.addView(radioGroup)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Modo de inventário (Winnix)")
            .setView(wrapper)
            .setPositiveButton("Salvar") { _, _ ->
                val selected = options.firstOrNull { it.id == radioGroup.checkedRadioButtonId }
                    ?: options[1] // default Fast read
                SettingsManager.setWinnixInventoryMode(this, selected.mode)
                updateRowValue(parent, ROW_WINNIX_INV_MODE, selected.label)
                toast("Salvo: ${selected.label}")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showWinnixAntCountDialog(parent: LinearLayout) {
        val current = SettingsManager.getWinnixAntCount(this)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = dp(20)
            setPadding(p, dp(8), p, dp(8))
        }

        val radioGroup = RadioGroup(this)
        for (i in 1..4) {
            val rb = RadioButton(this).apply {
                text      = "$i antena${if (i > 1) "s" else ""}"
                id        = 30 + i
                setTextColor(TEXT)
                textSize  = 14f
                isChecked = current == i
            }
            radioGroup.addView(rb)
        }
        wrapper.addView(radioGroup)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Número de antenas (Winnix)")
            .setView(wrapper)
            .setPositiveButton("Salvar") { _, _ ->
                val checked = radioGroup.checkedRadioButtonId
                val count   = if (checked in 31..34) checked - 30 else 1
                SettingsManager.setWinnixAntCount(this, count)
                updateRowValue(parent, ROW_WINNIX_ANT_COUNT,
                    "$count antena${if (count > 1) "s" else ""}")
                toast("Salvo: $count antenas")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showNumberDialog(title: String, hint: String, current: String,
                                 min: Int, max: Int, onSave: (Int) -> Unit) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = dp(20)
            setPadding(p, dp(8), p, 0)
        }
        val tvHint = TextView(this).apply {
            text     = hint
            textSize = 12f
            setTextColor(MUTED)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current)
            textSize = 18f
            gravity  = Gravity.CENTER
            setTextColor(TEXT)
            setBackgroundColor(Color.WHITE)
            selectAll()
        }
        wrapper.addView(tvHint)
        wrapper.addView(input)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(title)
            .setView(wrapper)
            .setPositiveButton("Salvar") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                when {
                    v == null -> toast("Valor inválido")
                    v < min   -> toast("Mínimo: $min")
                    v > max   -> toast("Máximo: $max")
                    else      -> onSave(v)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showLocationDialog(parent: LinearLayout) {
        val current = SettingsManager.getLocationMode(this)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = dp(20)
            setPadding(p, dp(8), p, dp(8))
        }

        val radioGroup = RadioGroup(this)
        val rbHybrid = RadioButton(this).apply {
            text      = "GNSS + Rede  (satélites + Wi-Fi/4G)"
            id        = 10
            setTextColor(TEXT)
            textSize  = 14f
            isChecked = current == SettingsManager.LOCATION_MODE_HYBRID
        }
        val rbGnss = RadioButton(this).apply {
            text      = "Somente GNSS  (apenas satélites)"
            id        = 11
            setTextColor(TEXT)
            textSize  = 14f
            isChecked = current == SettingsManager.LOCATION_MODE_GNSS
        }
        radioGroup.addView(rbHybrid)
        radioGroup.addView(rbGnss)
        wrapper.addView(radioGroup)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Modo de localização")
            .setView(wrapper)
            .setPositiveButton("Salvar") { _, _ ->
                val mode = if (radioGroup.checkedRadioButtonId == 10)
                    SettingsManager.LOCATION_MODE_HYBRID else SettingsManager.LOCATION_MODE_GNSS
                SettingsManager.setLocationMode(this, mode)
                val label = if (mode == SettingsManager.LOCATION_MODE_GNSS)
                    "Somente GNSS" else "GNSS + Rede"
                updateRowValue(parent, ROW_LOCATION, label)
                toast("Salvo: $label")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Restore defaults
    // -------------------------------------------------------------------------

    private fun buildRestoreButton(parent: LinearLayout) {
        val btn = Button(this).apply {
            text              = "Restaurar padrões"
            textSize          = 14f
            setTextColor(MUTED)
            background        = borderDrawable()
            stateListAnimator = null
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity,
                    android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("Restaurar padrões")
                    .setMessage("Deseja restaurar todas as configurações?")
                    .setPositiveButton("Restaurar") { _, _ ->
                        SettingsManager.setAutoSaveTags(this@SettingsActivity,
                            SettingsManager.DEFAULT_AUTO_SAVE_TAGS)
                        SettingsManager.setAutoSaveMinutes(this@SettingsActivity,
                            SettingsManager.DEFAULT_AUTO_SAVE_MINUTES)
                        SettingsManager.setLocationMode(this@SettingsActivity,
                            SettingsManager.DEFAULT_LOCATION_MODE)
                        SettingsManager.setAntennaType(this@SettingsActivity,
                            SettingsManager.DEFAULT_ANTENNA_TYPE)
                        SettingsManager.setWinnixAntCount(this@SettingsActivity,
                            SettingsManager.DEFAULT_WINNIX_ANT_COUNT)
                        SettingsManager.setWinnixPowerDbm(this@SettingsActivity,
                            SettingsManager.DEFAULT_WINNIX_POWER_DBM)
                        SettingsManager.setWinnixWorkingMs(this@SettingsActivity,
                            SettingsManager.DEFAULT_WINNIX_WORKING_MS)
                        SettingsManager.setWinnixInventoryMode(this@SettingsActivity,
                            SettingsManager.DEFAULT_WINNIX_INVENTORY_MODE)

                        updateRowValue(parent, ROW_TAGS,
                            "A cada ${SettingsManager.DEFAULT_AUTO_SAVE_TAGS} tags")
                        updateRowValue(parent, ROW_MINUTES,
                            "A cada ${SettingsManager.DEFAULT_AUTO_SAVE_MINUTES} min")
                        updateRowValue(parent, ROW_LOCATION, "GNSS + Rede")
                        updateRowValue(parent, ROW_ANTENNA_TYPE, "Jietong")

                        // Hide Winnix section — default is Jietong
                        winnixSection.visibility = View.GONE

                        toast("Configurações restauradas")
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            layoutParams = lp
        }
        parent.addView(btn)
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun buildSectionLabel(parent: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text     = text
            textSize      = 10f
            setTextColor(LABEL)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.1f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        parent.addView(tv)
    }

    private fun buildRow(
        parent : LinearLayout,
        title  : String,
        value  : String,
        valueId: Int,
        onClick: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(CARD)
            background  = borderDrawable()
            val pad     = dp(14)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        val textCol = LinearLayout(this).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(this).apply {
            this.text = title
            textSize  = 14f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.NORMAL)
        }
        val tvValue = TextView(this).apply {
            this.text = value
            textSize  = 12f
            id        = valueId
            setTextColor(MUTED)
        }
        textCol.addView(tvTitle)
        textCol.addView(tvValue)
        val arrow = TextView(this).apply {
            text     = "›"
            textSize = 18f
            setTextColor(BORDER)
        }
        card.addView(textCol)
        card.addView(arrow)
        parent.addView(card)
    }

    private fun borderDrawable(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(CARD)
            setStroke(dp(1), BORDER)
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun updateRowValue(parent: LinearLayout, id: Int, text: String) {
        parent.findViewById<TextView>(id)?.text = text
    }

    private fun spacer(dpVal: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(dpVal))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val ROW_TAGS            = 2001
        private const val ROW_MINUTES         = 2002
        private const val ROW_LOCATION        = 2003
        private const val ROW_ANTENNA_TYPE    = 2004
        private const val ROW_WINNIX_ANT_COUNT= 2005
        private const val ROW_WINNIX_POWER    = 2006
        private const val ROW_WINNIX_WORKING  = 2007
        private const val ROW_WINNIX_INV_MODE = 2008
    }
}