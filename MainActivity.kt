package com.nethsara.aiplayer

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.lifecycleScope
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)


class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var currentVideoUri: Uri? = null
    private var aiBusy = false
    private lateinit var playerView: PlayerView
    private lateinit var subtitleOverlay: TextView
    private lateinit var status: TextView
    private lateinit var fileLabel: TextView
    private val videoUris = mutableListOf<Uri>()
    private var cues: List<SubtitleCue> = emptyList()
    private var subtitleDelayMs: Long = 0L
    private var subtitleFontSp: Float = 19f
    private var subtitleOffsetMs = 0L
    private var subtitleSizeSp = 19f
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val position = (player?.currentPosition ?: 0L) - subtitleOffsetMs
            subtitleOverlay.text = cues.firstOrNull { position >= it.startMs && position <= it.endMs }?.text.orEmpty()
            handler.postDelayed(this, 100)
        }
    }

    private val pickVideos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { persistReadAccess(it); if (it !in videoUris) videoUris.add(it) }
            play(uris.last())
        }
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persistReadAccess(it); if (it !in videoUris) videoUris.add(it); play(it) }
    }
    private val pickSrt = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::loadSrt) }
    private val saveSrt = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri -> uri?.let(::writeSrt) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        player = ExoPlayer.Builder(this).build().also { playerView.player = it }
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { status.text = if (isPlaying) "▶ ධාවනය වෙමින්" else "Ⅱ විරාමය" }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                status.text = "Playback දෝෂයක්: codec/format එක device එකට නොගැළපිය හැක."
                toast("මේ වීඩියෝ format/codec එක play කළ නොහැක")
            }
        })
        status.text = "වීඩියෝවක් තෝරන්න • Subtitle ගොනුවක් import කරන්න"
        handler.post(ticker)
    }


    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 18) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))

        val brand = TextView(this).apply { text = "NETHSARA"; textSize = 27f; setTextColor(C.green); setTypeface(null, android.graphics.Typeface.BOLD) }
        val tagline = TextView(this).apply { text = "AI PLAYER  ·  OFFLINE SUBTITLE STUDIO"; textSize = 11f; setTextColor(C.muted); setPadding(0, 0, 0, 12) }
        status = TextView(this).apply { textSize = 13f; setTextColor(C.text); setPadding(0, 4, 0, 8) }
        fileLabel = TextView(this).apply { text = "තෝරාගත් වීඩියෝවක් නැහැ"; textSize = 14f; setTextColor(C.text); setPadding(0, 4, 0, 10) }
        content.addView(brand); content.addView(tagline); content.addView(status); content.addView(fileLabel)

        val videoCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.card); setPadding(8, 8, 8, 8) }
        playerView = PlayerView(this).apply { useController = true; setBackgroundColor(0xFF000000.toInt()); layoutParams = LinearLayout.LayoutParams(-1, dp(230)) }
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        frame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        subtitleOverlay = TextView(this).apply {
            textSize = subtitleSizeSp; gravity = Gravity.CENTER; setTextColor(-1); setPadding(dp(12), dp(8), dp(12), dp(8))
            setShadowLayer(5f, 2f, 2f, 0xFF000000.toInt()); setBackgroundColor(0x9907100B.toInt())
            layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(18), 0, dp(18), dp(58)) }
        }
        frame.addView(subtitleOverlay)
        videoCard.addView(frame)
        content.addView(videoCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        section(content, "VIDEO LIBRARY")
        row(content,
            button("＋  වීඩියෝ එකතු කරන්න") { pickVideos.launch(arrayOf("video/*")) },
            button("▣  Open Video") { pickVideo.launch(arrayOf("video/*")) })
        row(content,
            button("📚  Library") { showLibrary() },
            button("⏹  Stop") { player?.stop(); subtitleOverlay.text = ""; status.text = "Playback නතර කළා" })

        section(content, "SUBTITLE STUDIO")
        row(content,
            button("Import .SRT") { pickSrt.launch(arrayOf("application/x-subrip", "text/plain", "application/octet-stream")) },
            button("Export .SRT") { if (cues.isEmpty()) toast("Export කරන්න subtitles නැහැ") else saveSrt.launch("nethsara_subtitles.srt") })
        row(content,
            button("−  Subtitle Size") { subtitleSizeSp = (subtitleSizeSp - 1f).coerceAtLeast(12f); subtitleOverlay.textSize = subtitleSizeSp },
            button("＋  Subtitle Size") { subtitleSizeSp = (subtitleSizeSp + 1f).coerceAtMost(34f); subtitleOverlay.textSize = subtitleSizeSp })
        row(content,
            button("◀  Subtitle Delay") { subtitleOffsetMs -= 500; status.text = "Subtitle offset: ${subtitleOffsetMs}ms" },
            button("Subtitle Delay  ▶") { subtitleOffsetMs += 500; status.text = "Subtitle offset: ${subtitleOffsetMs}ms" })
        row(content,
            button("Clear subtitles") { cues = emptyList(); subtitleOverlay.text = ""; status.text = "Subtitles ඉවත් කළා" },
            button("Reset delay") { subtitleOffsetMs = 0; status.text = "Subtitle delay reset කළා" })
        row(content, button("Playback Speed") { showSpeedDialog() }, button("Reset speed") { player?.setPlaybackSpeed(1f); status.text = "Speed 1.0x" })

        section(content, "OFFLINE AI")
        val aiButton = button("✦  Generate Sinhala Subtitles (Whisper Offline)") { generateAiSubtitles() }.apply { setTextColor(C.bg); setBackgroundColor(C.green) }
        content.addView(aiButton, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val disclaimer = TextView(this).apply { text = "Whisper model is downloaded once (large file). Speech recognition runs offline after model download. Sinhala translation downloads its ML model on first use; later translation can run offline."; textSize = 11f; setTextColor(C.muted); setPadding(2, 2, 2, 8) }
        content.addView(disclaimer)
        val footer = TextView(this).apply { text = "Video formats: Android/Media3-supported formats and codecs  ·  Playback compatibility varies by device"; textSize = 10f; setTextColor(C.muted); gravity = Gravity.CENTER; setPadding(0, 12, 0, 4) }
        content.addView(footer)
        setContentView(root)
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply { text = title; textSize = 12f; setTextColor(C.green); setPadding(2, dp(12), 2, dp(7)); setTypeface(null, android.graphics.Typeface.BOLD) })
    }
    private fun row(parent: LinearLayout, first: Button, second: Button) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        layout.addView(first, weightLayout()); layout.addView(second, weightLayout())
        parent.addView(layout, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
    }
    private fun weightLayout() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(C.text); setBackgroundColor(C.button); setPadding(dp(6), dp(7), dp(6), dp(7)); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun persistReadAccess(uri: Uri) { try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }

    private fun play(uri: Uri) {
        currentVideoUri = uri
        player?.setMediaItem(MediaItem.fromUri(uri)); player?.prepare(); player?.play()
        fileLabel.text = uri.lastPathSegment?.substringAfterLast('/') ?: "Video selected"
        status.text = "Video loading…"
    }
    private fun loadSrt(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            cues = parseSrt(raw); subtitleOffsetMs = 0
            status.text = "SRT loaded • ${cues.size} subtitles"; toast("Subtitles ${cues.size}ක් import කළා")
        } catch (_: Exception) { toast("SRT ගොනුව කියවන්න බැරි වුණා") }
    }
    private fun writeSrt(uri: Uri) {
        try {
            val text = cues.mapIndexed { index, cue -> "${index + 1}\n${formatTime(cue.startMs)} --> ${formatTime(cue.endMs)}\n${cue.text}\n" }.joinToString("\n")
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }; toast("SRT export කළා")
        } catch (_: Exception) { toast("SRT save කරන්න බැරි වුණා") }
    }
    private fun formatTime(ms: Long): String { val h = ms / 3600000; val m = (ms % 3600000) / 60000; val s = (ms % 60000) / 1000; val milli = ms % 1000; return "%02d:%02d:%02d,%03d".format(h, m, s, milli) }
    private fun parseSrt(raw: String): List<SubtitleCue> {
        val normalized = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        return normalized.trim().split(Regex("\\n\\s*\\n")).mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }; val timeLine = lines.firstOrNull { it.contains("-->") } ?: return@mapNotNull null
            val parts = timeLine.split("-->"); if (parts.size != 2) return@mapNotNull null
            val start = parseTime(parts[0].trim()) ?: return@mapNotNull null
            val end = parseTime(parts[1].trim().substringBefore(' ').trim()) ?: return@mapNotNull null
            val text = lines.drop(lines.indexOf(timeLine) + 1).joinToString("\n").trim()
            if (text.isEmpty() || end <= start) null else SubtitleCue(start, end, text)
        }.sortedBy { it.startMs }
    }
    private fun parseTime(value: String): Long? = try { val p = value.replace(',', '.').split(':'); if (p.size != 3) null else ((p[0].toLong() * 3600 + p[1].toLong() * 60) * 1000 + (p[2].toDouble() * 1000).toLong()) } catch (_: Exception) { null }
    private fun showSpeedDialog() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("Playback speed").setItems(labels) { _, which ->
            player?.setPlaybackSpeed(speeds[which]); status.text = "Playback speed ${speeds[which]}x"
        }.show()
    }
    private fun showLibrary() {
        if (videoUris.isEmpty()) { toast("Videos එකතු කරලා නැහැ"); return }
        val labels = videoUris.mapIndexed { index, uri -> "${index + 1}. ${uri.lastPathSegment ?: uri}" }
        android.app.AlertDialog.Builder(this).setTitle("Video library (${videoUris.size})").setItems(labels.toTypedArray()) { _, which -> play(videoUris[which]) }.setNegativeButton("Close", null).show()
    }
    private fun generateAiSubtitles() {
        val uri = currentVideoUri
        if (uri == null) { toast("මුලින් Video එකක් තෝරන්න"); return }
        if (aiBusy) return
        aiBusy = true
        status.text = "AI: Model download / audio extract ආරම්භ කරනවා…"
        lifecycleScope.launch {
            var model: dev.ffmpegkit.whisper.WhisperModel? = null
            try {
                val modelFile = WhisperPipeline.ensureModel(this@MainActivity)
                status.text = "AI: Video audio extract කරනවා…"
                val wav = WhisperPipeline.videoToWav(this@MainActivity, uri)
                status.text = "AI: Whisper speech recognition…"
                model = Whisper.loadModel(this@MainActivity, modelFile.absolutePath)
                val result = Whisper.transcribe(model!!, wav.absolutePath, WhisperConfig(language = "auto"))
                val translated = mutableListOf<SubtitleCue>()
                val segments = result.segments
                for ((i, segment) in segments.withIndex()) {
                    val text = segment.text.trim()
                    if (text.isBlank()) continue
                    status.text = "AI: Sinhala translation ${i + 1}/${segments.size}…"
                    val sinhala = withContext(Dispatchers.IO) { translateToSinhala(text) }
                    translated.add(SubtitleCue(segment.startMs, segment.endMs, sinhala))
                }
                cues = translated.sortedBy { it.startMs }
                subtitleOffsetMs = 0
                status.text = "සාර්ථකයි • ${cues.size} Sinhala subtitle lines"
                toast("සිංහල Subtitles ${cues.size}ක් සෑදුවා. Export SRT කරන්න.")
            } catch (e: Exception) {
                status.text = "AI subtitle දෝෂයක්: ${e.message ?: "unknown error"}"
                toast("AI subtitles සෑදීම අසාර්ථකයි")
            } finally {
                model?.let { Whisper.releaseModel(it) }
                aiBusy = false
            }
        }
    }



    private fun translateToSinhala(text: String): String {
        val encoded = java.net.URLEncoder.encode(text, "UTF-8")
        val url = java.net.URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=si&dt=t&q=$encoded")
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 25000; connection.requestMethod = "GET"
        try {
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = org.json.JSONArray(body)
            val segments = json.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segments.length()) { val part = segments.optJSONArray(i); if (part != null && !part.isNull(0)) sb.append(part.optString(0)) }
            return sb.toString().ifBlank { text }
        } finally { connection.disconnect() }
    }

    override fun onDestroy() { handler.removeCallbacks(ticker); playerView.player = null; player?.release(); player = null; super.onDestroy() }

    private object C { const val bg = 0xFF07130F.toInt(); const val card = 0xFF10251B.toInt(); const val green = 0xFF56E39A.toInt(); const val button = 0xFF1B3B2A.toInt(); const val text = 0xFFE8F5EC.toInt(); const val muted = 0xFF91B7A3.toInt() }
}
