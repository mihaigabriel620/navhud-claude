package com.mihai.navhud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mihai.navhud.nav.LatLon
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Destination search.
 *
 * Two things make this feel like a nav app rather than a form. The list is
 * never empty — it opens on where you have been, because in practice you drive
 * to the same six places — and there is no Search button: results appear while
 * you type, so the flow is type, glance, tap, drive.
 *
 * "While you type" has to be handled carefully against a public geocoder.
 * Firing a request per keystroke would be both rude and useless, so keystrokes
 * are coalesced: nothing goes out until you have paused, and a reply for a
 * query you have already typed past is dropped rather than drawn.
 */
class SearchActivity : AppCompatActivity() {

    companion object {
        const val RESULT_LAT = "lat"
        const val RESULT_LON = "lon"
        const val RESULT_LABEL = "label"

        private const val PREFS = "navhud"
        private const val KEY_RECENTS = "recents"
        private const val KEY_HOME = "place_home"
        private const val KEY_WORK = "place_work"
        private const val MAX_RECENTS = 12

        /** Wait for a pause in typing before asking anyone anything. */
        private const val DEBOUNCE_MS = 350L

        /** Below this a query matches half of Belgium. */
        private const val MIN_QUERY = 3

        private const val REQ_SPEECH = 21
    }

    /** What the caller wanted: a destination, or a shortcut to set. */
    private enum class Mode { NAVIGATE, SET_HOME, SET_WORK }

    private lateinit var input: EditText
    private lateinit var list: ListView
    private lateinit var spinner: ProgressBar
    private lateinit var hint: TextView
    private lateinit var emptyState: View
    private lateinit var emptyIcon: android.widget.ImageView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var emptyAction: android.widget.Button
    private lateinit var clearBtn: android.widget.ImageView
    private lateinit var homeSub: TextView
    private lateinit var workSub: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** Bumped per search; a reply carrying an old number is stale. */
    private val queryGeneration = AtomicInteger(0)

    private data class Place(
        val label: String,
        val lat: Double,
        val lon: Double,
        val subtitle: String = "",
        val recent: Boolean = false
    )

    private var shown = mutableListOf<Place>()
    private lateinit var adapter: PlaceAdapter
    private var mode = Mode.NAVIGATE
    private var here: LatLon? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        input = findViewById(R.id.query)
        list = findViewById(R.id.results)
        spinner = findViewById(R.id.spinner)
        hint = findViewById(R.id.hint)
        emptyState = findViewById(R.id.emptyState)
        emptyIcon = findViewById(R.id.emptyIcon)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptyBody = findViewById(R.id.emptyBody)
        emptyAction = findViewById(R.id.emptyAction)
        clearBtn = findViewById(R.id.clear)
        homeSub = findViewById(R.id.homeSub)
        workSub = findViewById(R.id.workSub)

        adapter = PlaceAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> if (pos < shown.size) choose(shown[pos]) }

        findViewById<android.widget.ImageView>(R.id.back)
            .setOnClickListener { onBackPressedCompat() }
        clearBtn.setOnClickListener { input.setText(""); input.requestFocus() }
        findViewById<android.widget.ImageView>(R.id.mic).setOnClickListener { speak() }

        findViewById<LinearLayout>(R.id.homeChip).setOnClickListener { shortcut(KEY_HOME, Mode.SET_HOME) }
        findViewById<LinearLayout>(R.id.workChip).setOnClickListener { shortcut(KEY_WORK, Mode.SET_WORK) }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                clearBtn.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                ui.removeCallbacks(runSearch)
                if (q.length < MIN_QUERY) {
                    queryGeneration.incrementAndGet()   // abandon anything in flight
                    spinner.visibility = View.GONE
                    showRecents()
                } else {
                    ui.postDelayed(runSearch, DEBOUNCE_MS)
                }
            }
        })
        // The keyboard's search key just means "don't wait for the debounce".
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                ui.removeCallbacks(runSearch)
                hideKeyboard()
                search(input.text.toString().trim())
                true
            } else false
        }

        io.execute {
            val l = lastKnown()
            ui.post { here = l; refreshShortcuts(); adapter.notifyDataSetChanged() }
        }
        refreshShortcuts()
        showRecents()
        input.requestFocus()
    }

    override fun onDestroy() {
        io.shutdownNow()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val runSearch = Runnable { search(input.text.toString().trim()) }

    /**
     * Back out of "setting Home" without leaving the screen, so a mis-tap on
     * the shortcut is one press to undo rather than a saved wrong address.
     */
    private fun onBackPressedCompat() {
        if (mode != Mode.NAVIGATE) {
            mode = Mode.NAVIGATE
            input.setText("")
            showRecents()
            return
        }
        finish()
    }

    @Deprecated("kept for API 24; the predictive-back API is 33+")
    override fun onBackPressed() {
        if (mode != Mode.NAVIGATE) { onBackPressedCompat(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---- shortcuts ---------------------------------------------------------

    private fun refreshShortcuts() {
        val h = loadPlace(KEY_HOME)
        val w = loadPlace(KEY_WORK)
        homeSub.text = h?.let { shortLabel(it) } ?: getString(R.string.set_location)
        workSub.text = w?.let { shortLabel(it) } ?: getString(R.string.set_location)
        homeSub.setTextColor(if (h != null) 0xFFFF9D00.toInt() else 0xFF8A8F97.toInt())
        // Work was left at the layout's grey, which is the same grey this uses
        // to mean "not set" -- so a saved Work address looked unsaved next to
        // a saved Home in amber.
        workSub.setTextColor(if (w != null) 0xFFFF9D00.toInt() else 0xFF8A8F97.toInt())
    }

    private fun shortLabel(p: Place): String {
        val d = here?.let { Geo.haversine(it.lat, it.lon, p.lat, p.lon) } ?: return p.label
        return if (d < 1000) "${d.toInt()} m" else "%.0f km".format(d / 1000.0)
    }

    private fun shortcut(key: String, setMode: Mode) {
        val saved = loadPlace(key)
        if (saved != null) { choose(saved, remember = false); return }
        mode = setMode
        input.setText("")            // fires the watcher, which calls showRecents
        showRecents()                // ...and this sets the prompt for the mode
        input.requestFocus()
    }

    // ---- searching ---------------------------------------------------------

    private fun showRecents() {
        shown = loadRecents().map { it.copy(recent = true) }.toMutableList()
        adapter.notifyDataSetChanged()
        // While setting a shortcut the header is the only thing telling you
        // what tapping a result will do, so it must survive this. setText("")
        // fires the watcher even when the field is already empty, which is
        // exactly how the prompt used to vanish in the same frame it appeared.
        hint.text = when (mode) {
            Mode.SET_HOME -> getString(R.string.set_home_hint)
            Mode.SET_WORK -> getString(R.string.set_work_hint)
            Mode.NAVIGATE ->
                getString(if (shown.isEmpty()) R.string.type_an_address else R.string.recent)
        }
        if (shown.isEmpty()) {
            // No key is a different problem from no history, and it is one the
            // driver can actually fix -- so say which it is.
            if (Prefs.mapboxToken(this).isBlank()) {
                showEmpty(R.drawable.ic_settings, R.string.empty_token_title,
                    getString(R.string.empty_token_body), R.string.empty_token_action) {
                    startActivity(Intent(this, MainActivity::class.java))
                }
            } else {
                showEmpty(R.drawable.ic_search, R.string.empty_search_title,
                    getString(R.string.empty_search_body))
            }
        } else hideEmpty()
    }

    /**
     * The empty state, which is a screen and not an absence.
     *
     * An icon, a heading, a sentence that says what to do next, and an action
     * where there is one to offer. Before this, "type something", "nothing
     * matched" and "the lookup failed" were one line of text each above the
     * same sixty per cent of black.
     */
    private fun showEmpty(
        icon: Int, title: Int, body: String, action: Int? = null, onAction: (() -> Unit)? = null
    ) {
        emptyState.visibility = View.VISIBLE
        list.visibility = View.GONE
        emptyIcon.setImageResource(icon)
        emptyTitle.setText(title)
        emptyBody.text = body
        if (action != null && onAction != null) {
            emptyAction.visibility = View.VISIBLE
            emptyAction.setText(action)
            emptyAction.setOnClickListener { onAction() }
        } else emptyAction.visibility = View.GONE
    }

    private fun hideEmpty() {
        emptyState.visibility = View.GONE
        list.visibility = View.VISIBLE
    }

    private fun search(q: String) {
        if (q.length < MIN_QUERY) { showRecents(); return }
        val gen = queryGeneration.incrementAndGet()
        spinner.visibility = View.VISIBLE
        io.execute {
            val result = runCatching {
                com.mihai.navhud.nav.Geocoders.search(
                    query = q,
                    near = here ?: lastKnown(),
                    languageCode = Prefs.phrases(this).code,
                    mapboxToken = Prefs.mapboxToken(this).ifBlank { null },
                    googleKey = Prefs.googleKey(this).ifBlank { null },
                    regionHint = Prefs.homeCountry(this)
                ).map { r -> Place(titleOf(r.label), r.lat, r.lon, subtitleOf(r.label)) }
            }
            val places = result.getOrDefault(emptyList())
            val failed = result.isFailure

            ui.post {
                // A slow reply for a query the driver has already typed past is
                // worse than no reply: it replaces a good list with a stale one.
                if (gen != queryGeneration.get()) return@post
                spinner.visibility = View.GONE
                shown = places.toMutableList()
                adapter.notifyDataSetChanged()
                // "Nothing found" and "I could not ask" are different things.
                // A head unit that has lost its data link, or a Mapbox token
                // that has expired, used to be reported as an empty result --
                // so the driver retyped a perfectly good address instead of
                // knowing there was no connection.
                hint.text = when {
                    failed -> getString(R.string.search_unavailable)
                    places.isEmpty() -> getString(R.string.nothing_found, q)
                    else -> getString(R.string.results)
                }
                when {
                    failed -> showEmpty(R.drawable.ic_close_grey,
                        R.string.empty_offline_title, getString(R.string.empty_offline_body))
                    places.isEmpty() -> showEmpty(R.drawable.ic_search,
                        R.string.empty_none_title, getString(R.string.empty_none_body, q))
                    else -> hideEmpty()
                }
            }
        }
    }

    /**
     * "Avenue de la Couronne 329A, 1050 Ixelles" reads as a street on the first
     * line and a commune on the second, which is how the eye scans a list.
     */
    private fun titleOf(label: String) = label.substringBefore(',').trim().ifBlank { label }

    private fun subtitleOf(label: String): String {
        val rest = label.substringAfter(',', "").trim()
        return rest.split(',').joinToString(", ") { it.trim() }.take(60)
    }

    private fun choose(p: Place, remember: Boolean = true) {
        when (mode) {
            Mode.SET_HOME -> { savePlace(KEY_HOME, p); mode = Mode.NAVIGATE; refreshShortcuts()
                               toast(getString(R.string.home_saved)); showRecents(); input.setText("") }
            Mode.SET_WORK -> { savePlace(KEY_WORK, p); mode = Mode.NAVIGATE; refreshShortcuts()
                               toast(getString(R.string.work_saved)); showRecents(); input.setText("") }
            Mode.NAVIGATE -> {
                if (remember) saveRecent(p)
                setResult(Activity.RESULT_OK, Intent()
                    .putExtra(RESULT_LAT, p.lat)
                    .putExtra(RESULT_LON, p.lon)
                    .putExtra(RESULT_LABEL, listOf(p.label, p.subtitle)
                        .filter { it.isNotBlank() }.joinToString(", ")))
                finish()
            }
        }
    }

    // ---- voice -------------------------------------------------------------

    private fun speak() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                      RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.phrases(this).locale.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.dest_hint))
        runCatching { startActivityForResult(i, REQ_SPEECH) }
            .onFailure { toast(getString(R.string.no_speech)) }
    }

    @Deprecated("startActivityForResult is fine for one screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK) return
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim().orEmpty()
        if (said.isNotEmpty()) {
            input.setText(said)
            input.setSelection(said.length)
            ui.removeCallbacks(runSearch)
            search(said)
        }
    }

    // ---- the list ----------------------------------------------------------

    private inner class PlaceAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: LayoutInflater.from(this@SearchActivity)
                .inflate(R.layout.row_place, parent, false)
            val p = shown[position]
            v.findViewById<android.widget.ImageView>(R.id.rowIcon).setImageResource(
                if (p.recent) R.drawable.ic_clock else R.drawable.ic_place_pin)
            v.findViewById<TextView>(R.id.rowTitle).text = p.label

            val dist = here?.let { h ->
                val d = Geo.haversine(h.lat, h.lon, p.lat, p.lon)
                // One decimal under 10 km, none above: "6.5 km" is a useful
                // distinction from 7 km, "43.2 km" is false precision on a
                // straight-line distance.
                when {
                    d < 1000 -> "${d.toInt()} m"
                    d < 10000 -> "%.1f km".format(Locale.UK, d / 1000.0)
                    else -> "%.0f km".format(Locale.UK, d / 1000.0)
                }
            } ?: ""
            v.findViewById<TextView>(R.id.rowDistance).apply {
                text = dist
                visibility = if (dist.isBlank()) View.GONE else View.VISIBLE
            }
            v.findViewById<TextView>(R.id.rowSubtitle).apply {
                // The separator belongs to the address, not the distance, so
                // a row with no fix does not start with a stray dot.
                text = if (p.subtitle.isBlank()) ""
                       else if (dist.isBlank()) p.subtitle
                       else "  ·  " + p.subtitle
                visibility = if (p.subtitle.isBlank()) View.GONE else View.VISIBLE
            }
            return v
        }
    }

    // ---- odds and ends -----------------------------------------------------

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun toast(s: String) = com.mihai.navhud.ui.Notice.show(this, s)

    private fun lastKnown(): LatLon? = try {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = HudService.lastLocation
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        l?.let { LatLon(it.latitude, it.longitude) }
    } catch (e: SecurityException) { null }

    // ---- stored places (JSON in SharedPreferences; no database needed) ------

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadPlace(key: String): Place? {
        val raw = prefs().getString(key, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Place(o.getString("label"), o.getDouble("lat"), o.getDouble("lon"),
                  o.optString("sub", ""))
        }.getOrNull()
    }

    private fun savePlace(key: String, p: Place) {
        prefs().edit().putString(key, JSONObject()
            .put("label", p.label).put("lat", p.lat).put("lon", p.lon)
            .put("sub", p.subtitle).toString()).apply()
    }

    private fun loadRecents(): List<Place> {
        val raw = prefs().getString(KEY_RECENTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Place(o.getString("label"), o.getDouble("lat"), o.getDouble("lon"),
                      o.optString("sub", ""))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveRecent(p: Place) {
        val kept = loadRecents().filter { it.label != p.label }.toMutableList()
        kept.add(0, p)
        while (kept.size > MAX_RECENTS) kept.removeAt(kept.size - 1)
        val arr = JSONArray()
        for (x in kept) {
            arr.put(JSONObject().put("label", x.label).put("lat", x.lat)
                .put("lon", x.lon).put("sub", x.subtitle))
        }
        prefs().edit().putString(KEY_RECENTS, arr.toString()).apply()
    }
}
