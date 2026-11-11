package com.example.miniwalkie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private var socket: WebSocketClient? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private val transmitting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sampleRate = 16000
    private var reconnectAttempt = 0
    private var userRequestedDisconnect = false

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var activityLog: TextView
    private lateinit var serverInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var roomInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var connectButton: Button
    private lateinit var talkButton: TextView

    private val prefs by lazy { getSharedPreferences("mini_walkie", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)
        buildUi()
        initPlayer()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 20, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(Color.rgb(21, 29, 48), 22, Color.rgb(40, 52, 76))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
    }

    private fun makeField(hintText: String, initial: String = ""): EditText = EditText(this).apply {
        hint = hintText
        setText(initial)
        textSize = 15f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(132, 147, 171))
        setSingleLine(true)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(Color.rgb(12, 19, 34), 14, Color.rgb(48, 62, 88))
        layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(7); bottomMargin = dp(12) }
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(18))
            setBackgroundColor(Color.rgb(11, 16, 32))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }

        page.addView(text("MINI  /  WALKIE TALKIE", 12f, Color.rgb(83, 222, 202), true))
        page.addView(space(12))
        page.addView(text("Stay in the loop.", 30f, Color.WHITE, true))
        page.addView(text("Push to talk with your people, wherever you are.", 14f, Color.rgb(157, 170, 191)))
        page.addView(space(22))

        val settings = card()
        settings.addView(text("YOUR CHANNEL", 12f, Color.rgb(83, 222, 202), true))
        settings.addView(space(10))
        settings.addView(text("Server address", 14f, Color.rgb(217, 225, 238), true))
        serverInput = makeField(
            "https://your-worker.<subdomain>.workers.dev",
            prefs.getString("server", "") ?: ""
        ).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        settings.addView(serverInput)
        settings.addView(text("Paste your hosting URL here. HTTPS is converted to secure WSS.", 12f, Color.rgb(137, 151, 174)))
        settings.addView(space(12))
        settings.addView(text("YOUR NAME", 11f, Color.rgb(137, 151, 174), true))
        nameInput = makeField("e.g. Alex", prefs.getString("name", "") ?: "")
        settings.addView(nameInput)
        settings.addView(text("ROOM", 11f, Color.rgb(137, 151, 174), true))
        roomInput = makeField("e.g. team", prefs.getString("room", "team") ?: "team")
        settings.addView(roomInput)

        connectButton = Button(this).apply {
            text = "CONNECT TO ROOM"
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(9, 24, 35))
            background = rounded(Color.rgb(83, 222, 202), 15)
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(3) }
            setOnClickListener { if (socket?.isOpen == true) disconnectByUser() else connect() }
        }
        settings.addView(connectButton)
        val statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        statusDot = View(this).apply { background = rounded(Color.rgb(137, 151, 174), 50); layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)) }
        statusText = text("Not connected", 13f, Color.rgb(177, 190, 208))
        (statusText.layoutParams as? LinearLayout.LayoutParams)?.let { it.leftMargin = dp(9) }
        statusRow.addView(statusDot)
        statusRow.addView(statusText.apply { setPadding(dp(9), 0, 0, 0) })
        settings.addView(statusRow)
        page.addView(settings)

        val talkCard = card()
        talkCard.addView(text("PUSH TO TALK", 12f, Color.rgb(137, 151, 174), true))
        talkCard.addView(space(12))
        talkButton = text("HOLD TO SPEAK\n\nRelease to stop", 17f, Color.rgb(9, 24, 35), true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = rounded(Color.rgb(83, 222, 202), 22)
            layoutParams = LinearLayout.LayoutParams(-1, dp(138))
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { startTalking(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopTalking(); true }
                    else -> true
                }
            }
        }
        talkCard.addView(talkButton)
        talkCard.addView(space(10))
        talkCard.addView(text("Keep holding the button while you speak.", 12f, Color.rgb(137, 151, 174)))
        page.addView(talkCard)

        val chat = card()
        chat.addView(text("QUICK MESSAGE", 12f, Color.rgb(83, 222, 202), true))
        messageInput = makeField("Write a message…")
        chat.addView(messageInput)
        val sendButton = Button(this).apply {
            text = "SEND MESSAGE"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(48, 62, 88), 14)
            layoutParams = LinearLayout.LayoutParams(-1, dp(48))
            setOnClickListener { sendMessage() }
        }
        chat.addView(sendButton)
        chat.addView(space(16))
        chat.addView(text("ROOM ACTIVITY", 11f, Color.rgb(137, 151, 174), true))
        activityLog = text("Your messages and room updates will show here.", 13f, Color.rgb(205, 216, 233)).apply {
            setPadding(0, dp(9), 0, 0)
            maxLines = 7
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        chat.addView(activityLog)
        page.addView(chat)
        page.addView(text("Free hosting plans have usage limits. For hobby or testing use.", 11f, Color.rgb(116, 132, 157)).apply { gravity = Gravity.CENTER; setPadding(dp(4), 0, dp(4), dp(8)) })
        setContentView(scroll)
    }

    private fun normalizeUrl(raw: String): String {
        val typed = raw.trim().trimEnd('/')
        require(typed.isNotEmpty()) { "Enter your server URL first." }
        val normalized = when {
            typed.startsWith("https://", true) -> "wss://" + typed.substringAfter("://")
            typed.startsWith("http://", true) -> "ws://" + typed.substringAfter("://")
            typed.startsWith("wss://", true) || typed.startsWith("ws://", true) -> typed
            else -> "wss://$typed"
        }
        val uri = URI(normalized)
        require((uri.scheme == "wss" || uri.scheme == "ws") && !uri.host.isNullOrBlank()) {
            "Use a valid HTTPS/WSS server URL."
        }
        val room = URLEncoder.encode(roomInput.text.toString().trim().ifBlank { "team" }, "UTF-8")
        val existingQuery = uri.rawQuery?.split('&')?.filterNot { it.substringBefore('=').equals("room", true) }.orEmpty()
        val query = (existingQuery + "room=$room").joinToString("&")
        return buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority).append(uri.rawPath ?: "")
            append('?').append(query)
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    private fun connect() {
        mainHandler.removeCallbacksAndMessages(null)
        val selectedRoom = roomInput.text.toString().trim().ifBlank { "team" }
        val url = try { normalizeUrl(serverInput.text.toString()) } catch (e: Exception) {
            setStatus(e.message ?: "Enter a valid server URL", false)
            return
        }
        userRequestedDisconnect = false
        reconnectAttempt = 0
        prefs.edit()
            .putString("server", serverInput.text.toString().trim())
            .putString("name", nameInput.text.toString().trim())
            .putString("room", roomInput.text.toString().trim())
            .apply()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(serverInput.windowToken, 0)
        socket?.let { old -> socket = null; old.close() }
        openSocket(url, selectedRoom)
    }

    private fun openSocket(url: String, selectedRoom: String) {
        setStatus(if (reconnectAttempt == 0) "Connecting…" else "Waking server · retry ${reconnectAttempt}/8", false)
        lateinit var client: WebSocketClient
        client = object : WebSocketClient(URI(url)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                reconnectAttempt = 0
                val join = JSONObject()
                    .put("type", "join")
                    .put("name", nameInput.text.toString().trim().ifBlank { "Guest" })
                    .put("room", selectedRoom)
                send(join.toString())
                runOnUiThread {
                    if (this@MainActivity.socket === client) {
                        setStatus("Connected · $selectedRoom", true)
                        connectButton.text = "DISCONNECT"
                        append("Connected to room")
                    }
                }
            }

            override fun onMessage(message: String) {
                runOnUiThread {
                    if (this@MainActivity.socket !== client) return@runOnUiThread
                    try {
                        val json = JSONObject(message)
                        when (json.optString("type")) {
                            "chat" -> append("${json.optString("name")}: ${json.optString("text")}")
                            "info" -> append(json.optString("text"))
                        }
                    } catch (_: Exception) { append(message) }
                }
            }

            override fun onMessage(bytes: ByteBuffer) {
                val data = ByteArray(bytes.remaining())
                bytes.get(data)
                try { player?.write(data, 0, data.size) } catch (_: Exception) { }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                runOnUiThread {
                    if (this@MainActivity.socket === client) handleConnectionLost(url, selectedRoom, reason ?: "Connection closed")
                }
            }

            override fun onError(ex: Exception?) {
                runOnUiThread {
                    if (this@MainActivity.socket === client) append(ex?.message ?: "Connection error")
                }
            }
        }
        socket = client
        try { client.connect() } catch (e: Exception) { handleConnectionLost(url, selectedRoom, e.message ?: "Unable to connect") }
    }

    private fun handleConnectionLost(url: String, selectedRoom: String, reason: String) {
        if (userRequestedDisconnect) {
            setStatus("Disconnected", false)
            connectButton.text = "CONNECT TO ROOM"
            return
        }
        if (reconnectAttempt >= 8) {
            setStatus("Could not connect · check the server URL", false)
            connectButton.text = "TRY AGAIN"
            append("$reason. Check the URL, free-tier quota, and service status.")
            return
        }
        val delays = longArrayOf(2_000, 4_000, 8_000, 15_000, 25_000, 35_000, 45_000, 60_000)
        val delay = delays[min(reconnectAttempt, delays.lastIndex)]
        reconnectAttempt++
        setStatus("Server waking · trying again…", false)
        mainHandler.postDelayed({
            if (!userRequestedDisconnect && socket != null) openSocket(url, selectedRoom)
        }, delay)
    }

    private fun disconnectByUser() {
        userRequestedDisconnect = true
        mainHandler.removeCallbacksAndMessages(null)
        stopTalking()
        val old = socket
        socket = null
        old?.close()
        setStatus("Disconnected", false)
        connectButton.text = "CONNECT TO ROOM"
    }

    private fun setStatus(message: String, connected: Boolean) {
        statusText.text = message
        statusText.setTextColor(if (connected) Color.rgb(83, 222, 202) else Color.rgb(177, 190, 208))
        statusDot.background = rounded(if (connected) Color.rgb(83, 222, 202) else Color.rgb(240, 177, 91), 50)
    }

    private fun initPlayer() {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return
        player = AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer.coerceAtLeast(4096),
            AudioTrack.MODE_STREAM
        )
        try { player?.play() } catch (_: Exception) { }
    }

    private fun startTalking() {
        if (socket?.isOpen != true) {
            append("Connect to your room before talking.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            append("Allow microphone access to use push-to-talk.")
            return
        }
        if (!transmitting.compareAndSet(false, true)) return
        talkButton.background = rounded(Color.rgb(255, 173, 94), 22)
        talkButton.text = "YOU'RE LIVE\n\nRelease to stop"
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val size = minBuffer.coerceAtLeast(2048)
            try {
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
                recorder?.startRecording()
                val data = ByteArray(size)
                while (transmitting.get()) {
                    val count = recorder?.read(data, 0, data.size) ?: 0
                    if (count > 0 && socket?.isOpen == true) socket?.send(ByteBuffer.wrap(data.copyOf(count)))
                }
            } catch (e: Exception) {
                runOnUiThread { append("Microphone error: ${e.message ?: "check permission"}") }
            } finally {
                try { recorder?.stop(); recorder?.release() } catch (_: Exception) { }
                recorder = null
                runOnUiThread { if (::talkButton.isInitialized) {
                    talkButton.background = rounded(Color.rgb(83, 222, 202), 22)
                    talkButton.text = "HOLD TO SPEAK\n\nRelease to stop"
                } }
            }
        }.start()
    }

    private fun stopTalking() { transmitting.set(false) }

    private fun sendMessage() {
        val value = messageInput.text.toString().trim()
        if (value.isEmpty()) return
        val client = socket
        if (client?.isOpen != true) { append("Connect to your room before sending a message."); return }
        try {
            client.send(JSONObject().put("type", "chat").put("text", value).toString())
            append("Me: $value")
            messageInput.setText("")
        } catch (e: Exception) { append("Message could not be sent: ${e.message}") }
    }

    private fun append(line: String) {
        val oldLines = activityLog.text.toString().lines().filter { it.isNotBlank() }
        activityLog.text = (oldLines + line).takeLast(7).joinToString("\n")
    }

    override fun onDestroy() {
        userRequestedDisconnect = true
        mainHandler.removeCallbacksAndMessages(null)
        stopTalking()
        socket?.close()
        try { player?.stop(); player?.release() } catch (_: Exception) { }
        super.onDestroy()
    }
}
