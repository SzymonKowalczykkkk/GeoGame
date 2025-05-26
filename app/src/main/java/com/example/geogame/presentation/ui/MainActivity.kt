package com.example.geogame.presentation.ui

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.*
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.geogame.R
import com.example.geogame.data.BeaconData
import com.example.geogame.data.BeaconResponse
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.BasemapStyle
import com.esri.arcgisruntime.mapping.view.MapView
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.mapping.view.Graphic
import com.google.gson.Gson
import org.altbeacon.beacon.*
import androidx.lifecycle.Observer
import com.esri.arcgisruntime.geometry.SpatialReferences
import com.esri.arcgisruntime.symbology.PictureMarkerSymbol
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority


class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var graphicsOverlay: GraphicsOverlay

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Permission granted", Toast.LENGTH_LONG).show()
        }
    }

    private val beaconJsonFileNames = listOf(
        "beacons_gg0.txt", "beacons_gg1.txt", "beacons_gg2b1.txt",
        "beacons_gg3b2.txt", "beacons_gg3b3.txt", "beacons_gg4.txt", "beacons_gg_out.txt"
    )

    private var beaconMap: Map<String, BeaconData>? = null
    private var scanningBeacons = false
    private var currentMarker: Graphic? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        com.esri.arcgisruntime.ArcGISRuntimeEnvironment.setApiKey("AAPTxy8BH1VEsoebNVZXo8HurMNANgSR_GTjR9f-3_yiqiA0NCmdDBOawYF8YCMajaD1xtxwfHW7VQTnf2i52Ow3btgZewsyrqgfqnGoenBKflb6pbt20Ed2rcRP_8wlXy1Q_zfza-unXcjtPdg7ujxfW9LjT1VxcfpZC49XjOicwXjE539tg5FyvWJN_gXdBXEM.AT1_kZxSmO6z")
        mapView = findViewById(R.id.mapView)
        val map = ArcGISMap(BasemapStyle.ARCGIS_NAVIGATION)
        mapView.map = map

        graphicsOverlay = GraphicsOverlay()
        mapView.graphicsOverlays.add(graphicsOverlay)

        loadReferenceBeacons()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        findViewById<Button>(R.id.start).setOnClickListener {
            if (checkBluetoothStatus()) {
                startBeaconScanning()
                startContinuousLocationUpdates()
            }
        }

        findViewById<Button>(R.id.stop).setOnClickListener {
            stopBeaconScanning()
            stopContinuousLocationUpdates()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onResume() {
        super.onResume()
        mapView.resume()
        runApp()
    }

    override fun onPause() {
        super.onPause()
        mapView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.dispose()
    }

    private fun loadReferenceBeacons(): List<BeaconResponse> {
        val gson = Gson()
        val beacons = mutableListOf<BeaconData>()
        val responses = mutableListOf<BeaconResponse>()
        val assets = assets

        for (file in beaconJsonFileNames) {
            try {
                val json = assets.open(file).bufferedReader().use { it.readText() }
                val response = gson.fromJson(json, BeaconResponse::class.java)
                responses.add(response)
                beacons.addAll(response.items)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading beacons from $file", e)
            }
        }

        beaconMap = beacons.associateBy { it.beaconUid }
        return responses
    }

    private fun updateMapLocation(lat: Double, lon: Double) {
        val point = Point(lon, lat)
        runOnUiThread {
            currentMarker?.let { graphicsOverlay.graphics.remove(it) }

            val beaconDrawable = ContextCompat.getDrawable(this, R.drawable.baseline_airplanemode_active_24)
            val symbol = PictureMarkerSymbol.createAsync(beaconDrawable as BitmapDrawable?).get()
            symbol.width = 40f
            symbol.height = 40f

            currentMarker = Graphic(point, symbol)
            graphicsOverlay.graphics.add(currentMarker)
            mapView.setViewpointCenterAsync(point, 1000.0)
        }
    }

    private fun calculatePosition(lats: List<Double>, lons: List<Double>, dists: List<Double>): Pair<Double, Double> {
        var weightedLat = 0.0
        var weightedLon = 0.0
        var totalWeight = 0.0

        val epsilon = 1e-6
        for (i in lats.indices) {
            val weight = 1.0 / dists[i].coerceAtLeast(epsilon)
            weightedLat += lats[i] * weight
            weightedLon += lons[i] * weight
            totalWeight += weight
        }

        val lat = weightedLat / totalWeight
        val lon = weightedLon / totalWeight
        updateMapLocation(lat, lon)
        return Pair(lat, lon)
    }

    private fun startBeaconScanning() {
        if (scanningBeacons) return
        scanningBeacons = true

        Toast.makeText(this, "Start scanning", Toast.LENGTH_SHORT).show()

        val beaconManager = BeaconManager.getInstanceForApplication(this)
        listOf(
            BeaconParser.EDDYSTONE_UID_LAYOUT,
            BeaconParser.EDDYSTONE_TLM_LAYOUT,
            BeaconParser.EDDYSTONE_URL_LAYOUT
        ).forEach {
            beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(it))
        }

        val region = Region("all-beacons-region", null, null, null)
        beaconManager.getRegionViewModel(region).regionState.observe(this, Observer {
            Log.d("MainActivity", "Beacon region state: $it")
        })

        beaconManager.addRangeNotifier { beacons, _ ->
            val lats = mutableListOf<Double>()
            val lons = mutableListOf<Double>()
            val dists = mutableListOf<Double>()

            for (beacon in beacons) {
                val data = beaconMap?.get(beacon.bluetoothAddress)
                if (data != null) {
                    lats.add(data.latitude)
                    lons.add(data.longitude)
                    dists.add(beacon.distance)
                }
            }

            if (lats.isNotEmpty()) {
                calculatePosition(lats, lons, dists)
            }
        }

        beaconManager.startMonitoring(region)
        beaconManager.startRangingBeacons(region)
    }

    private fun stopBeaconScanning() {
        if (!scanningBeacons) return
        val region = Region("all-beacons-region", null, null, null)
        val manager = BeaconManager.getInstanceForApplication(this)
        manager.stopRangingBeacons(region)
        manager.stopMonitoring(region)
        scanningBeacons = false

        Toast.makeText(this, "Stop scanning", Toast.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun runApp() {
        if (!checkPermissions()) {
            showExplanation("Uprawnienia wymagane", "Włącz bluetooth.")
            return
        }

        val btFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, btFilter)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkPermissions(): Boolean {
        val missingPermissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        return if (missingPermissions.isEmpty()) {
            true
        } else {
            multiplePermissionsLauncher.launch(missingPermissions.toTypedArray())
            false
        }
    }

    private fun showExplanation(title: String, explanation: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(explanation)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun checkBluetoothStatus(): Boolean {
        val adapter = (getSystemService(BluetoothManager::class.java)).adapter
        return adapter?.isEnabled == true
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                if (checkBluetoothStatus()) {
                    startBeaconScanning()
                    Log.d("BEACON", "Skanowanie beaconów rozpoczęte")
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private val multiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.entries.forEach {
            if (!it.value) allGranted = false
        }

        if (allGranted) {
            Toast.makeText(this, "Wszystkie uprawnienia przyznane", Toast.LENGTH_SHORT).show()
            runApp()
        } else {
            Toast.makeText(this, "Brakuje wymaganych uprawnień", Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: com.google.android.gms.location.LocationRequest
    private lateinit var locationCallback: com.google.android.gms.location.LocationCallback
    private var locationUpdatesStarted = false

    private var gpsMarker: Graphic? = null

    private fun startContinuousLocationUpdates() {
        if (locationUpdatesStarted) return
        locationUpdatesStarted = true

        locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 500L // 0.5s
        )
            .setMinUpdateIntervalMillis(250L)
            .setMaxUpdateDelayMillis(1000L)
            .build()

        locationCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                val location = locationResult.lastLocation ?: return
                val lat = location.latitude
                val lon = location.longitude
                updateGpsMarker(lat, lon)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } else {
            Toast.makeText(this, "Brak uprawnień do lokalizacji GPS", Toast.LENGTH_SHORT).show()
        }
    }

    private fun vectorToBitmapDrawable(context: Context, vectorResId: Int): BitmapDrawable? {
        val drawable = ContextCompat.getDrawable(context, vectorResId) ?: return null

        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return BitmapDrawable(context.resources, bitmap)
    }

    private fun updateGpsMarker(lat: Double, lon: Double) {
        val point = Point(lon, lat, SpatialReferences.getWgs84())

        runOnUiThread {
            gpsMarker?.let { graphicsOverlay.graphics.remove(it) }

            val drawable = vectorToBitmapDrawable(this, R.drawable.baseline_airplanemode_active_24)
            val symbol = PictureMarkerSymbol.createAsync(drawable).get()
            symbol.width = 40f
            symbol.height = 40f

            gpsMarker = Graphic(point, symbol)
            graphicsOverlay.graphics.add(gpsMarker)

            mapView.setViewpointCenterAsync(point, 1000.0)
        }
    }

    private fun stopContinuousLocationUpdates() {
        if (!locationUpdatesStarted) return
        locationUpdatesStarted = false

        fusedLocationClient.removeLocationUpdates(locationCallback)
        Toast.makeText(this, "Zatrzymano aktualizację lokalizacji", Toast.LENGTH_SHORT).show()
    }
}


