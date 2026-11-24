package com.uhflogger.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.uhflogger.R

/**
 * Identidade visual SpaceVis para as telas montadas em Kotlin (Settings,
 * Backend, Google, Smartbox). O layout XML da tela principal usa os mesmos
 * tokens direto de `res/values/colors.xml`; aqui é a mesma fonte, nunca hex
 * solto — assim uma troca de cor da plataforma é uma linha no colors.xml.
 */
object Brand {

    enum class ButtonKind { PRIMARY, SECONDARY, DANGER }

    /** Tema dos AlertDialogs: botões no --primary, Inter, fundo --card. */
    val DIALOG_THEME: Int = R.style.UHFLogger_Dialog

    @ColorInt
    fun color(ctx: Context, @ColorRes id: Int): Int = ContextCompat.getColor(ctx, id)

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    fun rounded(
        ctx: Context,
        @ColorInt fill: Int,
        radiusDp: Int,
        @ColorInt stroke: Int? = null,
        strokeDp: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        if (stroke != null) setStroke(dp(ctx, strokeDp), stroke)
    }

    /** Card padrão da plataforma: --card, borda --border, raio 16. */
    fun card(ctx: Context, @ColorInt stroke: Int = color(ctx, R.color.sv_border)): GradientDrawable =
        rounded(ctx, color(ctx, R.color.sv_card), 16, stroke)

    /**
     * Campo de texto: --card, raio 10, borda sv_field_border (ou --primary com
     * foco). Não usa --input: claro demais para campo branco sobre fundo claro.
     */
    fun field(ctx: Context, focused: Boolean = false): GradientDrawable =
        if (focused) rounded(ctx, color(ctx, R.color.sv_card), 10, color(ctx, R.color.sv_primary), 2)
        else rounded(ctx, color(ctx, R.color.sv_card), 10, color(ctx, R.color.sv_field_border))

    /**
     * Botão com raio 10 e estado desabilitado no --muted (o mesmo do mockup:
     * "Iniciar leitura" apagado durante a leitura). Substitui o fundo — vale
     * para `Button(this)` criado em código, que não passa pelo MaterialButton.
     */
    fun styleButton(button: Button, kind: ButtonKind) {
        val ctx = button.context
        val (fill, stroke, text) = when (kind) {
            ButtonKind.PRIMARY -> Triple(R.color.sv_primary, null, R.color.sv_primary_foreground)
            ButtonKind.DANGER -> Triple(R.color.sv_error, null, R.color.sv_error_foreground)
            ButtonKind.SECONDARY -> Triple(R.color.sv_card, R.color.sv_border, R.color.sv_foreground)
        }
        val enabled = rounded(ctx, color(ctx, fill), 10, stroke?.let { color(ctx, it) })
        val disabled = if (kind == ButtonKind.SECONDARY) {
            rounded(ctx, color(ctx, R.color.sv_card), 10, color(ctx, R.color.sv_border))
        } else {
            rounded(ctx, color(ctx, R.color.sv_muted), 10)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), disabled)
            addState(intArrayOf(), enabled)
        }
        button.background = ripple(ctx, states)
        button.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(color(ctx, R.color.sv_muted_foreground), color(ctx, text)),
            )
        )
        button.isAllCaps = false
        button.stateListAnimator = null
    }

    private fun ripple(ctx: Context, content: Drawable): Drawable = RippleDrawable(
        ColorStateList.valueOf(color(ctx, R.color.sv_muted_foreground) and 0x33FFFFFF),
        content,
        rounded(ctx, color(ctx, R.color.sv_card), 10),
    )

    /**
     * Barra do topo no verde da sidebar com o logo oficial (sem recolorir nem
     * redimensionar fora da proporção: 160×21 dp, igual ao mockup).
     * [trailing] fica à direita — texto de contexto ou um botão.
     */
    fun topBar(activity: Activity, trailing: View? = null): LinearLayout {
        val ctx: Context = activity
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(ctx, R.color.sv_sidebar))
            minimumHeight = ctx.resources.getDimensionPixelSize(R.dimen.sv_topbar_height)
            setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14))
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.spacevis_logo)
                contentDescription = "SpaceVis"
                scaleType = ImageView.ScaleType.FIT_START
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 160), dp(ctx, 21))
            })
            addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            if (trailing != null) addView(trailing)
        }
    }

    /** Texto discreto para o lado direito da barra ("3 por perto · procurando…"). */
    fun topBarText(ctx: Context, text: CharSequence = ""): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(color(ctx, R.color.sv_sidebar_foreground))
    }

    /** Monta a tela: barra do topo + [content] ocupando o resto. */
    fun withTopBar(activity: Activity, content: View, trailing: View? = null): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(activity, R.color.sv_background))
            addView(topBar(activity, trailing), ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
}
