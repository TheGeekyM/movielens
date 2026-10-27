package com.movielens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.WebSearchTool20260209
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.movielens.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.STREAM_MODE).enableMultipleObjects().build())
    private var client: AnthropicClient? = null
    private var busy = false
    private var lastPoster: Box? = null
    private var lastPosterAt = 0L
    private var pinnedUntil = 0L      // a tapped poster stays framed until then
    private var imdbUrl = ""

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.status.setOnLongClickListener { askKey(); true }
        b.identify.setOnClickListener { identify() }
        b.overlay.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) onTap(e.x, e.y); true }
        // Tapping the card (IMDb badge included) opens the title in the IMDb app if installed, else the browser.
        b.card.setOnClickListener { if (imdbUrl.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(imdbUrl))) }
        if (prefs.getString("claude", "").isNullOrBlank()) askKey()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera() else b.status.text = "Camera permission needed"
    }

    private fun askKey() {
        val claude = EditText(this).apply { hint = "Claude API key"; setText(prefs.getString("claude", "")) }
        AlertDialog.Builder(this).setTitle("Claude API key")
            .setMessage("Get one at console.anthropic.com.\n\nLong-press the top pill to change it later.")
            .setView(LinearLayout(this).apply { setPadding(48, 0, 48, 0); addView(claude, LinearLayout.LayoutParams(-1, -2)) })
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ -> prefs.edit().putString("claude", claude.text.toString().trim()).apply(); client = null }
            .show()
    }

    private fun startCamera() {
        // MlKitAnalyzer hands back boxes already mapped to PreviewView coordinates.
        val controller = LifecycleCameraController(this)
        controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        controller.setImageAnalysisAnalyzer(ContextCompat.getMainExecutor(this),
            MlKitAnalyzer(listOf(detector), CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED, ContextCompat.getMainExecutor(this)) { r ->
                r.getValue(detector)?.let { objs -> onFrame(objs.map { o -> o.boundingBox.let { Box(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) } }) }
            })
        controller.bindToLifecycle(this)
        b.preview.controller = controller
    }

    private fun onFrame(objects: List<Box>) {
        if (busy || SystemClock.uptimeMillis() < pinnedUntil) return
        val w = b.overlay.width.toFloat(); val h = b.overlay.height.toFloat()
        if (w == 0f) return
        val now = SystemClock.uptimeMillis()
        posterAt(objects, w / 2, h / 2, w, h)?.let { lastPoster = it; lastPosterAt = now }
        // Hold a lost poster briefly so the frame doesn't flicker back to the default.
        b.overlay.select(lastPoster?.takeIf { now - lastPosterAt < 700 } ?: defaultFrame(w, h))
    }

    private fun onTap(x: Float, y: Float) {
        if (busy) return
        val w = b.overlay.width.toFloat(); val h = b.overlay.height.toFloat()
        val f = defaultFrame(w, h)
        val dx = x - (f.l + f.r) / 2; val dy = y - (f.t + f.b) / 2
        // ponytail: tap re-uses the last detection near the finger; otherwise centres the default frame on it.
        b.overlay.select(lastPoster?.takeIf { it.has(x, y) } ?: Box(f.l + dx, f.t + dy, f.r + dx, f.b + dy))
        pinnedUntil = SystemClock.uptimeMillis() + 8000
        b.overlay.postDelayed({ identify() }, 350)  // let the frame glide into place first
    }

    private fun setLoading(on: Boolean) {
        busy = on
        b.overlay.loading = on
        b.spinner.visibility = if (on) View.VISIBLE else View.INVISIBLE
        b.identify.isEnabled = !on
        b.identify.animate().scaleX(if (on) 0.82f else 1f).scaleY(if (on) 0.82f else 1f).setDuration(180).start()
    }

    private fun identify() {
        val key = prefs.getString("claude", "") ?: ""
        if (key.isBlank()) { askKey(); return }
        if (busy) return
        val full = b.preview.bitmap ?: return
        val frame = b.overlay.shown() ?: defaultFrame(full.width.toFloat(), full.height.toFloat())
        val c = cropFor(frame, full.width.toFloat(), full.height.toFloat())
        val crop = Bitmap.createBitmap(full, c.l.toInt(), c.t.toInt(), c.w.toInt().coerceAtLeast(1), c.h.toInt().coerceAtLeast(1))
        setLoading(true)
        hideCard()
        b.status.text = "Identifying…"
        lifecycleScope.launch {
            val res = runCatching { withContext(Dispatchers.IO) { askClaude(key, crop) } }
            res.onSuccess { m ->
                if (m != null && m.optBoolean("found")) { show(m); b.status.text = "Frame another poster, then tap ✦" }
                else b.status.text = "Couldn't tell which movie this is. Fill the frame with one poster"
            }.onFailure { b.status.text = "AI error: ${it.message?.take(60)}" }
            pinnedUntil = 0
            setLoading(false)
        }
    }

    private fun askClaude(key: String, img: Bitmap): JSONObject? {
        val scale = minOf(1f, 1024f / maxOf(img.width, img.height))
        val small = Bitmap.createScaledBitmap(img, (img.width * scale).toInt().coerceAtLeast(1), (img.height * scale).toInt().coerceAtLeast(1), true)
        val jpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val api = client ?: AnthropicOkHttpClient.builder().apiKey(key).build().also { client = it }
        fun str() = mapOf("type" to "string")
        val schema = mapOf(
            "type" to "object", "additionalProperties" to false,
            "required" to listOf("found", "title", "year", "type", "imdb_rating", "imdb_votes", "genres", "age_rating", "imdb_id"),
            "properties" to mapOf(
                "found" to mapOf("type" to "boolean"),
                "title" to str(), "year" to mapOf("type" to "string", "description" to "e.g. 2010, or 2019–2024 / 2019– for series"),
                "type" to mapOf("type" to "string", "enum" to listOf("Movie", "TV Series", "Mini Series", "Documentary", "Short")),
                "imdb_rating" to mapOf("type" to "number", "description" to "0 if unknown"),
                "imdb_votes" to mapOf("type" to "integer", "description" to "0 if unknown"),
                "genres" to mapOf("type" to "array", "items" to str()),
                "age_rating" to mapOf("type" to "string", "description" to "certificate such as PG-13, TV-MA, R, U/A, A; empty if unknown"),
                "imdb_id" to mapOf("type" to "string", "description" to "tt… id, empty if unknown"),
            ))
        val params = MessageCreateParams.builder()
            .model("claude-opus-5-5")
            .maxTokens(8000L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW)
                .format(JsonOutputFormat.builder().schema(JsonOutputFormat.Schema.builder()
                    .apply { schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()).build())
                .build())
            .addTool(WebSearchTool20260209.builder().maxUses(3L).allowedDomains(listOf("imdb.com")).build())
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessageOfContentBlockParams(listOf(
                ContentBlockParam.ofImage(ImageBlockParam.builder().source(
                    Base64ImageSource.builder().data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()).build()),
                ContentBlockParam.ofText(TextBlockParam.builder().text(
                    "This is a phone photo of a screen or poster, cropped around one movie or TV series tile. " +
                    "Its title may be written on the artwork or just above/below it. If parts of other tiles show at the edges, " +
                    "ignore them and identify the one in the middle. Then search IMDb for its current rating, vote count, genres and " +
                    "certificate (US if it has one, else its local one). If you can't identify it confidently, set found to false.").build())))
            .build()
        // ponytail: no pause_turn continuation; max 3 searches keeps the server loop well inside one turn.
        val res = api.messages().create(params)
        if (res.stopReason().orElse(null) == StopReason.REFUSAL) return null
        val json = res.content().mapNotNull { it.text().orElse(null)?.text() }.lastOrNull() ?: return null
        return JSONObject(json)
    }

    private fun hideCard() {
        imdbUrl = ""
        b.card.animate().alpha(0f).translationY(80f).setDuration(200).withEndAction { b.card.visibility = View.GONE }.start()
    }

    private fun show(m: JSONObject) {
        val title = m.optString("title")
        val id = m.optString("imdb_id")
        imdbUrl = if (id.startsWith("tt")) "https://www.imdb.com/title/$id/" else "https://www.imdb.com/find/?q=" + URLEncoder.encode(title, "UTF-8")
        b.title.text = title
        b.type.text = m.optString("type")
        b.year.text = m.optString("year").replace("–", " – ").replace("-", " – ").let { if (it.endsWith(" – ")) it + "present" else it }
        val rated = m.optString("age_rating")
        b.age.text = ageBadge(rated).let { if (it == rated || rated.isEmpty()) it else "$it  $rated" }
        b.rating.text = m.optDouble("imdb_rating", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) } ?: "–"
        b.votes.text = shortVotes(m.optLong("imdb_votes"))
        b.genres.removeAllViews()
        val genres = m.optJSONArray("genres")
        (0 until (genres?.length() ?: 0)).take(3).forEach { i ->
            b.genres.addView(TextView(this).apply {
                text = genres!!.getString(i); setTextColor(0xFFFFFFFF.toInt()); textSize = 12f
                setBackgroundResource(R.drawable.chip); setPadding(28, 10, 28, 10)
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 16 }
            })
        }
        b.card.apply {
            animate().cancel()
            if (visibility != View.VISIBLE) { alpha = 0f; translationY = 80f; visibility = View.VISIBLE }
            animate().alpha(1f).translationY(0f).setDuration(250).start()
        }
    }
}
