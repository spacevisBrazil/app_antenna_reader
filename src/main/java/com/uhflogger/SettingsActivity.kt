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
    private val RED    = Color.parseColor("#C62828")
    private val BG     = Color.parseColor("#FAFAFA")
    private val CARD   = Color.parseColor("#FFFFFF")
    private val BORDER = Color.parseColor("#E0E0E0")
    private val TEXT   = Color.parseColor("#111111")
    private val MUTED  = Color.parseColor("#888888")
    private val LABEL  = Color.parseColor("#2E7D32")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Configurações"

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(BG)
        }
        scroll.addView(root)
        setContentView(scroll)

        buildSectionLabel(root, "AUTO-SAVE")
        buildRow(root, "Por quantidade de tags",
            "A cada ${SettingsManager.getAutoSaveTags(this)} tags",
            ROW_TAGS) { showNumberDialog("Auto-save por quantidade",
            "Entre 1.000 e 100.000 tags", SettingsManager.getAutoSaveTags(this).toString(),
            1_000, 100_000) { v ->
            SettingsManager.setAutoSaveTags(this, v)
            updateRowValue(root, ROW_TAGS, "A cada $v tags")
            toast("Salvo")
        }
        }
        buildRow(root, "Por tempo",
            "A cada ${SettingsManager.getAutoSaveMinutes(this)} min",
            ROW_MINUTES) { showNumberDialog("Auto-save por tempo",
            "Entre 1 e 60 minutos", SettingsManager.getAutoSaveMinutes(this).toString(),
            1, 60) { v ->
            SettingsManager.setAutoSaveMinutes(this, v)
            updateRowValue(root, ROW_MINUTES, "A cada $v min")
            toast("Salvo")
        }
        }

        root.addView(spacer(16))
        buildSectionLabel(root, "LOCALIZAÇÃO")
        val locLabel = if (SettingsManager.getLocationMode(this) == SettingsManager.LOCATION_MODE_GNSS)
            "Somente GNSS" else "GNSS + Rede"
        buildRow(root, "Modo de localização", locLabel, ROW_LOCATION) {
            showLocationDialog(root)
        }

        root.addView(spacer(24))
        buildRestoreButton(root)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // -------------------------------------------------------------------------
    // Componentes de UI
    // -------------------------------------------------------------------------

    private fun buildSectionLabel(parent: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize  = 10f
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
        parent   : LinearLayout,
        title    : String,
        value    : String,
        valueId  : Int,
        onClick  : () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(CARD)
            background  = borderDrawable()
            val pad = dp(14)
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
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(this).apply {
            text     = title
            textSize = 14f
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

    private fun buildRestoreButton(parent: LinearLayout) {
        val btn = Button(this).apply {
            text = "Restaurar padrões"
            textSize = 14f
            setTextColor(MUTED)
            background = borderDrawable()
            stateListAnimator = null
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("Restaurar padrões")
                    .setMessage("Deseja restaurar todas as configurações?")
                    .setPositiveButton("Restaurar") { _, _ ->
                        SettingsManager.setAutoSaveTags(this@SettingsActivity, SettingsManager.DEFAULT_AUTO_SAVE_TAGS)
                        SettingsManager.setAutoSaveMinutes(this@SettingsActivity, SettingsManager.DEFAULT_AUTO_SAVE_MINUTES)
                        SettingsManager.setLocationMode(this@SettingsActivity, SettingsManager.DEFAULT_LOCATION_MODE)
                        updateRowValue(parent, ROW_TAGS, "A cada ${SettingsManager.DEFAULT_AUTO_SAVE_TAGS} tags")
                        updateRowValue(parent, ROW_MINUTES, "A cada ${SettingsManager.DEFAULT_AUTO_SAVE_MINUTES} min")
                        updateRowValue(parent, ROW_LOCATION, "GNSS + Rede")
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
    // Diálogos
    // -------------------------------------------------------------------------

    private fun showNumberDialog(title: String, hint: String, current: String,
                                 min: Int, max: Int, onSave: (Int) -> Unit) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = dp(20)
            setPadding(p, dp(8), p, 0)
        }
        val tvHint = TextView(this).apply {
            text = hint
            textSize = 12f
            setTextColor(MUTED)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current)
            textSize = 18f
            gravity = Gravity.CENTER
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
                    v == null  -> toast("Valor inválido")
                    v < min    -> toast("Mínimo: $min")
                    v > max    -> toast("Máximo: $max")
                    else       -> onSave(v)
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
            text = "GNSS + Rede  (satélites + Wi-Fi/4G)"
            id   = 10
            setTextColor(TEXT)
            textSize  = 14f
            isChecked = current == SettingsManager.LOCATION_MODE_HYBRID
        }
        val rbGnss = RadioButton(this).apply {
            text = "Somente GNSS  (apenas satélites)"
            id   = 11
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
    // Helpers
    // -------------------------------------------------------------------------

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

    private fun spacer(dpVal: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(dpVal))
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val ROW_TAGS     = 2001
        private const val ROW_MINUTES  = 2002
        private const val ROW_LOCATION = 2003
    }
}