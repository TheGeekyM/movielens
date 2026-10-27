package com.movielens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.movielens.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cache = HashMap<String, JSONObject?>()
    private var busy = false
    private var lastSeen = ""
    private var shownId = ""

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.status.setOnLongClickListener { askKey(); true }
        b.identify.setOnClickListener { identify() }
        // Tapping the card (IMDb badge included) opens the title in the IMDb app if installed, else the browser.
        b.card.setOnClickListener {
            if (shownId.isNotEmpty()) startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.imdb.com/title/$shownId/")))
        }
        if (prefs.getString("key", "").isNullOrBlank()) askKey()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera() else b.status.text = "Camera permission needed"
    }

    private fun askKey() {
        val omdb = EditText(this).apply { hint = "OMDb API key"; setText(prefs.getString("key", "")) }
        val claude = EditText(this).apply { hint = "Claude API key (for posters without text)"; setText(prefs.getString("claude", "")) }
        AlertDialog.Builder(this).setTitle("API keys")
            .setMessage("OMDb: free at omdbapi.com/apikey.aspx\nClaude: console.anthropic.com (optional, powers ✦ Identify)\n\nLong-press the top pill to change them later.")
            .setView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 0, 48, 0); addView(omdb); addView(claude) })
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("key", omdb.text.toString().trim()).putString("claude", claude.text.toString().trim()).apply()
            }
            .show()
    }

    // Posters with no readable title: ask Claude to recognise the artwork, then look it up on OMDb.
    private fun identify() {
        val key = prefs.getString("claude", "") ?: ""
        if (key.isBlank()) { askKey(); return }
        val frame = b.preview.bitmap ?: return
        if (busy) return
        busy = true
        b.identify.isEnabled = false
        b.status.text = "Identifying with AI…"
        lifecycleScope.launch {
            val guess = runCatching { withContext(Dispatchers.IO) { askClaude(key, frame) } }
                .onFailure { b.status.text = "AI error: ${it.message?.take(60)}" }.getOrNull()
            val hit = guess?.takeIf { it != "UNKNOWN" }?.let { g ->
                val (title, year) = Regex("^(.*?)\\s*\\((\\d{4})").find(g)?.destructured?.let { it.component1() to it.component2() } ?: (g to "")
                lookup(title, year) ?: lookup(title)
            }
            if (hit != null) { show(hit); b.status.text = "Point at another poster or title" }
            else if (guess != null) { hideCard(); b.status.text = if (guess == "UNKNOWN") "Couldn't recognise it, try closer" else "“$guess” isn't on OMDb" }
            b.identify.isEnabled = true
            busy = false
        }
    }

    private fun askClaude(key: String, frame: Bitmap): String {
        val scale = 1024f / maxOf(frame.width, frame.height)
        val small = Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
        val jpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val client = AnthropicOkHttpClient.builder().apiKey(key).build()
        val params = MessageCreateParams.builder()
            .model("claude-opus-5-5")
            .maxTokens(2000L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessageOfContentBlockParams(listOf(
                ContentBlockParam.ofImage(ImageBlockParam.builder().source(
                    Base64ImageSource.builder().data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()).build()),
                ContentBlockParam.ofText(TextBlockParam.builder().text(
                    "This camera frame shows a movie or TV series (poster, still, cover, or screen). " +
                    "Identify it. Reply with exactly one line: Title (Year) — or UNKNOWN if you can't tell.").build())))
            .build()
        val res = client.messages().create(params)
        return res.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim().lines().first().trim()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val preview = Preview.Builder().build().also { it.surfaceProvider = b.preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
                val img = proxy.image
                if (busy || img == null) { proxy.close(); return@setAnalyzer }
                val rot = proxy.imageInfo.rotationDegrees
                val (w, h) = if (rot % 180 == 0) proxy.width to proxy.height else proxy.height to proxy.width
                recognizer.process(InputImage.fromMediaImage(img, rot))
                    .addOnSuccessListener { onText(titleCandidates(it, w, h)) }
                    .addOnCompleteListener { proxy.close() }
            }
            future.get().run { unbindAll(); bindToLifecycle(this@MainActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
        }, ContextCompat.getMainExecutor(this))
    }

    // Require the same text on two consecutive frames before hitting the network.
    private fun onText(candidates: List<String>) {
        val key = candidates.joinToString("|")
        if (candidates.isEmpty() || key != lastSeen) { lastSeen = key; return }
        busy = true
        b.status.text = "Looking up “${candidates[0]}”…"
        lifecycleScope.launch {
            val hit = candidates.firstNotNullOfOrNull { q -> cache.getOrPut(q.lowercase()) { lookup(q) } }
            if (hit != null) { show(hit); b.status.text = "Point at another poster or title" }
            else { hideCard(); b.status.text = "No IMDb match for “${candidates[0]}” · try ✦ Identify" }
            busy = false
        }
    }

    private suspend fun lookup(q: String, year: String = ""): JSONObject? = withContext(Dispatchers.IO) {
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) return@withContext null
        fun get(params: String) = runCatching {
            JSONObject(URL("https://www.omdbapi.com/?apikey=$key&$params").readText())
        }.getOrNull()?.takeIf { it.optString("Response") == "True" }
        val enc = URLEncoder.encode(q, "UTF-8")
        val y = if (year.isNotEmpty()) "&y=$year" else ""
        get("t=$enc$y")?.takeIf { sameTitle(q, it.optString("Title")) }
            ?: get("s=$enc$y")?.optJSONArray("Search")?.let { a -> (0 until a.length()).map(a::getJSONObject) }
                ?.firstOrNull { sameTitle(q, it.optString("Title")) }
                ?.let { get("i=${it.getString("imdbID")}") }
    }

    private fun hideCard() {
        shownId = ""
        b.card.animate().alpha(0f).translationY(80f).setDuration(200).withEndAction { b.card.visibility = android.view.View.GONE }.start()
    }

    private fun show(m: JSONObject) {
        val id = m.optString("imdbID")
        if (id == shownId) return
        shownId = id
        b.title.text = m.optString("Title")
        val type = if (m.optString("Type") == "series") "TV Series" else m.optString("Type").replaceFirstChar { it.uppercase() }
        b.type.text = "$type · ${m.optString("Runtime")}"
        b.year.text = m.optString("Year").replace("–", " – ").let { if (it.endsWith(" – ")) it + "present" else it }
        val rated = m.optString("Rated")
        b.age.text = ageBadge(rated).let { if (it == rated || rated == "N/A") it else "$it  $rated" }
        b.rating.text = m.optString("imdbRating").takeIf { it != "N/A" } ?: "–"
        b.votes.text = shortVotes(m.optString("imdbVotes"))
        b.genres.removeAllViews()
        m.optString("Genre").split(", ").filter { it != "N/A" }.take(3).forEach { g ->
            b.genres.addView(TextView(this).apply {
                text = g; setTextColor(0xFFFFFFFF.toInt()); textSize = 12f
                setBackgroundResource(R.drawable.chip); setPadding(28, 10, 28, 10)
                layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 16 }
            })
        }
        b.card.apply {
            if (visibility != android.view.View.VISIBLE) { alpha = 0f; translationY = 80f; visibility = android.view.View.VISIBLE }
            animate().alpha(1f).translationY(0f).setDuration(250).start()
        }
    }
}
