package com.mihai.navhud

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.mihai.navhud.map.Compass
import com.mihai.navhud.ui.CompassView
import kotlin.math.roundToInt

/**
 * "Which way is the car pointing?"
 *
 * The app answers that from GPS as soon as you are moving, and from the phone's
 * compass before you are. The compass part needs one number the app cannot know
 * on its own: how the phone sits in its holder. Lying flat in a landscape
 * cradle, its top edge points across the car; stood upright in a windscreen
 * mount, the screen faces the driver; turn it end for end in the same cradle
 * and everything is 180 degrees out. On top of that a car is a steel box that
 * bends the magnetic field it is measuring.
 *
 * All of that is one constant offset, and it is normally learned in the first
 * few hundred metres of driving without anyone doing anything. This screen is
 * for the case that cannot cover — the phone has just gone into a different
 * holder and the car has not moved yet — and for checking the answer against
 * what you can see out of the windscreen.
 */
class CalibrateActivity : Activity(), SensorEventListener {

    private lateinit var compassView: CompassView
    private lateinit var readout: TextView
    private lateinit var readoutSub: TextView
    private lateinit var stateView: TextView

    private var sensors: SensorManager? = null
    private val m = FloatArray(9)
    private val r = DoubleArray(9)

    private var azimuth = Double.NaN
    private var offset = 0.0
    private var manual = false

    /**
     * Which 15-degree sectors of the circle the phone has been pointed through.
     *
     * Turning a phone slowly through a full circle is how you calibrate a
     * magnetometer: Android's own estimator watches the field sweep and solves
     * for the hard-iron offset, which is the constant error a steel car body
     * adds. There is no API to trigger it — the way you ask for it is to move
     * the phone — so the honest thing this screen can do is *show* the driver
     * how much of the circle they have covered and tell them when the sensor
     * reports itself calibrated.
     */
    private val sectors = BooleanArray(24)
    private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    /** The same magnetic-to-true correction the map applies. */
    private var declination = 0.0

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_calibrate)

        compassView = findViewById(R.id.compass)
        readout = findViewById(R.id.readout)
        readoutSub = findViewById(R.id.readoutSub)
        stateView = findViewById(R.id.calibState)

        offset = Prefs.headingOffset(this)
        declination = Prefs.declination(this)

        findViewById<Button>(R.id.done).setOnClickListener {
            Prefs.setHeadingOffset(this, offset)
            Prefs.setHeadingCalibrated(this, true)
            finish()
        }

        fun nudge(by: Double) {
            offset = Geo.normalizeDeg(offset + by)
            manual = true
            render()
        }
        findViewById<Button>(R.id.left90).setOnClickListener { nudge(-90.0) }
        findViewById<Button>(R.id.left5).setOnClickListener { nudge(-5.0) }
        findViewById<Button>(R.id.right5).setOnClickListener { nudge(5.0) }
        findViewById<Button>(R.id.right90).setOnClickListener { nudge(90.0) }
        findViewById<Button>(R.id.flip).setOnClickListener { nudge(180.0) }

        findViewById<Button>(R.id.reset).setOnClickListener {
            Prefs.clearHeadingCalibration(this)
            offset = 0.0
            manual = false
            java.util.Arrays.fill(sectors, false)
            render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensors = sm
        val rot = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rot == null) {
            stateView.setText(R.string.calib_no_sensor)
        } else {
            sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { sensors?.unregisterListener(this) }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {
        if (s?.type == Sensor.TYPE_ROTATION_VECTOR) { accuracy = a; render() }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        runCatching { SensorManager.getRotationMatrixFromVector(m, e.values) }
            .onSuccess {
                for (i in 0..8) r[i] = m[i].toDouble()
                Compass.headingFor(r, displayRotation(), Prefs.headingAxis(this))?.let {
                    azimuth = it
                    val sector = (((it % 360.0) + 360.0) % 360.0 / 15.0).toInt() % 24
                    sectors[sector] = true
                }
                render()
            }
    }

    private fun render() {
        // Declination included, because the map includes it. Without it the
        // arrow you nudge into place here is not the arrow you see out there.
        val car = if (azimuth.isNaN()) Double.NaN
                  else Geo.normalizeDeg(azimuth + declination + offset)
        compassView.carHeading = car
        compassView.rawHeading = azimuth
        compassView.covered = sectors
        readout.text = if (car.isNaN()) "--" else "${car.roundToInt() % 360}°  ${cardinal(car)}"

        val covered = sectors.count { it }
        readoutSub.text = getString(R.string.calib_progress, covered * 100 / sectors.size)

        val deg = ((offset.roundToInt() % 360) + 360) % 360
        stateView.text = when {
            accuracy == SensorManager.SENSOR_STATUS_ACCURACY_HIGH && covered >= 22 ->
                getString(R.string.calib_ready, deg)
            covered >= 22 -> getString(R.string.calib_turned_enough, deg)
            azimuth.isNaN() -> getString(R.string.calib_no_sensor)
            else -> getString(R.string.calib_turn_more)
        }
    }

    /** Surface.ROTATION_0/90/180/270 for the window this activity is in. */
    private fun displayRotation(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            display?.rotation ?: android.view.Surface.ROTATION_0
        else
            @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation

    /** 141 degrees is "SE", which is how a compass app says it. */
    private fun cardinal(deg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((deg + 22.5) / 45.0).toInt()) % 8]
    }
}
