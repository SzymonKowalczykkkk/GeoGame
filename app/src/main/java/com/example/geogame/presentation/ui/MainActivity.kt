package com.example.geogame.presentation.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.health.connect.datatypes.ExerciseRoute
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.Observer
import com.example.geogame.R
import com.google.gson.Gson
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.MonitorNotifier
import org.altbeacon.beacon.Region
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import com.example.geogame.data.BeaconResponse
import com.example.geogame.data.BeaconData
import org.osmdroid.config.Configuration

@Suppress("UNREACHABLE_CODE")
class MainActivity : AppCompatActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. Continue the action or workflow in your
                // app.
                Toast.makeText(
                    this,
                    "Permission granted",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Explain to the user that the feature is unavailable because the
                // feature requires a permission that the user has denied. At the
                // same time, respect the user's decision. Don't link to system
                // settings in an effort to convince the user to change their
                // decision.
            }
        }

    private var permissionsGranted = false
    private var bluetoothEnabled = false
    private var locationEnabled = false
    private var scanningBeacons = false
    private val beaconJsonFileNames = listOf(
        "beacons_gg0.txt",
        "beacons_gg1.txt",
        "beacons_gg2b1.txt",
        "beacons_gg3b2.txt",
        "beacons_gg3b3.txt",
        "beacons_gg4.txt",
        "beacons_gg_out.txt"
    )

    private val mapView: MapView by lazy {
        findViewById(R.id.mapView)
    }
    private var currentPosition: Pair<Double, Double> = Pair(52.0, 21.0)

    private var beaconMap: Map<String, BeaconData>? = null

    private fun loadReferenceBeacons(): MutableList<BeaconResponse> {
        val assetManager = this.assets
        val gson = Gson()
        val beaconResponses = mutableListOf<BeaconResponse>()

        for (fileName in beaconJsonFileNames) {
            try {
                // Read the JSON file
                val inputStream = assetManager.open(fileName)
                val json = inputStream.bufferedReader().use { it.readText() }

                // Parse JSON into BeaconResponse and add to list
                val beaconResponse = gson.fromJson(json, BeaconResponse::class.java)
                beaconResponses.add(beaconResponse)
                Log.d("MainActivity", "Successfully read ${beaconResponse.items.size} beacons from $fileName")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error reading beacon data from file: $fileName", e)
                e.printStackTrace()
            }
        }
        val beacons = mutableListOf<BeaconData>()
        beaconResponses.forEach { beaconResponse ->
            beaconResponse.items.forEach { beaconData ->
                beacons.add(beaconData)
                Log.d("MainActivity", "Beacon: UID=${beaconData.beaconUid}, Name=${beaconData.id}, Longitude=${beaconData.longitude}, latitude=${beaconData.latitude}")
            }
        }
        beaconMap = beacons.associateBy { it.beaconUid }
        Log.d("MainActivity", "Loaded a total of ${beaconMap?.size ?: 0} beacons into the map")

        return beaconResponses
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate")
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        loadReferenceBeacons()
        Log.d("MainActivity", "Loaded ${beaconMap?.size} beacons")

        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)

        val startPoint = GeoPoint(52.2219, 21.0071)
        mapView.controller.setZoom(18.0)
        mapView.controller.setCenter(startPoint)
    }

//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
//        setContentView(R.layout.activity_main)
//
//        mapView.setTileSource(TileSourceFactory.MAPNIK)
//        mapView.setMultiTouchControls(true)
//    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart")
        findViewById<Button>(R.id.start).setOnClickListener {
            if (servicesTriggerCheck()) {
                startBeaconScanning()
            }
        }

        findViewById<Button>(R.id.stop).setOnClickListener {
            stopBeaconScanning()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause")
        mapView.onPause()
    }

    private fun showExplanation(title: String, explanation: String) {
        // show an explanation to the user that this app requires bluetooth
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(explanation)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
        alertDialog.show()
    }

    private fun checkBluetoothStatus() : Boolean {
        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            showExplanation("Bluetooth not found on the device", "This app needs Bluetooth in order to scan beacons.")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            showExplanation("Bluetooth musi był włączony", "Aplikacja wymaga włączonego bluetootha do pozycjonowania.")
            return false
        }
        return true
    }

    private fun checkBluetoothON() : Boolean {
        val status = checkBluetoothStatus()
        bluetoothEnabled = status
        return status
    }

    private fun checkLocationON(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val locationON = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!locationON) {
            showExplanation("Lokalizacja wyłączona", "Aplikacja wymaga włączonej lokalizacji do pozycjonowania.")
        }
        locationEnabled = locationON
        return locationON
    }

    private fun servicesTriggerCheck(): Boolean {
        return checkBluetoothON() && checkLocationON()
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        bluetoothEnabled = true
                        Toast.makeText(context, "Bluetooth włączony", Toast.LENGTH_SHORT).show()
                        if (locationEnabled) {
                            startBeaconScanning()
                        }
                    }
                    else -> {
                        bluetoothEnabled = false
                        Toast.makeText(context, "Bluetooth wyłączony", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                val locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager
                // val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                // val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                locationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

                if (locationEnabled) {
                    locationEnabled = true
                    if (bluetoothEnabled) {
                        startBeaconScanning()
                    }
                } else {
                    locationEnabled = false
                }
            }
        }
    }

    private fun runApp() {
        checkPermissions()
        if (!permissionsGranted) {
            showExplanation("Przyznaj uprawnienia aplikacji", "Aplikacja wymaga dostępu do bluetootha oraz lokalizacji do poprawnego działania.")
            return
        }

        val btFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, btFilter)
        val locationFilter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        registerReceiver(locationReceiver, locationFilter)

        if (servicesTriggerCheck()) {
            startBeaconScanning()
        }

        startGPSUpdates()
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume")
        mapView.onResume()
        runApp()
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy")
    }

    override fun onRestart() {
        super.onRestart()
        Log.d("MainActivity", "onRestart")
    }

    private fun checkPermissions(): Boolean {
        permissionsGranted = (checkLocationPermission() && checkBluetoothPermission())
        return permissionsGranted
    }

    private fun checkLocationPermission(): Boolean {
        return checkPermission(
            Manifest.permission.ACCESS_FINE_LOCATION,
            "Location permission is required for scanning beacons"
        ) && checkPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            "Precise location permission is required for scanning beacons"
        )
    }

    private fun checkBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkPermission(
                Manifest.permission.BLUETOOTH_SCAN,
                "Bluetooth scanning permission is required for scanning beacons"
            ) && checkPermission(
                Manifest.permission.BLUETOOTH_CONNECT,
                "Bluetooth connecting permission is required for scanning beacons"
            )
        } else {
            TODO("VERSION.SDK_INT < S")
            return false
        }
    }

    private fun checkPermission(permission: String, rationale: String): Boolean {
        when {
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission is granted. Continue the action or workflow in your
                // app.
                return true
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                permission
            ) -> {
                // Explain to the user that the feature is unavailable because the
                // feature requires a permission that the user has denied. At the
                // same time, respect the user's decision. Don't link to system
                // settings in an effort to convince the user to change their
                // decision.
                Toast.makeText(
                    this,
                    rationale,
                    Toast.LENGTH_LONG
                ).show()
                return false
            }
            else -> {
                // You can directly ask for the permission.
                // The registered ActivityResultCallback gets the result of this request.
                requestPermissionLauncher.launch(permission)
                return ActivityCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_DENIED
            }
        }
    }

    private var userMarker: org.osmdroid.views.overlay.Marker? = null
    private var gpsMarker: org.osmdroid.views.overlay.Marker? = null

    private fun updateGPSMarker(lat: Double, lon: Double) {
        val point = GeoPoint(lat, lon)
        runOnUiThread {
            if (gpsMarker == null) {
                gpsMarker = org.osmdroid.views.overlay.Marker(mapView).apply {
                    setAnchor(
                        org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                        org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM
                    )
                    icon = ContextCompat.getDrawable(this@MainActivity, org.osmdroid.library.R.drawable.person) // wbudowana ikona
                    title = "Pozycja GPS"
                }
                mapView.overlays.add(gpsMarker)
            }
            gpsMarker?.position = point
            mapView.invalidate()
        }
    }

    private fun updateMapLocation(position: Pair<Double, Double>) {
        val geoPoint = GeoPoint(position.first, position.second)
        runOnUiThread {
            if (userMarker == null) {
                userMarker = org.osmdroid.views.overlay.Marker(mapView).apply {
                    setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                    icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.baseline_airplanemode_active_24)
                }
                mapView.overlays.add(userMarker)
            } else {
                userMarker?.position = geoPoint
            }

            mapView.controller.animateTo(geoPoint)
            mapView.invalidate()
        }
    }

    private lateinit var locationManager: LocationManager

    private fun startGPSUpdates() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val lat = location.latitude
                val lon = location.longitude

                Log.d("GPS", "Lat: $lat, Lon: $lon")

                // Wyświetlenie markeru GPS
                updateGPSMarker(lat, lon)
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L, // czas w ms – 0 oznacza „tak często jak się da”, ale 500ms to rozsądne minimum
                0f,   // minimalna zmiana dystansu w metrach (0 = każda zmiana)
                locationListener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun calculatePosition(beaconLats: List<Double>,
                                  beaconLons: List<Double>,
                                  beaconDistances: List<Double>): Pair<Double, Double> {
        if (beaconLats.isEmpty() || beaconLons.isEmpty() || beaconDistances.isEmpty()) {
            return Pair(0.0, 0.0)
        }
        // Weighting beacons based on inverse distance (closer beacons matter more)
        var weightedLat = 0.0
        var weightedLon = 0.0
        var totalWeight = 0.0

        val epsilon = 1e-6
        beaconLats.indices.forEach{ i->
            val distance = beaconDistances[i].coerceAtLeast(epsilon)
            val weight = 1.0 / distance

            weightedLat += beaconLats[i] * weight
            weightedLon += beaconLons[i] * weight
            totalWeight += weight
        }

        val calculatedPosition = Pair(weightedLat / totalWeight, weightedLon / totalWeight)

        updateMapLocation(calculatedPosition)

        return currentPosition
    }

    private fun startBeaconScanning() {
        if (scanningBeacons) {
            return
        }
        scanningBeacons = true
        Toast.makeText(this, "Scanning beacons...", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Permissions granted, scanning beacons...")
        val beaconManager =  BeaconManager.getInstanceForApplication(this)
        listOf(
            BeaconParser.EDDYSTONE_UID_LAYOUT,
            BeaconParser.EDDYSTONE_TLM_LAYOUT,
            BeaconParser.EDDYSTONE_URL_LAYOUT
        ).forEach {
            beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(it))
        }
        val region = Region("all-beacons-region", null, null, null)
        // Set up a Live Data observer so this Activity can get monitoring callbacks
        // observer will be called each time the monitored regionState changes (inside vs. outside region)
        beaconManager.getRegionViewModel(region).regionState.observe(this, monitoringObserver)
        beaconManager.startMonitoring(region)
        beaconManager.addRangeNotifier { beacons, _ ->
            val beaconCount = beacons.size
            // find the beacon by its id
            if (beaconCount > 0) {
                val beaconLons = mutableListOf<Double>()
                val beaconLats = mutableListOf<Double>()
                val beaconDistances = mutableListOf<Double>()
                for (beacon in beacons) {
                    val beaconData = beaconMap?.get(beacon.bluetoothAddress)
                    beaconLons.add(beaconData?.longitude ?: 0.0)
                    beaconLats.add(beaconData?.latitude ?: 0.0)
                    beaconDistances.add(beacon.distance)
                }
                val position = calculatePosition(beaconLats, beaconLons, beaconDistances)
                val num = beaconLats.size
                Log.d("MainActivity", "Beacons: $num")
                Log.d("MainActivity", "Position: $position")
            }
        }
        // observer will be called each time a new rangedBeacons is measured
        beaconManager.startRangingBeacons(region)
    }

    private fun stopBeaconScanning() {
        if (!scanningBeacons) return
        scanningBeacons = false
        Toast.makeText(this, "Stopping beacon scanning...", Toast.LENGTH_SHORT).show()
        val beaconManager = BeaconManager.getInstanceForApplication(this)
        val region = Region("all-beacons-region", null, null, null)
        beaconManager.stopMonitoring(region)
        beaconManager.stopRangingBeacons(region)
    }

    val monitoringObserver = Observer<Int> { state ->
        if (state == MonitorNotifier.INSIDE) {
            Log.d("MainActivity", "Detected beacons(s)")
        }
        else {
            Log.d("MainActivity", "Stopped detecteing beacons")
        }
    }
}