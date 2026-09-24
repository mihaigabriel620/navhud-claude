package com.mihai.navhud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.view.View
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.alerts.CountryRules

/**
 * Setup and diagnostics. The driving screen is MapActivity; this is where you
 * paste your Mapbox token and watch the raw protocol frames when something is
 * not behaving.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tokenInput: EditText
    private lateinit var statusView: TextView
    private lateinit var frameView: TextView
    private lateinit var voiceBox: CheckBox
    private lateinit var btBox: CheckBox
    private lateinit var langSpinner: Spinner
    private lateinit var camSpinner: Spinner
    private lateinit var countrySpinner: Spinner
    private lateinit var camExplain: TextView

    // fr-BE and fr-FR differ in exactly the way that matters out loud: Belgium
    // says septante and nonante, France says soixante-dix and quatre-vingt-dix.
    private val langCodes = arrayOf("", "en", "fr-BE", "fr-FR")
    private val camModes = arrayOf<CameraPolicy?>(null, CameraPolicy.EXACT,
                                                  CameraPolicy.ZONE, CameraPolicy.OFF)
    private val countries = arrayOf("BE", "FR", "NL", "LU", "DE", "CH", "AT",
                                    "CZ", "SK", "HU", "RO", "IT", "ES", "PL")

    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tokenInput = findViewById(R.id.token)
        statusView = findViewById(R.id.status)
        frameView = findViewById(R.id.frame)
        voiceBox = findViewById(R.id.voice)
        btBox = findViewById(R.id.bluetooth)
        langSpinner = findViewById(R.id.language)
        camSpinner = findViewById(R.id.cameraMode)
        countrySpinner = findViewById(R.id.homeCountry)
        camExplain = findViewById(R.id.cameraExplain)
        setUpPickers()

        tokenInput.setText(Prefs.mapboxToken(this))
        voiceBox.isChecked = Prefs.voice(this)
        btBox.isChecked = Prefs.useBluetooth(this)

        val googleInput = findViewById<EditText>(R.id.googleKey)
        googleInput.setText(Prefs.googleKey(this))
        findViewById<Button>(R.id.saveGoogle).setOnClickListener {
            Prefs.setGoogleKey(this, googleInput.text.toString())
            toast(if (googleInput.text.isBlank()) "Google key cleared. Searching with OpenStreetMap."
                  else "Google key saved")
        }

        findViewById<Button>(R.id.saveToken).setOnClickListener {
            val t = tokenInput.text.toString().trim()
            if (t.isNotEmpty() && !t.startsWith("pk.") && !t.startsWith("sk.")) {
                toast("That does not look like a Mapbox token. They start with pk.")
            }
            Prefs.setMapboxToken(this, t)
            toast(if (t.isBlank()) "Token cleared" else "Token saved")
        }
        voiceBox.setOnCheckedChangeListener { _, on ->
            Prefs.setVoice(this, on)
            HudService.voiceEnabled = on
        }
        btBox.setOnCheckedChangeListener { _, on -> Prefs.setUseBluetooth(this, on) }

        findViewById<Button>(R.id.stop).setOnClickListener {
            // Stop means stop: the map screen checks HudService.userStopped
            // before starting free drive again, so this is not undone the
            // moment you press Back.
            startService(Intent(this, HudService::class.java)
                .setAction(HudService.ACTION_STOP))
            toast("Stopped. Re-open the map to start again.")
        }
        findViewById<Button>(R.id.openMap).setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }

        requestPermissions()
        HudService.statusListener = { ui.post { refresh() } }
        refresh()
        // Started in onResume, not here: the 400 ms poll formats a multi-line
        // diagnostics block into TextViews, and it used to keep doing that
        // while the screen was not even visible -- for the whole drive, on the
        // main thread, competing with the map.
    }

    /**
     * The camera picker deliberately offers "what the law here allows" as the
     * default: the app can tighten it, never loosen it, and the explanation
     * line says which country's rule is currently in force.
     */
    private fun setUpPickers() {
        fun spinner(sp: Spinner, labels: Array<String>, selected: Int, onPick: (Int) -> Unit) {
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            val start = selected.coerceIn(0, labels.size - 1)
            sp.setSelection(start, false)
            // Android delivers the *initial* selection to the listener as
            // though the user had made it, and it arrives after the listener is
            // attached, so it cannot be avoided by ordering. Swallow the first
            // callback if it is the value we just set: without this, opening
            // Setup re-saves every setting and speaks the voice sample every
            // single time.
            var settling = true
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    if (settling) { settling = false; if (pos == start) return }
                    onPick(pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        val savedLang = Prefs.languageCode(this)
        spinner(langSpinner,
            arrayOf("Follow the device", "English",
                    "Français (Belgique), septante et nonante",
                    "Français (France), soixante-dix"),
            // "fr" is what earlier versions saved; it means Belgium here.
            (if (savedLang == "fr") 2 else langCodes.indexOf(savedLang)).coerceAtLeast(0)
        ) {
            Prefs.setLanguageCode(this, langCodes[it])
            // Push it into the service *now*. It used to be saved and nothing
            // else, and because free drive starts the service the moment the
            // app opens, onStartCommand -- the only place that read it -- had
            // already run. The setting was stored and never applied.
            HudService.applyPrefs(this, speakSample = true)
            // The voice the engine settled on is worth showing straight away,
            // so a fallback is visible rather than mysterious.
            langSpinner.postDelayed({
                findViewById<TextView>(R.id.voiceInfo).text = VoiceGuide.lastVoiceInfo
            }, 400)
        }

        spinner(camSpinner,
            arrayOf("What the law here allows", "Exact positions",
                    "Danger zones only", "No camera alerts"),
            camModes.indexOf(Prefs.cameraPreference(this)).coerceAtLeast(0)
        ) {
            Prefs.setCameraPreference(this, camModes[it])
            HudService.applyPrefs(this)
            refreshCamExplain()
        }

        spinner(countrySpinner, countries,
            countries.indexOf(Prefs.homeCountry(this)).coerceAtLeast(0)
        ) {
            Prefs.setHomeCountry(this, countries[it])
            HudService.applyPrefs(this)
            refreshCamExplain()
        }

        findViewById<android.widget.Button>(R.id.calibrate).setOnClickListener {
            startActivity(Intent(this, CalibrateActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.keystone).setOnClickListener {
            startActivity(Intent(this, KeystoneActivity::class.java))
        }

        refreshCamExplain()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(poll)
        ui.post(poll)
        refreshCalibInfo()
        refreshKeystoneInfo()
        refreshBackgroundHealth()
    }

    /**
     * Say plainly whether this phone is going to let the display keep running.
     *
     * Everything here is a yes/no the system will answer honestly if asked, and
     * every one of them is invisible until the HUD goes dark on a motorway.
     */
    private fun refreshBackgroundHealth() {
        val checks = BackgroundHealth.checks(this)
        val bad = checks.filter { !it.ok }
        val view = findViewById<TextView>(R.id.bgHealth)
        view.text = if (bad.isEmpty()) getString(R.string.bg_all_ok)
                    else bad.joinToString("\n") { "• ${it.label}: ${it.detail}" }
        view.setTextColor(
            if (bad.isEmpty()) androidx.core.content.ContextCompat.getColor(this, R.color.ok)
            else androidx.core.content.ContextCompat.getColor(this, R.color.amber_bright))

        findViewById<CheckBox>(R.id.bootStart).apply {
            isChecked = Prefs.startOnBoot(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setStartOnBoot(this@MainActivity, on) }
        }

        // Diagnostics are folded away. What used to be the bottom third of this
        // screen -- a live status dump and the raw serial sentence going to the
        // display, in monospace -- is the single loudest signal that a project
        // stopped before it was finished. It is genuinely useful, so it stays;
        // it just stops being the first thing a new user reads.
        val diagBlock = findViewById<View>(R.id.diagBlock)
        findViewById<TextView>(R.id.diagToggle).apply {
            setOnClickListener {
                val open = diagBlock.visibility == View.VISIBLE
                diagBlock.visibility = if (open) View.GONE else View.VISIBLE
                setText(if (open) R.string.diag_show else R.string.diag_hide)
            }
        }

        findViewById<CheckBox>(R.id.showPerf).apply {
            isChecked = Prefs.showPerf(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setShowPerf(this@MainActivity, on) }
        }
        findViewById<CheckBox>(R.id.arrowDebug).apply {
            isChecked = Prefs.arrowDebug(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setArrowDebug(this@MainActivity, on) }
        }

        val fix = findViewById<android.widget.Button>(R.id.bgFix)
        val exempt = BackgroundHealth.ignoringBatteryOptimisations(this)
        fix.visibility = if (exempt) View.GONE else View.VISIBLE
        fix.setOnClickListener { BackgroundHealth.requestExemption(this) }

        findViewById<TextView>(R.id.bgSteps).text = buildString {
            append(getString(R.string.bg_steps_title))
            for (step in BackgroundHealth.manualSteps) append("\n• ").append(step)
            HudService.lastExitReason?.let { append("\n\nLast time NavHUD stopped: ").append(it) }
        }
    }

    private fun refreshCalibInfo() {
        val deg = ((Prefs.headingOffset(this).toInt() % 360) + 360) % 360
        findViewById<TextView>(R.id.calibInfo).text =
            if (Prefs.headingCalibrated(this)) getString(R.string.calib_learned, deg)
            else getString(R.string.calib_none)
    }

    /**
     * What the display is currently doing with the picture.
     *
     * Prefers what the board itself reported over what this phone remembers:
     * the settings live in the board's flash, so a phone that has never been
     * on this screen can still show the truth once a cable is plugged in.
     */
    private fun refreshKeystoneInfo() {
        val k = HudService.boardGeometry ?: Prefs.keystone(this)
        findViewById<TextView>(R.id.keystoneInfo).text = when {
            k == null -> getString(R.string.keystone_summary_none)
            k.isIdentity && !k.mirrorX && !k.mirrorY -> getString(R.string.keystone_summary_none)
            else -> getString(
                R.string.keystone_summary, k.areaPercent(),
                if (k.mirrorX || k.mirrorY) getString(R.string.keystone_summary_mirror) else ""
            )
        }
    }

    private fun refreshCamExplain() {
        val where = HudService.country ?: Prefs.homeCountry(this)
        val effective = CountryRules.effective(where, Prefs.cameraPreference(this))
        camExplain.text = CountryRules.explain(where) + "\nIn force: " + when (effective) {
            CameraPolicy.EXACT -> "exact positions"
            CameraPolicy.ZONE -> "danger zones only"
            CameraPolicy.OFF -> "no alerts"
        }
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        HudService.statusListener = null
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val poll = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 400) }
    }

    private fun refresh() {
        // The driving screen shows only what a driver can act on, so the full
        // diagnostics live here.
        statusView.text = "${HudService.status}\nlink: ${HudService.linkInfo}" +
                          "\ngps: ${HudService.fixQuality}" +
                          "\n${HudService.headingSource}" +
                          "\ncameras: ${HudService.cameraFreshness()}" +
                          (HudService.country?.let { "\ncountry: $it" } ?: "")
        // Which voice was picked is worth showing: if it says the offline one,
        // installing Google's high-quality French from the TTS settings is the
        // single biggest improvement available to the spoken guidance. Without
        // the "voice: " prefix, which was a debug label leaking onto a screen
        // where the heading already says what the line is about.
        findViewById<TextView>(R.id.voiceInfo).text = VoiceGuide.lastVoiceInfo

        // The link, in a sentence rather than a driver class name.
        findViewById<TextView>(R.id.linkState).text = when {
            !HudService.running -> getString(R.string.link_idle)
            HudService.linkUp -> getString(R.string.link_up, HudService.linkInfo)
            HudService.linkEverUp -> getString(R.string.link_lost)
            else -> getString(R.string.link_waiting)
        }
        val f = HudService.lastFrame
        frameView.text = if (f == null) "no frames yet" else buildString {
            append(f.encode().trim()).append("\n\n")
            append("speed      ${if (f.speedKph < 0) "--" else f.speedKph} km/h\n")
            append("limit      ${when (f.limitKph) {
                0 -> "unknown"; -1 -> "unlimited"; else -> "${f.limitKph} km/h" }}\n")
            append("maneuver   ${f.maneuver}")
            if (f.roundaboutExit > 0) append(" (exit ${f.roundaboutExit})")
            append("\n")
            append("in         ${f.distToManeuverM} m -> ${f.street}\n")
            append("remaining  ${"%.1f".format(f.remainingM / 1000.0)} km, " +
                   "${f.etaSeconds / 60} min\n")
            append("flags      ${f.flags}")
        }
    }

    private fun requestPermissions() {
        val want = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            want += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            want += Manifest.permission.BLUETOOTH_CONNECT
        }
        val missing = want.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 7)
    }

    private fun toast(s: String) = com.mihai.navhud.ui.Notice.show(this, s)
}
