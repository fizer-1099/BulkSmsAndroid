package com.example.bulksms

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class SimpleBarChart(ctx: Context) : View(ctx) {
    var labels: List<String> = emptyList()
    var ok: List<Int> = emptyList()
    var fail: List<Int> = emptyList()
    var textColor: Int = Color.GRAY
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val n = labels.size
        if (n == 0 || ok.size < n || fail.size < n) return
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        var maxV = 1
        for (i in 0 until n) maxV = maxOf(maxV, ok[i] + fail[i])
        val topPad = 18 * d
        val bottomPad = 24 * d
        val chartH = h - topPad - bottomPad
        val slot = w / n
        val barW = slot * 0.5f
        p.textSize = 11 * d
        p.textAlign = Paint.Align.CENTER
        for (i in 0 until n) {
            val cx = slot * i + slot / 2
            val okH = chartH * ok[i] / maxV
            val failH = chartH * fail[i] / maxV
            val base = topPad + chartH
            p.color = Color.rgb(37, 99, 235)
            c.drawRoundRect(cx - barW / 2, base - okH, cx + barW / 2, base, 5 * d, 5 * d, p)
            p.color = Color.rgb(220, 38, 38)
            c.drawRect(cx - barW / 2, base - okH - failH, cx + barW / 2, base - okH, p)
            p.color = textColor
            c.drawText(labels[i], cx, h - 6 * d, p)
            val total = ok[i] + fail[i]
            if (total > 0) c.drawText(total.toString(), cx, base - okH - failH - 4 * d, p)
        }
        p.color = textColor
        p.strokeWidth = d
        c.drawLine(0f, topPad + chartH, w, topPad + chartH, p)
    }
}
