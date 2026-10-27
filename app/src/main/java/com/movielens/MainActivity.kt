package com.movielens

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.view.LifecycleCameraController
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
import com.anthropic.models.messages.WebSearchTool20250305
import com.movielens.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var b: ActivityMainBinding
    private val store by lazy { Store(this) }
    private val sensors by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private var client: AnthropicClient? = null
    private var shown: JSONObject? = null     // scan item on the card
    private var shownData: JSONObject? = null // its IMDb data, if any
    private val shownKey get() = shown?.let(::key).orEmpty()
    private var items: List<JSONObject> = emptyList()  // titles from the last scan, same order as b.tags.tags
    private var scanJob: Job? = null
    private var lastMove = 0L
    private var movedSinceScan = true
    private var pending = 0                   // IMDb lookups in flight (scan progress)
    private val loading = HashSet<String>()   // keys whose IMDb lookup is running
    private var touchX = 0f
    private var touchY = 0f

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.menu.setOnClickListener { menu() }
        b.pill.setOnLongClickListener { settings(); true }
        // Tap a poster to focus it, long-press to add it to the watchlist.
        b.tags.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) { touchX = e.x; touchY = e.y }; false }
        b.tags.setOnClickListener { b.tags.at(touchX, touchY).let { if (it >= 0) focus(it) } }
        b.tags.setOnLongClickListener { b.tags.at(touchX, touchY).let { if (it >= 0) { focus(it); toggleSaved() } }; true }
        b.card.setOnClickListener { openImdb() }
        b.imdb.setOnClickListener { openImdb() }
        b.reload.setOnClickListener { retry() }
        b.save.setOnClickListener { toggleSaved() }
        b.trailer.setOnClickListener {
            val m = shown ?: return@setOnClickListener
            open("https://www.youtube.com/results?search_query=" + URLEncoder.encode("${m.optString("title")} ${m.optString("year").take(4)} trailer", "UTF-8"))
        }
        if (store.apiKey.isBlank()) settings()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)

        // Auto-scan loop: once the phone has moved and then held still, scan what's on screen.
        lifecycleScope.launch {
            while (isActive) {
                delay(250)
                val still = SystemClock.uptimeMillis() - lastMove > 700
                if (still && movedSinceScan && scanJob?.isActive != true && store.apiKey.isNotBlank() && b.preview.bitmap != null) scan()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // ponytail: no gyroscope -> never "moves", so it scans once; move-to-rescan needs the sensor.
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() { super.onPause(); sensors.unregisterListener(this) }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        if (sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) < 0.35f) return  // rad/s; hand tremor stays below this
        lastMove = SystemClock.uptimeMillis()
        if (movedSinceScan) return
        // The view moved: tags no longer line up, so drop them and rescan once steady again.
        movedSinceScan = true
        scanJob?.cancel()
        b.tags.scanning = false
        b.tags.tags = emptyList(); items = emptyList()
        busy(false, "Hold steady on the screen")
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera() else b.status.text = "Camera permission needed"
    }

    private fun startCamera() {
        val controller = LifecycleCameraController(this)
        controller.bindToLifecycle(this)
        b.preview.controller = controller
    }

    private fun busy(on: Boolean, text: String) {
        b.spinner.visibility = if (on) View.VISIBLE else View.GONE
        b.status.text = text
    }

    private fun key(m: JSONObject) = m.optString("title").lowercase() + "|" + m.optString("year").take(4)

    private fun open(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun imdbUrl(m: JSONObject, d: JSONObject?): String {
        val id = d?.optString("imdb_id").orEmpty()
        return if (id.startsWith("tt")) "https://www.imdb.com/title/$id/" else "https://www.imdb.com/find/?q=" + URLEncoder.encode(m.optString("title"), "UTF-8")
    }

    private fun openImdb() { shown?.let { open(imdbUrl(it, shownData)) } }

    // ---- Scanning ---------------------------------------------------------------------------

    private fun scan() {
        val frame = b.preview.bitmap ?: return
        movedSinceScan = false
        busy(true, "Scanning…")
        b.tags.scanning = true
        scanJob = lifecycleScope.launch {
            // Step 1: one vision call finds every title on screen and where it is.
            val found = runCatching { withContext(Dispatchers.IO) { findAll(frame) } }
                .also { b.tags.scanning = false }
                .onFailure { busy(false, "AI error: ${it.message?.take(50)}") }.getOrNull() ?: return@launch
            if (found.isEmpty()) { hideCard(); busy(false, "No movies found. Point at posters and hold steady"); return@launch }
            val w = b.tags.width.toFloat(); val h = b.tags.height.toFloat()
            items = found
            b.tags.tags = found.map { m ->
                Tags.Tag(toView(m.optInt("x0"), m.optInt("y0"), m.optInt("x1"), m.optInt("y1"), w, h)).apply { store.cached(key(m))?.let { rating = ratingOf(it) } }
            }
            found.indices.forEach(::styleTag)
            focus(focusIndex(b.tags.tags.map { it.box }, w / 2, h / 2))

            // Step 2: IMDb numbers per title, several at once; each tag fills in as its lookup lands.
            val todo = found.indices.filter { store.cached(key(found[it])) == null }
            pending = todo.size
            progress()
            val gate = Semaphore(4)
            todo.forEach { i -> launch { gate.withPermit { fetch(i, found[i], 3) }; pending--; progress() } }
        }
    }

    /** IMDb lookup for scan item [i]; updates its tag and, if it's on the card, the card. */
    private suspend fun fetch(i: Int, m: JSONObject, searches: Long) {
        val k = key(m)
        loading += k
        if (shownKey == k) show(m, null)
        val d = runCatching { withContext(Dispatchers.IO) { details(m, searches) } }.getOrNull()
        loading -= k
        if (d != null && ratingOf(d) != "–") {  // don't remember misses, so a retry or rescan tries again
            store.putCache(k, d)
            store.push("history", entry(m, d), 200)
        }
        if (items.getOrNull(i) === m) { b.tags.tags.getOrNull(i)?.rating = d?.let(::ratingOf) ?: "–"; styleTag(i) }
        if (shownKey == k) show(m, d)
    }

    private fun retry() {
        val i = b.tags.selected
        val m = items.getOrNull(i) ?: return
        if (key(m) in loading) return
        b.tags.tags.getOrNull(i)?.rating = null; b.tags.invalidate()
        lifecycleScope.launch { busy(true, "Retrying IMDb…"); fetch(i, m, 4); progress() }
    }

    private fun progress() =
        if (pending > 0) busy(true, "Getting ratings… ${items.size - pending}/${items.size}") else busy(false, "Tap a poster · long-press to save")

    private fun ratingOf(d: JSONObject) = d.optDouble("imdb_rating", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) } ?: "–"

    private fun genresOf(d: JSONObject?) = d?.optJSONArray("genres")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList()

    /** Apply taste / parental settings to one tag, then move the crown. */
    private fun styleTag(i: Int) {
        val t = b.tags.tags.getOrNull(i) ?: return
        val d = items.getOrNull(i)?.let { store.cached(key(it)) }
        t.dim = d != null && dimmed(d.optDouble("imdb_rating", 0.0).toFloat(), genresOf(d), d.optString("age_rating"),
            store.minRating, genreSet(store.likes), genreSet(store.dislikes), store.parental)
        t.adult = store.parental && d != null && ageBadge(d.optString("age_rating")) == "+18"
        b.tags.best = bestIndex(b.tags.tags.map { if (it.dim) null else it.rating?.toFloatOrNull() })
        b.tags.invalidate()
    }

    /** Full card for one poster; the rest keep their small rating chips. */
    private fun focus(i: Int) {
        val m = items.getOrNull(i) ?: return
        b.tags.selected = i
        show(m, store.cached(key(m)))
    }

    // ---- Claude -----------------------------------------------------------------------------

    private fun api(): AnthropicClient = client ?: AnthropicOkHttpClient.builder().apiKey(store.apiKey)
        .apply { if (store.server.isNotBlank()) baseUrl(store.server.trimEnd('/')) }  // proxy holds the real key
        .build().also { client = it }

    private fun schema(props: Map<String, Map<String, Any>>) = JsonOutputFormat.Schema.builder().apply {
        mapOf("type" to "object", "additionalProperties" to false, "required" to props.keys.toList(), "properties" to props)
            .forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
    }.build()

    /** [searches] = max web searches; 0 means no web search tool. */
    private fun ask(content: List<ContentBlockParam>, schema: JsonOutputFormat.Schema, searches: Long): JSONObject? {
        val params = MessageCreateParams.builder()
            .model("claude-haiku-5-5")
            .maxTokens(8000L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).format(JsonOutputFormat.builder().schema(schema).build()).build())
            .apply {
                if (searches > 0) addTool(WebSearchTool20250305.builder().maxUses(searches)
                    .allowedDomains(listOf("imdb.com", "rottentomatoes.com", "metacritic.com", "justwatch.com")).build())
            }
            .addUserMessageOfContentBlockParams(content)
            .build()
        // ponytail: no pause_turn continuation; 3–4 searches max keeps the server loop inside one turn.
        val res = api().messages().create(params)
        if (res.stopReason().orElse(null) == StopReason.REFUSAL) return null
        return res.content().mapNotNull { it.text().orElse(null)?.text() }.lastOrNull()?.let(::JSONObject)
    }

    private fun findAll(img: Bitmap): List<JSONObject> {
        val scale = minOf(1f, 1568f / maxOf(img.width, img.height))
        val small = Bitmap.createScaledBitmap(img, (img.width * scale).toInt().coerceAtLeast(1), (img.height * scale).toInt().coerceAtLeast(1), true)
        val jpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val coord = mapOf("type" to "integer", "description" to "0–1000, normalised to image width (x) or height (y)")
        val item = mapOf(
            "title" to mapOf("type" to "string"),
            "year" to mapOf("type" to "string", "description" to "e.g. 2010, or 2019–2024 / 2019– for series"),
            "type" to mapOf("type" to "string", "enum" to listOf("Movie", "TV Series", "Mini Series", "Documentary", "Short")),
            "x0" to coord, "y0" to coord, "x1" to coord, "y1" to coord,
        )
        val res = ask(listOf(
            ContentBlockParam.ofImage(ImageBlockParam.builder().source(
                Base64ImageSource.builder().data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()).build()),
            ContentBlockParam.ofText(TextBlockParam.builder().text(
                "This is a phone camera photo of a screen or posters showing movie / TV series tiles. List every tile you can " +
                "confidently identify (a title may be written on the artwork or just above/below it); skip tiles you can't. " +
                "For each, give the bounding box of its artwork as x0,y0 (top-left) and x1,y1 (bottom-right).").build())),
            schema(mapOf("items" to mapOf("type" to "array", "items" to mapOf(
                "type" to "object", "additionalProperties" to false, "required" to item.keys.toList(), "properties" to item)))),
            0) ?: return emptyList()
        val arr = res.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map(arr::getJSONObject)
    }

    private fun details(m: JSONObject, searches: Long): JSONObject? {
        val country = Locale.getDefault().displayCountry.ifBlank { "the US" }
        return ask(listOf(
            ContentBlockParam.ofText(TextBlockParam.builder().text(
                "Look up \"${m.optString("title")}\" (${m.optString("type")}, ${m.optString("year")}). From IMDb: current rating, " +
                "vote count, genres, certificate (US if it has one, else its local one). Also its Rotten Tomatoes and Metacritic scores, " +
                "a spoiler-free one or two sentence premise, and the streaming services it's on in $country (JustWatch). " +
                "The year may be missing or slightly off; match on the title and type.").build())),
            schema(mapOf(
                "imdb_rating" to mapOf("type" to "number", "description" to "0 if unknown"),
                "imdb_votes" to mapOf("type" to "integer", "description" to "0 if unknown"),
                "genres" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                "age_rating" to mapOf("type" to "string", "description" to "certificate such as PG-13, TV-MA, R, U/A, A; empty if unknown"),
                "imdb_id" to mapOf("type" to "string", "description" to "tt… id, empty if unknown"),
                "rotten_tomatoes" to mapOf("type" to "string", "description" to "Tomatometer like 92%, empty if unknown"),
                "metacritic" to mapOf("type" to "integer", "description" to "0 if unknown"),
                "plot" to mapOf("type" to "string", "description" to "spoiler-free, max 2 sentences"),
                "where_to_watch" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "streaming service names"),
            )), searches)
    }

    // ---- Card -------------------------------------------------------------------------------

    private fun hideCard() {
        shown = null; shownData = null
        b.card.animate().alpha(0f).translationY(80f).setDuration(200).withEndAction { b.card.visibility = View.GONE }.start()
    }

    /** Card from the scan (title/year/type); [d] is the IMDb data, null if still loading or the lookup failed. */
    private fun show(m: JSONObject, d: JSONObject?) {
        shown = m; shownData = d
        val year = m.optString("year").replace("–", " – ").replace("-", " – ").let { if (it.endsWith(" – ")) it + "present" else it }
        b.type.text = listOf(m.optString("type"), year).filter { it.isNotBlank() }.joinToString("  ·  ")
        b.title.text = m.optString("title")
        val rated = d?.optString("age_rating").orEmpty()
        b.age.visibility = if (d == null) View.GONE else View.VISIBLE
        b.age.text = ageBadge(rated).let { if (it == rated || rated.isEmpty()) it else "$it  $rated" }
        val busy = shownKey in loading
        val missing = !busy && (d == null || ratingOf(d) == "–")
        if (busy) b.ring.setLoading() else b.ring.setValue(d?.optDouble("imdb_rating", 0.0)?.toFloat() ?: 0f)
        b.votes.text = when { busy -> "Fetching IMDb…"; missing -> "No rating found"; else -> shortVotes(d!!.optLong("imdb_votes")) }
        b.reload.visibility = if (missing) View.VISIBLE else View.GONE

        val rt = d?.optString("rotten_tomatoes").orEmpty()
        val mc = d?.optInt("metacritic") ?: 0
        b.scores.text = listOfNotNull(rt.takeIf { it.isNotBlank() }?.let { "🍅 $it" }, mc.takeIf { it > 0 }?.let { "Ⓜ $it" }).joinToString("     ")
        b.scores.visibility = if (b.scores.text.isEmpty()) View.GONE else View.VISIBLE
        b.plot.text = d?.optString("plot").orEmpty()
        b.plot.visibility = if (b.plot.text.isEmpty()) View.GONE else View.VISIBLE
        val watch = d?.optJSONArray("where_to_watch")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
        b.watch.text = if (watch.isEmpty()) "" else "📺  " + watch.take(4).joinToString(" · ")
        b.watch.visibility = if (watch.isEmpty()) View.GONE else View.VISIBLE
        b.save.text = if (isSaved(m)) "★  Saved" else "☆  Save"

        b.genres.removeAllViews()
        genresOf(d).take(3).forEach { g ->
            b.genres.addView(TextView(this).apply {
                text = g; setTextColor(0xFFFFFFFF.toInt()); textSize = 12f
                setBackgroundResource(R.drawable.chip); setPadding(24, 8, 24, 8)
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 16 }
            })
        }
        b.card.apply {
            if (visibility == View.VISIBLE && alpha == 1f) return@apply
            animate().cancel()
            if (visibility != View.VISIBLE) { alpha = 0f; translationY = 80f; visibility = View.VISIBLE }
            animate().alpha(1f).translationY(0f).setDuration(420).setInterpolator(OvershootInterpolator(1.1f)).start()
        }
    }

    // ---- Watchlist, history, settings --------------------------------------------------------

    private fun entry(m: JSONObject, d: JSONObject?) = JSONObject()
        .put("key", key(m)).put("title", m.optString("title")).put("year", m.optString("year")).put("type", m.optString("type"))
        .put("rating", d?.let(::ratingOf) ?: "–").put("url", imdbUrl(m, d)).put("at", System.currentTimeMillis())

    private fun isSaved(m: JSONObject) = store.list("watchlist").any { it.optString("key") == key(m) }

    private fun toggleSaved() {
        val m = shown ?: return
        if (isSaved(m)) {
            store.save("watchlist", store.list("watchlist").filter { it.optString("key") != key(m) })
            Toast.makeText(this, "Removed from watchlist", Toast.LENGTH_SHORT).show()
        } else {
            store.push("watchlist", entry(m, shownData), 500)
            Toast.makeText(this, "★ Saved to watchlist", Toast.LENGTH_SHORT).show()
        }
        b.save.text = if (isSaved(m)) "★  Saved" else "☆  Save"
    }

    /** Bottom sheet: tinted line icons, live subtitles, a real switch for parental mode. */
    private fun menu() {
        val dp = resources.displayMetrics.density
        val sheet = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.sheet)
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt())
        }
        box.addView(View(this).apply { setBackgroundResource(R.drawable.handle) },
            LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (14 * dp).toInt() })

        fun row(icon: Int, tint: Int, title: String, sub: String, trailing: View? = null, onTap: (() -> Unit)?) {
            val r = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * dp).toInt(), (11 * dp).toInt(), (10 * dp).toInt(), (11 * dp).toInt())
                background = getDrawable(TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId)
                if (onTap != null) setOnClickListener { sheet.dismiss(); onTap() }
            }
            r.addView(ImageView(this).apply {
                setImageResource(icon); imageTintList = ColorStateList.valueOf(tint)
                setBackgroundResource(R.drawable.icon_bg); backgroundTintList = ColorStateList.valueOf(tint and 0x00FFFFFF or 0x26000000)
                setPadding((9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt())
            }, LinearLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt()))
            r.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = title; setTextColor(0xFFFFFFFF.toInt()); textSize = 16f; typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(context).apply { text = sub; setTextColor(0x99FFFFFF.toInt()); textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (14 * dp).toInt() })
            r.addView(trailing ?: ImageView(this).apply { setImageResource(R.drawable.ic_chevron); imageTintList = ColorStateList.valueOf(0x66FFFFFF) },
                LinearLayout.LayoutParams(-2, -2))
            box.addView(r)
        }

        val saved = store.list("watchlist").size
        val seen = store.list("history").size
        val taste = listOfNotNull(
            store.minRating.takeIf { it > 0 }?.let { "Min %.1f".format(it) },
            store.likes.takeIf { it.isNotBlank() }?.let { "likes ${it.trim()}" },
            store.dislikes.takeIf { it.isNotBlank() }?.let { "hiding ${it.trim()}" }).joinToString(" · ").ifEmpty { "Off" }
        row(R.drawable.ic_bookmark, 0xFFF5C518.toInt(), "Watchlist", if (saved == 0) "Long-press a poster to save it" else "$saved saved") { showList("watchlist", "Watchlist") }
        row(R.drawable.ic_history, 0xFF5AA9FF.toInt(), "History", if (seen == 0) "Nothing yet" else "$seen titles looked up") { showList("history", "History") }
        row(R.drawable.ic_tune, 0xFF2ECC71.toInt(), "Taste filter", taste) { taste() }
        val sw = SwitchCompat(this).apply {
            isChecked = store.parental
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFFF5C518.toInt(), 0xFFBDBDBD.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x80F5C518.toInt(), 0x40FFFFFF))
            setOnCheckedChangeListener { _, on -> store.parental = on; items.indices.forEach(::styleTag) }
        }
        row(R.drawable.ic_shield, 0xFFE5484D.toInt(), "Parental mode", "Fade out 18+ titles", sw, null)
        row(R.drawable.ic_key, 0xFFB18CFF.toInt(), "API settings", if (store.server.isBlank()) "Direct to Claude" else "Via your server") { settings() }

        sheet.setContentView(box)
        sheet.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(R.style.SheetAnim)
            setDimAmount(0.5f)
        }
        sheet.show()
    }

    private fun showList(name: String, title: String) {
        val l = store.list(name)
        if (l.isEmpty()) { Toast.makeText(this, "$title is empty", Toast.LENGTH_SHORT).show(); return }
        val rows = l.map { "★ ${it.optString("rating")}   ${it.optString("title")}  (${it.optString("year").take(4)})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title)
            .setItems(rows) { _, i ->
                val e = l[i]
                AlertDialog.Builder(this).setTitle(e.optString("title"))
                    .setPositiveButton("Open on IMDb") { _, _ -> open(e.optString("url")) }
                    .setNegativeButton("Remove") { _, _ -> store.save(name, l.filterIndexed { j, _ -> j != i }) }
                    .show()
            }
            .setNeutralButton("Clear all") { _, _ -> store.save(name, emptyList()) }
            .show()
    }

    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply { this.hint = hint; setText(value); inputType = type }

    private fun form(vararg fields: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(56, 8, 56, 0)
        fields.forEach { addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun taste() {
        val min = field("Minimum IMDb rating (e.g. 7)", if (store.minRating > 0) store.minRating.toString() else "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val likes = field("Genres you like (comma separated)", store.likes)
        val dislikes = field("Genres to hide (comma separated)", store.dislikes)
        AlertDialog.Builder(this).setTitle("Taste filter")
            .setMessage("Posters that don't match fade out, and the 👑 goes to the best of the rest.")
            .setView(form(min, likes, dislikes))
            .setPositiveButton("Save") { _, _ ->
                store.minRating = min.text.toString().toFloatOrNull()?.coerceIn(0f, 10f) ?: 0f
                store.likes = likes.text.toString(); store.dislikes = dislikes.text.toString()
                items.indices.forEach(::styleTag)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun settings() {
        val key = field("Claude API key (or app token for your server)", store.apiKey)
        val server = field("Server URL (optional, see server/proxy.php)", store.server, InputType.TYPE_TEXT_VARIATION_URI)
        AlertDialog.Builder(this).setTitle("API settings")
            .setMessage("Get a key at console.anthropic.com. With a server URL, the real key stays on your server.")
            .setView(form(key, server))
            .setCancelable(store.apiKey.isNotBlank())
            .setPositiveButton("Save") { _, _ -> store.apiKey = key.text.toString().trim(); store.server = server.text.toString().trim(); client = null }
            .show()
    }
}
