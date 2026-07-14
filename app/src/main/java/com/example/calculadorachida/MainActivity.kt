package com.example.calculadorachida

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvExpression: TextView
    private lateinit var tvDisplay: TextView
    private lateinit var calcDisplay: View
    private lateinit var doomSurface: DoomSurfaceView
    private lateinit var btnDoomToggle: Button

    // ── Calculator state ──────────────────────────────────────────────────────
    private var displayValue = "0"
    private var storedValue = 0.0
    private var pendingOperator = ""
    private var waitingForOperand = false
    private var justCalculated = false
    private val stateStack = ArrayDeque<Pair<Double, String>>()

    // ── DOOM state ────────────────────────────────────────────────────────────
    private var isDoomMode = false

    // Fill in with your own legitimately-sourced direct download link to the
    // shareware doom1.wad before building an APK you plan to share, so DOOM
    // mode works for others without them needing adb or a file manager. Leave
    // blank to keep the manual transfer flow (Toast points at the adb push
    // command instead).
    private val doomWadUrl = "https://www.pc-freak.net/files/doom-wad-files/Doom1.WAD"

    /*
     * Button label → DOOM key code mapping.
     *
     * Numpad-style movement:
     *   8 = forward    2 = backward    4 = turn left    6 = turn right
     *   7 = strafe L   9 = strafe R
     *
     * Actions:
     *   5 / x² / . / = → fire      0 / tan → use/open     + → run
     *   √ / %          → map (TAB)  C        → Escape
     *
     * Operators as weapons:
     *   1=wp1  3=wp3  ÷=wp4  ×=wp5  −=wp6
     *
     * Scientific as extra controls:
     *   sin → strafe L   cos → strafe R   xⁿ → run
     */
    private val doomKeyMap: Map<String, Int> = mapOf(
        // Movement
        "8"   to 0xad,   // KEY_UPARROW    (forward)
        "2"   to 0xaf,   // KEY_DOWNARROW  (backward)
        "4"   to 0xac,   // KEY_LEFTARROW  (turn left)
        "6"   to 0xae,   // KEY_RIGHTARROW (turn right)
        "7"   to 0xa0,   // KEY_STRAFE_L
        "9"   to 0xa1,   // KEY_STRAFE_R
        // Fire
        "5"   to 0xa3,   // KEY_FIRE
        "x²"  to 0xa3,
        "."   to 0xa3,
        // Menu select / confirm
        "="   to 13,     // KEY_ENTER
        // Use / open door
        "0"   to 0xa2,   // KEY_USE
        // Run
        "+"   to 0xb6,   // KEY_RSHIFT
        "xⁿ"  to 0xb6,
        // Map toggle
        "√"   to 0x09,   // TAB
        "%"   to 0x09,
        // Escape / menu
        "C"   to 0x1b,   // ESC
        // Weapon slots (DOOM uses '1'–'7'). "2" itself is taken by backward
        // movement above, so pistol (slot 2) would otherwise be unreachable
        // once you'd switched off it — "tan" stands in for it instead.
        "1"   to 0x31,
        "tan" to 0x32,
        "3"   to 0x33,
        "÷"   to 0x34,
        "×"   to 0x35,
        "−"   to 0x36,
        // Alternate strafe
        "sin" to 0xa0,   // KEY_STRAFE_L
        "cos" to 0xa1,   // KEY_STRAFE_R
        // Confirm dialogs ("are you sure?" on e.g. Nightmare difficulty)
        // only accept key_menu_confirm/abort ('y'/'n') or space/escape —
        // "(" and ")" were otherwise unused in DOOM mode.
        "("   to 0x79,   // 'y' KEY_MENU_CONFIRM
        ")"   to 0x6e,   // 'n' KEY_MENU_ABORT
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        tvExpression  = findViewById(R.id.tvExpression)
        tvDisplay     = findViewById(R.id.tvDisplay)
        calcDisplay   = findViewById(R.id.calcDisplay)
        doomSurface   = findViewById(R.id.doomSurface)
        btnDoomToggle = findViewById(R.id.btnDoomToggle)

        tvDisplay.text = displayValue

        // Install touch listeners on every button for DOOM key injection
        setupDoomTouchListeners(findViewById(R.id.main))
    }

    override fun onDestroy() {
        super.onDestroy()
        doomSurface.stopDoom()
    }

    // ── Shared click handler (called via android:onClick on all buttons) ───────
    fun onButtonClick(view: View) {
        val label = (view as Button).text.toString()

        // DOOM toggle is always active regardless of mode
        if (label == "DOOM" || label == "CALC") {
            toggleDoomMode()
            return
        }

        // In DOOM mode all other buttons are handled exclusively by the touch listener
        if (isDoomMode) return

        // ── Calculator logic ──────────────────────────────────────────────────
        when (label) {
            "C"                              -> clearAll()
            "±"                              -> toggleSign()
            "%"                              -> applyPercent()
            "+", "−", "×", "÷", "xⁿ"       -> applyOperator(label)
            "="                              -> calculate()
            "."                              -> appendDecimal()
            "sin", "cos", "tan"             -> applyTrigFunction(label)
            "x²"                             -> applySquare()
            "√"                              -> applySqrt()
            "("                              -> openParen()
            ")"                              -> closeParen()
            else                             -> appendDigit(label)
        }
        tvDisplay.text = displayValue
    }

    // ── DOOM mode toggle ──────────────────────────────────────────────────────
    private fun toggleDoomMode() {
        if (isDoomMode) exitDoomMode() else enterDoomMode()
    }

    private fun enterDoomMode() {
        // Only app-private paths — they need no storage permission and always
        // work from native code. Raw /sdcard paths (e.g. /sdcard/Download) can
        // pass File.exists() in Kotlin yet still fail native fopen() under
        // scoped storage (API 29+); worse, a stale leftover file there will
        // get picked as a "found" candidate and crash, pre-empting the
        // downloadWad() fallback below from ever running. Not worth the risk
        // now that auto-download covers the not-found case reliably.
        val candidates = listOf(
            File(getExternalFilesDir(null), "doom1.wad"),  // /sdcard/Android/data/<pkg>/files/
            File(filesDir, "doom1.wad"),                   // internal storage (adb run-as)
        )
        val wadFile = candidates.firstOrNull { it.exists() && it.length() > 0 }
        if (wadFile != null) {
            startDoomWith(wadFile)
            return
        }

        if (doomWadUrl.isBlank()) {
            Toast.makeText(
                this,
                "doom1.wad not found. Easiest:\nadb push doom1.wad \"${getExternalFilesDir(null)}/\"",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        downloadWad { downloaded -> downloaded?.let { startDoomWith(it) } }
    }

    private fun startDoomWith(wadFile: File) {
        isDoomMode = true
        calcDisplay.visibility = View.GONE
        doomSurface.visibility = View.VISIBLE
        btnDoomToggle.text = "CALC"
        doomSurface.prepare(wadFile.absolutePath, filesDir.absolutePath)
    }

    // Downloads doomWadUrl into the app-private external files dir (no
    // storage permission needed, see enterDoomMode's candidate ordering) and
    // calls back on the UI thread with the saved File, or null on failure.
    private fun downloadWad(onResult: (File?) -> Unit) {
        val target = File(getExternalFilesDir(null), "doom1.wad")
        Toast.makeText(this, "Downloading DOOM data…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val connection = URL(doomWadUrl).openConnection() as HttpURLConnection
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${connection.responseCode}")
                }
                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                // The real shareware doom1.wad is ~4.2MB. Anything far smaller
                // is a broken/redirected link (e.g. an HTML error page) rather
                // than the real file — DOOM would otherwise accept it and
                // crash later with a much less obvious error.
                if (target.length() < 1_000_000) {
                    throw IOException("downloaded file is only ${target.length()} bytes — doomWadUrl is wrong or broken")
                }
                runOnUiThread { onResult(target) }
            } catch (e: Exception) {
                target.delete()
                runOnUiThread {
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    onResult(null)
                }
            }
        }.start()
    }

    private fun exitDoomMode() {
        isDoomMode = false
        calcDisplay.visibility = View.VISIBLE
        doomSurface.visibility = View.GONE
        btnDoomToggle.text = "DOOM"
    }

    // ── Touch listeners for DOOM key injection ────────────────────────────────
    // Returning true consumes the event (no click fires); returning false lets
    // the normal click propagate to onButtonClick for calculator use.
    private fun setupDoomTouchListeners(root: View) {
        if (root is Button) {
            root.setOnTouchListener { v, event ->
                if (!isDoomMode) return@setOnTouchListener false
                val label = (v as Button).text.toString()
                val key = doomKeyMap[label] ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN                    -> doomSurface.nativeSendKey(key, true)
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL                  -> doomSurface.nativeSendKey(key, false)
                }
                true  // consume — prevents click from firing calculator logic
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) setupDoomTouchListeners(root.getChildAt(i))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Calculator logic
    // ══════════════════════════════════════════════════════════════════════════

    private fun appendDigit(digit: String) {
        if (waitingForOperand || justCalculated) {
            displayValue = digit
            waitingForOperand = false
            justCalculated = false
        } else {
            displayValue = if (displayValue == "0") digit else displayValue + digit
        }
    }

    private fun appendDecimal() {
        if (waitingForOperand || justCalculated) {
            displayValue = "0."
            waitingForOperand = false
            justCalculated = false
            return
        }
        if (!displayValue.contains(".")) displayValue += "."
    }

    private fun applyOperator(op: String) {
        if (!waitingForOperand) {
            val current = displayValue.toDouble()
            if (pendingOperator.isNotEmpty()) {
                val result = computeResult(storedValue, current, pendingOperator)
                storedValue = result
                displayValue = formatNumber(result)
            } else {
                storedValue = current
            }
        }
        pendingOperator = op
        waitingForOperand = true
        justCalculated = false
        tvExpression.text = "${formatNumber(storedValue)} $op"
    }

    private fun calculate() {
        if (pendingOperator.isEmpty() || waitingForOperand) return
        val input = displayValue.toDouble()
        val result = computeResult(storedValue, input, pendingOperator)
        tvExpression.text = "${formatNumber(storedValue)} $pendingOperator $displayValue ="
        displayValue = formatNumber(result)
        storedValue = 0.0
        pendingOperator = ""
        waitingForOperand = false
        justCalculated = true
    }

    private fun applyTrigFunction(func: String) {
        val value = displayValue.toDoubleOrNull() ?: return
        val rad = value * PI / 180.0
        val result = when (func) {
            "sin" -> sin(rad)
            "cos" -> cos(rad)
            "tan" -> tan(rad)
            else  -> return
        }
        tvExpression.text = "$func($value°)"
        displayValue = formatNumber(result)
        justCalculated = true
    }

    private fun applySquare() {
        val value = displayValue.toDoubleOrNull() ?: return
        val original = displayValue
        displayValue = formatNumber(value * value)
        tvExpression.text = "$original²"
        justCalculated = true
    }

    private fun applySqrt() {
        val value = displayValue.toDoubleOrNull() ?: return
        if (value < 0.0) { displayValue = "Error"; return }
        val original = displayValue
        displayValue = formatNumber(sqrt(value))
        tvExpression.text = "√$original"
        justCalculated = true
    }

    private fun openParen() {
        stateStack.addLast(Pair(storedValue, pendingOperator))
        storedValue = 0.0
        pendingOperator = ""
        displayValue = "0"
        waitingForOperand = true
        justCalculated = false
        tvExpression.text = "(".repeat(stateStack.size)
    }

    private fun closeParen() {
        if (stateStack.isEmpty()) return
        val innerResult = if (pendingOperator.isNotEmpty() && !waitingForOperand) {
            computeResult(storedValue, displayValue.toDoubleOrNull() ?: 0.0, pendingOperator)
        } else {
            displayValue.toDoubleOrNull() ?: 0.0
        }
        val (outerStored, outerOp) = stateStack.removeLast()
        storedValue = outerStored
        pendingOperator = outerOp
        displayValue = formatNumber(innerResult)
        waitingForOperand = false
        justCalculated = false
        tvExpression.text = if (outerOp.isNotEmpty()) "${formatNumber(outerStored)} $outerOp" else ""
    }

    private fun computeResult(a: Double, b: Double, op: String): Double = when (op) {
        "+"  -> a + b
        "−"  -> a - b
        "×"  -> a * b
        "÷"  -> if (b != 0.0) a / b else Double.NaN
        "xⁿ" -> a.pow(b)
        else -> b
    }

    private fun clearAll() {
        displayValue = "0"
        storedValue = 0.0
        pendingOperator = ""
        waitingForOperand = false
        justCalculated = false
        stateStack.clear()
        tvExpression.text = ""
    }

    private fun toggleSign() {
        val value = displayValue.toDoubleOrNull() ?: return
        displayValue = formatNumber(-value)
        justCalculated = false
    }

    private fun applyPercent() {
        val value = displayValue.toDoubleOrNull() ?: return
        displayValue = formatNumber(value / 100.0)
        justCalculated = false
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "Error"
        return if (value % 1.0 == 0.0) value.toLong().toString()
        else "%.9f".format(value).trimEnd('0').trimEnd('.')
    }
}
