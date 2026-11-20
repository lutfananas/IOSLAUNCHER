/*
 * iOS Launcher — modifications Copyright (c) 2026 lutfananas. The original Duo Launcher portions remain under the MIT License.
 *
 * Battery / Wi-Fi / cellular cluster.
 *
 * Ported from Duo Launcher (https://github.com/jakesgoodapps/DuoLauncher) —
 * DeviceStatus.kt, StatusSignalMapping.kt and the status glyph in StatusRail.kt.
 * Original work Copyright (c) 2026 Duo Launcher contributors, MIT License (see THIRD_PARTY_NOTICES.md).
 * Changes: Compose/Flow code rewritten as a plain Android View + listener; glyph only, placed as the
 * top-right cell of home page 1.
 */
package com.ioslauncher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.text.TextPaint
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin

data class DeviceStatus(
    val battery: Int? = null,
    val charging: Boolean = false,
    val wifiConnected: Boolean = false,
    val wifiLevel: Int? = null,
    val cellularLevel: Int? = null,
    val airplane: Boolean = false,
)

internal enum class SignalElementEmphasis { DIM, NEUTRAL, LIT }

internal sealed interface WifiSignalVisual {
    data object Disconnected : WifiSignalVisual
    data class Connected(val elements: List<SignalElementEmphasis>) : WifiSignalVisual
}

internal fun wifiSignalVisual(connected: Boolean, level: Int?): WifiSignalVisual {
    if (!connected) return WifiSignalVisual.Disconnected
    if (level == null) return WifiSignalVisual.Connected(List(4) { SignalElementEmphasis.NEUTRAL })
    val active = level.coerceIn(0, 4)
    return WifiSignalVisual.Connected(List(4) { i -> if (i < active) SignalElementEmphasis.LIT else SignalElementEmphasis.DIM })
}

internal sealed interface CellularSignalVisual {
    data object Airplane : CellularSignalVisual
    data object Unavailable : CellularSignalVisual
    data class Available(val activeDots: Int) : CellularSignalVisual
}

internal fun cellularSignalVisual(level: Int?, airplane: Boolean): CellularSignalVisual = when {
    airplane -> CellularSignalVisual.Airplane
    level == null -> CellularSignalVisual.Unavailable
    else -> CellularSignalVisual.Available((level.coerceIn(0, 4) * 5 + 3) / 4)
}

/** Observes battery, Wi-Fi and cellular state while started. No location or phone-state permission needed. */
class DeviceStatusMonitor(private val context: Context, private val onChange: (DeviceStatus) -> Unit) {
    private val connection = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val phone = context.getSystemService(TelephonyManager::class.java)
    var state = DeviceStatus(); private set
    private var started = false
    private var networkRegistered = false
    private var phoneRegistered = false

    private fun update(block: (DeviceStatus) -> DeviceStatus) {
        val next = block(state)
        if (next != state) { state = next; onChange(next) }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = post { updateConnection() }
        override fun onLost(network: Network) = post { updateConnection() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = post { updateConnection() }
    }

    private fun post(r: () -> Unit) { ContextCompat.getMainExecutor(context).execute(r) }

    private val phoneCallback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            update { it.copy(cellularLevel = signalStrength.level.coerceIn(0, 4)) }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val charge = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                update {
                    it.copy(
                        battery = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null,
                        charging = charge == BatteryManager.BATTERY_STATUS_CHARGING || charge == BatteryManager.BATTERY_STATUS_FULL,
                    )
                }
            }
            updateConnection()
        }
    }

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            @Suppress("DEPRECATION") addAction(WifiManager.RSSI_CHANGED_ACTION)
        }, ContextCompat.RECEIVER_EXPORTED)
        networkRegistered = runCatching { connection.registerDefaultNetworkCallback(networkCallback); true }.getOrDefault(false)
        phoneRegistered = runCatching { phone.registerTelephonyCallback(context.mainExecutor, phoneCallback); true }.getOrDefault(false)
        update { it.copy(cellularLevel = runCatching { phone.signalStrength?.level }.getOrNull()) }
        updateConnection()
    }

    @Suppress("DEPRECATION")
    private fun updateConnection() {
        val caps = runCatching { connection.getNetworkCapabilities(connection.activeNetwork) }.getOrNull()
        val info = (caps?.transportInfo as? WifiInfo) ?: runCatching { wifi.connectionInfo }.getOrNull()
        val connected = info?.supplicantState == SupplicantState.COMPLETED || caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val level = info?.rssi?.takeIf { connected && it > -127 }?.let { WifiManager.calculateSignalLevel(it, 5) }
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        update { it.copy(wifiConnected = connected, wifiLevel = level, airplane = airplane) }
    }

    fun stop() {
        if (!started) return
        started = false
        if (networkRegistered) runCatching { connection.unregisterNetworkCallback(networkCallback) }
        if (phoneRegistered) runCatching { phone.unregisterTelephonyCallback(phoneCallback) }
        runCatching { context.unregisterReceiver(receiver) }
        networkRegistered = false; phoneRegistered = false
    }
}

/**
 * The single combined glyph: battery ring (arc fills with charge), Wi-Fi fan inside the ring and five
 * cellular dots along the open bottom of the ring. Battery percentage sits underneath like an icon label.
 */
class StatusClusterView(context: Context) : View(context) {
    var status = DeviceStatus()
        set(v) { field = v; contentDescription = describe(v); invalidate() }
    /** Reference app-icon size; the glyph is drawn smaller than an icon with a digital clock above it. */
    var glyphSize = 0
    var showLabel = true

    private val shadow = Color.argb(40, 0, 0, 0)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        setShadowLayer(3f, 0f, 1f, Color.argb(76, 0, 0, 0))
    }
    private val arc = RectF()

    var labelTextSize: Float
        get() = labelPaint.textSize
        set(v) { labelPaint.textSize = v }

    var iconTop = 0f

    private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        setShadowLayer(4f, 0f, 1f, Color.argb(76, 0, 0, 0))
    }
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(tick); post(tick) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(tick) }

    private fun clockText(): String {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date())
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    override fun onDraw(canvas: Canvas) {
        val ref = glyphSize.toFloat().takeIf { it > 0 } ?: minOf(width, height).toFloat()
        val w = ref * GLYPH_RATIO
        val ox = (width - w) / 2f
        // Glyph bottom sits where an app icon's bottom would; the clock fills the space above it.
        val oy = iconTop + ref - w
        clockPaint.textSize = ref * 0.19f   // 70% of the previous 0.27
        canvas.drawText(clockText(), width / 2f, oy - ref * 0.06f, clockPaint)
        canvas.save()
        canvas.translate(ox, oy)
        val cx = w / 2f; val cy = w / 2f
        val radius = w * .44f
        val ring = w * .072f
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)

        stroke.strokeWidth = ring * 1.25f; stroke.color = Color.argb(38, 0, 0, 0)
        canvas.drawArc(arc, 150f, 240f, false, stroke)
        stroke.strokeWidth = ring; stroke.color = Color.argb(82, 255, 255, 255)
        canvas.drawArc(arc, 150f, 240f, false, stroke)
        status.battery?.let {
            stroke.color = if (status.charging) Color.rgb(52, 199, 89) else if (it <= 20) Color.rgb(255, 69, 58) else Color.WHITE
            canvas.drawArc(arc, 150f, 240f * it / 100f, false, stroke)
        }

        val wifiVisual = wifiSignalVisual(status.wifiConnected, status.wifiLevel)
        if (wifiVisual is WifiSignalVisual.Connected) {
            for (i in 1..3) {
                val r = w * (.12f + i * .067f)
                val rect = RectF(cx - r, w * .61f - r, cx + r, w * .61f + r)
                stroke.color = Color.argb(41, 0, 0, 0); stroke.strokeWidth = w * .073f
                canvas.drawArc(rect, 230f, 80f, false, stroke)
                stroke.color = Color.argb((255 * signalAlpha(wifiVisual.elements[i])).toInt(), 255, 255, 255)
                stroke.strokeWidth = w * .058f
                canvas.drawArc(rect, 230f, 80f, false, stroke)
            }
            fill.color = Color.argb(41, 0, 0, 0)
            canvas.drawCircle(cx, w * .60f, w * .052f, fill)
            fill.color = Color.argb((255 * signalAlpha(wifiVisual.elements[0])).toInt(), 255, 255, 255)
            canvas.drawCircle(cx, w * .60f, w * .043f, fill)
        } else {
            stroke.color = Color.argb(41, 0, 0, 0); stroke.strokeWidth = w * .073f
            canvas.drawLine(w * .39f, w * .42f, w * .61f, w * .58f, stroke)
            stroke.color = Color.argb(191, 255, 255, 255); stroke.strokeWidth = w * .058f
            canvas.drawLine(w * .39f, w * .42f, w * .61f, w * .58f, stroke)
        }

        val cell = cellularSignalVisual(status.cellularLevel, status.airplane)
        val activeDots = (cell as? CellularSignalVisual.Available)?.activeDots ?: 0
        for (i in 0..4) {
            val angle = Math.toRadians((130 - i * 20).toDouble())
            val dx = cx + radius * cos(angle).toFloat()
            val dy = cy + radius * sin(angle).toFloat()
            fill.color = Color.argb(36, 0, 0, 0)
            canvas.drawCircle(dx, dy, w * .052f, fill)
            fill.color = if (i < activeDots) Color.WHITE else Color.argb(77, 255, 255, 255)
            canvas.drawCircle(dx, dy, w * .043f, fill)
        }
        canvas.restore()

        if (showLabel) {
            val text = if (status.airplane) "Airplane" else status.battery?.let { "$it%${if (status.charging) " ⚡" else ""}" } ?: "—"
            canvas.drawText(text, width / 2f, oy + w + labelPaint.textSize * 1.25f, labelPaint)
        }
    }

    companion object { const val GLYPH_RATIO = 0.7f }

    private fun signalAlpha(e: SignalElementEmphasis) = when (e) {
        SignalElementEmphasis.DIM -> .3f
        SignalElementEmphasis.NEUTRAL -> .62f
        SignalElementEmphasis.LIT -> 1f
    }

    private fun describe(s: DeviceStatus) = listOf(
        s.battery?.let { "Battery $it percent${if (s.charging) ", charging" else ""}" } ?: "Battery unavailable",
        if (s.wifiConnected) "Wi-Fi connected${s.wifiLevel?.let { ", signal $it of 4" } ?: ""}" else "Wi-Fi disconnected",
        if (s.airplane) "Airplane mode" else s.cellularLevel?.let { "Cellular signal $it of 4" } ?: "Cellular signal unavailable",
    ).joinToString(". ")
}
