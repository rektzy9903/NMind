package com.claudecodesetup.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.claudecodesetup.data.AppPreferences
import java.net.HttpURLConnection
import java.net.URL

class PreviewActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var webView: WebView
    private lateinit var errorView: View
    private lateinit var loadingView: View
    private lateinit var portChip: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var currentPort = 5173
    private var retryCount = 0
    private val MAX_RETRIES = 10
    private val retryRunnable = Runnable { checkAndLoad() }

    // Common dev-server port presets
    private val presets = listOf(
        "Vite" to 5173,
        "Next" to 3000,
        "Angular" to 4200,
        "Flask" to 5000,
        "Django" to 8000
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)
        currentPort = prefs.getPreviewPort()

        val root = buildUI()
        setContentView(root)

        supportActionBar?.hide()
        showPortPicker(onConfirm = { port ->
            currentPort = port
            prefs.setPreviewPort(port)
            portChip.text = "localhost:$port"
            retryCount = 0
            checkAndLoad()
        })
    }

    // ── UI build ──────────────────────────────────────────────────────────────

    private fun buildUI(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0C0C0F"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top bar
        val topBar = buildTopBar()
        root.addView(topBar)

        // WebView (added to the frame below — never directly to root, or the
        // second addView throws IllegalStateException "already has a parent")
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame) runOnUiThread { showError() }
                }
            }
        }

        // Error overlay (hidden by default)
        errorView = buildErrorView()

        // Loading overlay (shown while probing the port, so the user never sees
        // a blank white WebView or a premature "not running" error during retries)
        loadingView = buildLoadingView()

        // Frame stacks the WebView, the loading overlay and the error overlay;
        // frame fills the remaining vertical space under the top bar.
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        frame.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        frame.addView(loadingView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        frame.addView(errorView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(frame)

        return root
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E22"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp, 0, 8.dp, 0)
        }

        // Back button
        val back = TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(Color.parseColor("#F0F0F2"))
            setPadding(8.dp, 0, 8.dp, 0)
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, 48.dp
        ))

        // Port chip (amber, tappable → picker)
        portChip = TextView(this).apply {
            text = "localhost:$currentPort"
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#FF8C42"))
            setBackgroundColor(Color.parseColor("#22FF8C42"))
            setPadding(10.dp, 4.dp, 10.dp, 4.dp)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(4.dp, 0, 0, 0) }
            layoutParams = lp
            setOnClickListener {
                showPortPicker(onConfirm = { port ->
                    currentPort = port
                    prefs.setPreviewPort(port)
                    portChip.text = "localhost:$port"
                    retryCount = 0
                    checkAndLoad()
                })
            }
        }
        bar.addView(portChip)

        // Spacer
        bar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        // Refresh button
        val refresh = TextView(this).apply {
            text = "↻"
            textSize = 22f
            setTextColor(Color.parseColor("#9090A0"))
            setPadding(12.dp, 0, 8.dp, 0)
            setOnClickListener {
                // Re-probe from scratch: works whether we're showing the page
                // (content refresh), the loading overlay, or the error screen.
                handler.removeCallbacks(retryRunnable)
                retryCount = 0
                checkAndLoad()
            }
        }
        bar.addView(refresh, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, 48.dp
        ))

        // Overflow menu
        val more = TextView(this).apply {
            text = "⋮"
            textSize = 20f
            setTextColor(Color.parseColor("#9090A0"))
            setPadding(8.dp, 0, 8.dp, 0)
            setOnClickListener { v ->
                val pop = PopupMenu(this@PreviewActivity, v)
                pop.menu.add("Change port").setOnMenuItemClickListener {
                    portChip.performClick(); true
                }
                pop.menu.add("Open in browser").setOnMenuItemClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("http://127.0.0.1:$currentPort")))
                    true
                }
                pop.show()
            }
        }
        bar.addView(more, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, 48.dp
        ))

        return bar
    }

    private fun buildLoadingView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0C0C0F"))
            visibility = View.GONE
            setPadding(32.dp, 0, 32.dp, 0)

            addView(android.widget.ProgressBar(this@PreviewActivity).apply {
                isIndeterminate = true
                indeterminateTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#FF8C42"))
            })
            addView(TextView(this@PreviewActivity).apply {
                tag = "loading-label"
                text = "Connecting to localhost:$currentPort…"
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Color.parseColor("#9090A0"))
                gravity = Gravity.CENTER
                setPadding(0, 16.dp, 0, 0)
            })
        }
    }

    private fun buildErrorView(): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0C0C0F"))
            visibility = View.GONE
            setPadding(32.dp, 0, 32.dp, 0)
        }

        // Icon row
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val icon = TextView(this).apply {
            text = "🖥"
            textSize = 40f
            setPadding(0, 0, 0, 0)
        }
        val cross = TextView(this).apply {
            text = " ✕"
            textSize = 22f
            setTextColor(Color.parseColor("#F87171"))
            gravity = Gravity.TOP
        }
        iconRow.addView(icon)
        iconRow.addView(cross)
        v.addView(iconRow)

        // Title
        v.addView(TextView(this).apply {
            text = "Dev server not running"
            textSize = 17f
            setTextColor(Color.parseColor("#F0F0F2"))
            gravity = Gravity.CENTER
            setPadding(0, 16.dp, 0, 6.dp)
        })

        // Subtitle (port, updated dynamically)
        val sub = TextView(this).apply {
            tag = "subtitle"
            textSize = 13f
            setTextColor(Color.parseColor("#9090A0"))
            gravity = Gravity.CENTER
        }
        v.addView(sub)

        // Hint label
        v.addView(TextView(this).apply {
            text = "Start it in the 🐧 Ubuntu tab:"
            textSize = 12f
            setTextColor(Color.parseColor("#9090A0"))
            gravity = Gravity.CENTER
            setPadding(0, 20.dp, 0, 8.dp)
        })

        // Code hint block
        val hint = TextView(this).apply {
            tag = "hint"
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#3DD68C"))
            setBackgroundColor(Color.parseColor("#151518"))
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24.dp) }
            layoutParams = lp
        }
        v.addView(hint)

        // Button row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun amberBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#1E1E22"))
            setBackgroundColor(Color.parseColor("#FF8C42"))
            setPadding(20.dp, 10.dp, 20.dp, 10.dp)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(8.dp, 0, 8.dp, 0) }
            layoutParams = lp
            setOnClickListener { onClick() }
        }

        btnRow.addView(amberBtn("↻  Retry") {
            errorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            retryCount = 0
            checkAndLoad()
        })
        btnRow.addView(amberBtn("Change port") { portChip.performClick() })
        v.addView(btnRow)

        return v
    }

    // ── Port picker bottom sheet ──────────────────────────────────────────────

    private fun showPortPicker(onConfirm: (Int) -> Unit) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#151518"))
            setPadding(24.dp, 20.dp, 24.dp, 32.dp)
        }

        // Handle
        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#3A3A42"))
            val lp = LinearLayout.LayoutParams(40.dp, 4.dp).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, 20.dp)
            }
            layoutParams = lp
        })

        // Title
        root.addView(TextView(this).apply {
            text = "🌐  Live Preview"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F0F0F2"))
        })
        root.addView(TextView(this).apply {
            text = "Enter the port your dev server is running on"
            textSize = 12f
            setTextColor(Color.parseColor("#9090A0"))
            setPadding(0, 4.dp, 0, 20.dp)
        })

        // PORT label
        root.addView(TextView(this).apply {
            text = "PORT"
            textSize = 11f
            setTextColor(Color.parseColor("#60606E"))
            setPadding(0, 0, 0, 6.dp)
        })

        // Port input
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentPort.toString())
            textSize = 16f
            setTextColor(Color.parseColor("#F0F0F2"))
            setHintTextColor(Color.parseColor("#60606E"))
            setBackgroundColor(Color.parseColor("#1E1E22"))
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            selectAll()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val port = text.toString().toIntOrNull() ?: currentPort
                    sheet.dismiss(); onConfirm(port); true
                } else false
            }
        }
        root.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 16.dp) })

        // Preset chips
        root.addView(TextView(this).apply {
            text = "Common ports:"
            textSize = 11f
            setTextColor(Color.parseColor("#60606E"))
            setPadding(0, 0, 0, 8.dp)
        })

        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        presets.forEach { (name, port) ->
            val chip = TextView(this).apply {
                text = "$name $port"
                textSize = 12f
                setTextColor(Color.parseColor("#FF8C42"))
                setBackgroundColor(Color.parseColor("#22FF8C42"))
                setPadding(12.dp, 6.dp, 12.dp, 6.dp)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 8.dp, 0) }
                layoutParams = lp
                setOnClickListener { input.setText(port.toString()) }
            }
            chipRow.addView(chip)
        }
        chipScroll.addView(chipRow)
        root.addView(chipScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 24.dp) })

        // Open button
        val openBtn = Button(this).apply {
            text = "Open Preview  →"
            textSize = 14f
            setTextColor(Color.parseColor("#1E1E22"))
            setBackgroundColor(Color.parseColor("#FF8C42"))
            isAllCaps = false
            setPadding(0, 14.dp, 0, 14.dp)
            setOnClickListener {
                val port = input.text.toString().toIntOrNull() ?: currentPort
                sheet.dismiss()
                onConfirm(port)
            }
        }
        root.addView(openBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        sheet.setContentView(root)
        sheet.show()
        input.requestFocus()
    }

    // ── Load logic with auto-retry ────────────────────────────────────────────

    private fun checkAndLoad() {
        val url = "http://127.0.0.1:$currentPort"
        // Update error view labels
        (errorView.findViewWithTag<TextView>("subtitle"))?.text =
            "Nothing on localhost:$currentPort"
        (errorView.findViewWithTag<TextView>("hint"))?.text =
            hintForPort(currentPort)

        // Show the loading overlay instead of a blank WebView / premature error
        // while we probe + retry. Reflects which attempt we're on.
        errorView.visibility = View.GONE
        webView.visibility = View.GONE
        loadingView.visibility = View.VISIBLE
        (loadingView.findViewWithTag<TextView>("loading-label"))?.text =
            if (retryCount == 0) "Connecting to localhost:$currentPort…"
            else "Connecting to localhost:$currentPort… (retry $retryCount/$MAX_RETRIES)"

        // Silently probe the port on a background thread; if reachable → load,
        // else auto-retry then show the error screen. A 3s timeout + more retries
        // tolerate a slow / single-threaded dev server still binding the port.
        Thread {
            val reachable = try {
                val con = URL(url).openConnection() as HttpURLConnection
                con.connectTimeout = 3000
                con.readTimeout = 3000
                con.connect()
                con.responseCode in 100..599   // any HTTP response = server up
            } catch (_: Exception) { false }

            runOnUiThread {
                if (reachable) {
                    errorView.visibility = View.GONE
                    loadingView.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    webView.loadUrl(url)
                } else if (retryCount < MAX_RETRIES) {
                    retryCount++
                    handler.postDelayed(retryRunnable, 1500)
                } else {
                    showError()
                }
            }
        }.start()
    }

    private fun showError() {
        loadingView.visibility = View.GONE
        webView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun hintForPort(port: Int) = when (port) {
        5173 -> "$ npm run dev"
        3000 -> "$ npm run dev   # or: npm start"
        4200 -> "$ ng serve"
        5000 -> "$ flask run"
        8000 -> "$ python manage.py runserver   # or: python -m http.server"
        else -> "$ npm run dev   # or your server start command"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        handler.removeCallbacks(retryRunnable)
        webView.destroy()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── dp helper ─────────────────────────────────────────────────────────────

    private val Int.dp: Int get() =
        (this * resources.displayMetrics.density + 0.5f).toInt()
}
