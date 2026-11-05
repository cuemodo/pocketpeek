package com.gaeljaffrain.pocketartnet

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val receiver = ArtNetReceiver()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var grid: DmxGridView
    private lateinit var pageBar: PageBarView
    private lateinit var universeChip: TextView
    private lateinit var modeChip: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView

    private var universe = 0
    private var page = 0
    /** First channel (0-based) of the current page: kept when the grid shape changes. */
    private var pageStart = 0
    private val cells get() = grid.shape.cells
    private val pages get() = grid.shape.pages
    private var mode = DisplayMode.PCT
    /** Selected channel 1..512, or 0. */
    private var selectedChannel = 0

    private val snapshot = ByteArray(512)
    private val shown = ByteArray(512)
    private var forceRedraw = true
    private val prevPackets = HashMap<Int, Long>()
    private val fps = HashMap<Int, Int>()
    private var lastStatusMs = 0L
    private var lastFpsMs = 0L

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val prefs by lazy { getSharedPreferences("monitor", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        universe = prefs.getInt("universe", 0)
        pageStart = prefs.getInt("pageStart", 0).coerceIn(0, 511)
        mode = DisplayMode.entries.getOrElse(prefs.getInt("mode", 0)) { DisplayMode.PCT }
        receiver.selectedUniverse = universe
        setContentView(buildUi())
        page = pageStart / cells
        applyUniverse()
        applyPage()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()

    // Sizes are larger than usual Android ones: CipherOS runs the R1 at density 190, so 1 dp is only
    // ~0.11 mm. 60 dp is ~6.5 mm, a comfortable touch target.
    private fun chip(text: String, bold: Boolean = true) = TextView(this).apply {
        this.text = text
        setTextColor(Palette.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dpi(10f), dpi(6f), dpi(10f), dpi(6f))
        minHeight = dpi(60f)
        minWidth = dpi(60f)
        background = GradientDrawable().apply {
            setColor(Palette.PANEL)
            setStroke(dpi(1f), Palette.GRID)
            cornerRadius = dp(6f)
        }
        isClickable = true
        // Nothing takes focus: the first wheel/D-pad press would otherwise just leave touch mode.
        isFocusable = false
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }
        val pad = dpi(8f)
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(pad + insets.systemWindowInsetLeft, pad + insets.systemWindowInsetTop,
                pad + insets.systemWindowInsetRight, pad + insets.systemWindowInsetBottom)
            insets
        }
        root.setPadding(pad, pad, pad, pad)

        // Row 1: [-] [UNIVERSE n] [+]            [mode]
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val minus = chip("−").apply { setOnClickListener { changeUniverse(-1) }; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f) }
        universeChip = chip("").apply {
            setOnClickListener { showUniversePicker() }
            setPadding(dpi(4f), 0, dpi(4f), 0)
        }
        val plus = chip("+").apply { setOnClickListener { changeUniverse(1) }; setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f) }
        modeChip = chip(mode.label).apply {
            setOnClickListener { setMode(mode.next()) }
            minWidth = dpi(70f)
        }
        val gapLp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(4f) } }
        top.addView(minus, gapLp())
        top.addView(universeChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(4f) })
        top.addView(plus, gapLp())
        top.addView(modeChip)
        root.addView(top)

        // Row 2: status dot + text
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(2f), dpi(6f), 0, dpi(6f))
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.DIM) }
        }
        statusText = TextView(this).apply {
            setTextColor(Palette.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        status.addView(statusDot, LinearLayout.LayoutParams(dpi(12f), dpi(12f)).apply { marginEnd = dpi(6f) })
        status.addView(statusText)
        root.addView(status)

        // Grid takes the remaining space; its shape follows the size.
        grid = DmxGridView(this).apply {
            mode = this@MainActivity.mode
            onCellTap = { i -> selectChannel(page * cells + i + 1) }
            onSwipe = { d -> changePage(d) }
            onShape = { s ->
                page = pageStart / s.cells
                pageBar.pages = s.pages
                pageBar.visibility = if (s.pages > 1) View.VISIBLE else View.GONE
                applyPage()
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Info line: selected channel details, or the page range.
        infoText = TextView(this).apply {
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dpi(6f), 0, dpi(6f))
            setOnClickListener { selectChannel(0) }
            isFocusable = false
        }
        root.addView(infoText)

        pageBar = PageBarView(this).apply { onPage = { p -> setPage(p) } }
        root.addView(pageBar)
        return root
    }

    // ---------------------------------------------------------------- state changes

    private fun changeUniverse(delta: Int) = setUniverse((universe + delta).coerceIn(0, Universe.MAX))

    private fun setUniverse(u: Int) {
        if (u == universe) return
        universe = u
        prefs.edit().putInt("universe", u).apply()
        receiver.selectedUniverse = u
        receiver.announce()
        applyUniverse()
    }

    private fun applyUniverse() {
        universeChip.text = "U $universe · ${Universe.label(universe)}"
        forceRedraw = true
        tick()
    }

    private fun changePage(delta: Int) {
        val p = page + delta
        if (p in 0 until pages) setPage(p)
    }

    private fun setPage(p: Int) {
        page = p
        pageStart = p * cells
        prefs.edit().putInt("pageStart", pageStart).apply()
        applyPage()
    }

    private fun applyPage() {
        grid.firstChannel = page * cells + 1
        pageBar.current = page
        updateSelection()
        forceRedraw = true
        tick()
        pageBar.invalidate()
    }

    private fun setMode(m: DisplayMode) {
        mode = m
        prefs.edit().putInt("mode", m.ordinal).apply()
        modeChip.text = m.label
        grid.mode = m
        grid.invalidate()
    }

    private fun selectChannel(ch: Int) {
        selectedChannel = if (ch == selectedChannel) 0 else ch
        updateSelection()
        grid.invalidate()
    }

    private fun updateSelection() {
        val first = page * cells + 1
        grid.selected = if (selectedChannel in first until first + cells) selectedChannel - first else -1
        updateInfo()
    }

    private fun updateInfo() {
        val ch = selectedChannel
        infoText.text = if (ch == 0) {
            val first = page * cells + 1
            "CH $first–${first + cells - 1} · PAGE ${page + 1}/${pages}"
        } else {
            val v = snapshot[ch - 1].toInt() and 0xff
            "CH %d = %d · %d%% · %02X".format(ch, v, pct(v), v)
        }
    }

    private fun showUniversePicker() {
        val now = SystemClock.elapsedRealtime()
        val active = receiver.universes.values
            .filter { now - it.lastSeenMs < 5000 }
            .sortedBy { it.universe }
        val items = active.map { st ->
            "U ${st.universe}  (${Universe.label(st.universe)})  ${fps[st.universe] ?: 0} fps  ${st.source?.hostAddress ?: ""}"
        } + "Enter universe…"
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (active.isEmpty()) "No Art-Net seen yet" else "Active universes")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < active.size) setUniverse(active[which].universe) else showUniverseInput()
            }
            .show()
    }

    private fun showUniverseInput() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "e.g. 3  or  0:0:3"
            setText(universe.toString())
            selectAll()
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Art-Net universe (0–${Universe.MAX})")
            .setView(input)
            .setPositiveButton("OK") { _, _ -> Universe.parse(input.text.toString())?.let(::setUniverse) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- refresh loop

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    private fun tick() {
        if (!::grid.isInitialized) return
        val st = receiver.universes[universe]
        if (st != null) synchronized(st.data) { st.data.copyInto(snapshot) } else snapshot.fill(0)

        if (forceRedraw || !snapshot.contentEquals(shown)) {
            snapshot.copyInto(shown)
            forceRedraw = false
            snapshot.copyInto(grid.values, 0, page * cells, (page + 1) * cells)
            grid.invalidate()
            for (p in 0 until pages) {
                var m = 0
                for (i in p * cells until (p + 1) * cells) {
                    val v = snapshot[i].toInt() and 0xff
                    if (v > m) m = v
                }
                pageBar.pageMax[p] = m
            }
            pageBar.invalidate()
            if (selectedChannel != 0) updateInfo()
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastFpsMs >= 1000) {
            val dt = (now - lastFpsMs).coerceAtLeast(1)
            for ((u, s) in receiver.universes) {
                val prev = prevPackets[u] ?: s.packets
                fps[u] = ((s.packets - prev) * 1000 / dt).toInt()
                prevPackets[u] = s.packets
            }
            lastFpsMs = now
        }
        if (now - lastStatusMs >= 250) {
            lastStatusMs = now
            updateStatus(st, now)
        }
    }

    private fun updateStatus(st: UniverseState?, now: Long) {
        val err = receiver.lastError
        val live = st != null && now - st.lastSeenMs < STALE_MS
        // Hold the last look like a node would, but dim it so it can't be mistaken for live data.
        val stale = st != null && !live
        val alpha = if (stale) STALE_ALPHA else 1f
        if (grid.alpha != alpha) { grid.alpha = alpha; pageBar.alpha = alpha }
        val others = receiver.universes.values
            .filter { it.universe != universe && now - it.lastSeenMs < STALE_MS }
            .map { it.universe }.sorted()
        val active = if (others.isEmpty()) "" else "  ·  active: ${others.take(6).joinToString(", ")}"
        val (color, text) = when {
            err != null -> Palette.BAD to "Socket error: $err"
            live -> Palette.OK to "${fps[universe] ?: 0} fps  ·  ${st!!.source?.hostAddress}"
            stale -> Palette.WARN to "No signal ${formatAgo(now - st!!.lastSeenMs)}  ·  holding$active"
            others.isNotEmpty() -> Palette.WARN to "No data on U $universe$active"
            else -> {
                val ip = ArtNetReceiver.localIpv4()?.first?.hostAddress ?: "no network"
                Palette.DIM to "Waiting for Art-Net on $ip:${ArtNetReceiver.PORT}…"
            }
        }
        (statusDot.background as GradientDrawable).setColor(color)
        statusText.setTextColor(if (live) Palette.TEXT else color)
        statusText.text = text
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("artnet").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        val lockMode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(lockMode, "artnet").apply { setReferenceCounted(false); acquire() }
        receiver.start()
        lastFpsMs = SystemClock.elapsedRealtime()
        handler.post(ticker)
        handler.postDelayed({ receiver.announce() }, 500)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        receiver.stop()
        multicastLock?.release(); multicastLock = null
        wifiLock?.release(); wifiLock = null
        super.onStop()
    }

    // ---------------------------------------------------------------- hardware input
    // The R1 scroll wheel and buttons: map every plausible key to page/universe moves and log
    // the rest, so `adb logcat -s PocketArtNet` shows what the hardware actually sends.

    // dispatchKeyEvent, not onKeyDown: a focused chip would otherwise eat D-pad keys for focus moves.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action: (() -> Unit)? = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_NAVIGATE_NEXT -> { { changePage(1) } }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS -> { { changePage(-1) } }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { { showUniversePicker() } }
            else -> null
        }
        if (action == null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "unhandled key ${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})")
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) action()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) || event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER))
        ) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL).takeIf { it != 0f }
                ?: -event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (v != 0f) { changePage(if (v < 0) 1 else -1); return true }
        }
        return super.onGenericMotionEvent(event)
    }

    companion object {
        private const val TAG = "PocketArtNet"
        private const val FRAME_MS = 33L
        /** No packet for this long = signal lost (sACN uses the same 2.5 s data-loss timeout). */
        private const val STALE_MS = 2500L
        private const val STALE_ALPHA = 0.35f
    }
}
