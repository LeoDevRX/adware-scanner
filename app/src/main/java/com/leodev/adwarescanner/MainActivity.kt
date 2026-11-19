package com.leodev.adwarescanner

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scanButton: Button
    private lateinit var systemBox: CheckBox
    private lateinit var cleanBox: CheckBox

    private var lastResult: ScanResult? = null
    private var scanning = false
    private var hasScanned = false

    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), 0)
        }

        root.addView(TextView(this).apply {
            text = "Adware Scanner"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Checks your apps and settings for signs of adware. Nothing leaves your phone."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            alpha = 0.75f
            setPadding(0, dp(4), 0, dp(12))
        })

        scanButton = Button(this).apply {
            text = "Scan phone"
            setOnClickListener { startScan() }
        }
        root.addView(scanButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val options = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        systemBox = CheckBox(this).apply {
            text = "Include system apps"
            setOnCheckedChangeListener { _, _ -> if (hasScanned) startScan() }
        }
        cleanBox = CheckBox(this).apply {
            text = "Show clean apps"
            setOnCheckedChangeListener { _, _ -> lastResult?.let { render(it) } }
        }
        options.addView(systemBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        options.addView(cleanBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(options)

        progress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        status = TextView(this).apply {
            text = "Tap \"Scan phone\" to start."
            setPadding(0, dp(8), 0, dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        root.addView(status)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // Re-scan after coming back from an uninstall / settings screen
        if (hasScanned) startScan()
    }

    private fun startScan() {
        if (scanning) return
        scanning = true
        scanButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Scanning…"
        val includeSystem = systemBox.isChecked
        Thread {
            val result = runCatching { Scanner(applicationContext).scan(includeSystem) }
            runOnUiThread {
                scanning = false
                hasScanned = true
                scanButton.isEnabled = true
                scanButton.text = "Scan again"
                progress.visibility = View.GONE
                result.onSuccess { lastResult = it; render(it) }
                    .onFailure { status.text = "Scan failed: ${it.message}" }
            }
        }.start()
    }

    private fun render(r: ScanResult) {
        content.removeAllViews()

        val high = r.apps.count { it.level == Level.HIGH }
        val medium = r.apps.count { it.level == Level.MEDIUM }
        val low = r.apps.count { it.level == Level.LOW }
        val warnings = r.device.count { it.warn }
        status.text = buildString {
            append("Scanned ${r.scannedCount} apps. ")
            append(
                when {
                    high > 0 -> "$high high-risk, $medium medium, $low low."
                    medium > 0 -> "No high-risk apps. $medium medium, $low low."
                    else -> "Nothing serious found. $low low-risk."
                }
            )
            if (warnings > 0) append(" $warnings phone setting(s) to check.")
        }

        // Phone-wide checks
        content.addView(sectionTitle("Phone checks"))
        for (c in r.device) content.addView(deviceRow(c))

        // Apps
        content.addView(sectionTitle("Apps (riskiest first)"))
        val shown = if (cleanBox.isChecked) r.apps else r.apps.filter { it.level != Level.CLEAN }
        if (shown.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "No apps with red flags."
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        for (a in shown) content.addView(appRow(a))

        content.addView(TextView(this).apply {
            text = "A flag is a warning sign, not proof. Launchers, password managers, keyboards and " +
                "accessibility tools legitimately need some of these permissions. If you don't recognise " +
                "a high-risk app or didn't install it, uninstall it."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.7f
            setPadding(0, dp(16), 0, 0)
        })
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun deviceRow(c: DeviceCheck): View {
        val box = card(if (c.warn) Color.argb(60, 255, 152, 0) else Color.argb(25, 128, 128, 128))
        box.addView(TextView(this).apply {
            text = (if (c.warn) "⚠ " else "✓ ") + c.title
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        box.addView(TextView(this).apply {
            text = c.detail
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.85f
        })
        return box
    }

    private fun appRow(a: AppReport): View {
        val tint = when (a.level) {
            Level.HIGH -> Color.argb(70, 229, 57, 53)
            Level.MEDIUM -> Color.argb(60, 255, 152, 0)
            Level.LOW -> Color.argb(45, 253, 216, 53)
            Level.CLEAN -> Color.argb(25, 128, 128, 128)
        }
        val box = card(tint)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            runCatching { setImageDrawable(packageManager.getApplicationIcon(a.packageName)) }
        }
        header.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })

        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(TextView(this).apply {
            text = a.label + if (a.isSystem) "  (system)" else ""
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        names.addView(TextView(this).apply {
            text = a.packageName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.65f
        })
        header.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(TextView(this).apply {
            text = "${a.level.name}\n${a.score}"
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        box.addView(header)

        for (f in a.findings) {
            box.addView(TextView(this).apply {
                text = "• ${f.text}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(4), dp(2), 0, 0)
            })
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        buttons.addView(Button(this).apply {
            text = "App info"
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!a.isSystem) {
            buttons.addView(Button(this).apply {
                text = "Uninstall"
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.packageName}")))
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        }
        box.addView(buttons)
        return box
    }

    private fun card(tint: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(tint)
            setStroke(dp(1), if (dark) Color.argb(50, 255, 255, 255) else Color.argb(30, 0, 0, 0))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
