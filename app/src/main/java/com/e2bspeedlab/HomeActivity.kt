package com.e2bspeedlab

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny mode launcher now that SpeedLab can read both model output and books. */
class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.rgb(8, 10, 14)
        window.navigationBarColor = Color.rgb(8, 10, 14)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(8, 10, 14))
        }
        root.addView(TextView(this).apply {
            text = "E2B SPEEDLAB"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "HUMAN-READ OPTIMIZED OUTPUT"
            textSize = 10f
            letterSpacing = 0.14f
            setTextColor(Color.rgb(125, 138, 154))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))

        root.addView(modeButton("AI FLASH\nGemma 4 E2B", ReadActivityV4::class.java), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(92)).apply {
            topMargin = dp(28)
        })
        root.addView(modeButton("AOZORA BOOK\n原文 / AI 70% / 40% / 20% 圧縮", AozoraBookActivityV2::class.java), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(92)).apply {
            topMargin = dp(12)
        })
        root.addView(TextView(this).apply {
            text = "BOOKは青空文庫の公式公開データを直接使用 • AI圧縮は端末内E2B"
            textSize = 11f
            setTextColor(Color.rgb(110, 123, 138))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))

        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(dp(22) + safe.left, dp(16) + safe.top, dp(22) + safe.right, dp(16) + safe.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun modeButton(label: String, activity: Class<out Activity>) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(35, 41, 50))
        setOnClickListener { startActivity(Intent(this@HomeActivity, activity)) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
