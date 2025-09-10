package com.unicity.nfcwalletdemo.utils

import android.content.Context
import android.location.Location
import android.location.LocationManager
import kotlin.random.Random

/**
 * Location Manager for Unicity Wallet
 * Handles both real GPS and testing mode for development
 */
object UnicityLocationManager {
    private const val PREFS_NAME = "UnicitywWalletPrefs"
    private const val KEY_DEMO_MODE = "demo_mode_enabled"
    private const val KEY_DEMO_LOCATION = "demo_location"
    private const val KEY_DEMO_LATITUDE = "demo_latitude"
    private const val KEY_DEMO_LONGITUDE = "demo_longitude"
    
    // Preset demo locations in African cities
    enum class DemoLocation(val city: String, val country: String, val latitude: Double, val longitude: Double) {
        LAGOS("Lagos", "Nigeria", 6.5244, 3.3792),
        NAIROBI("Nairobi", "Kenya", -1.2921, 36.8219),
        CAPE_TOWN("Cape Town", "South Africa", -33.9249, 18.4241),
        CAIRO("Cairo", "Egypt", 30.0444, 31.2357),
        ACCRA("Accra", "Ghana", 5.6037, -0.1870),
        DAR_ES_SALAAM("Dar es Salaam", "Tanzania", -6.7924, 39.2083),
        JOHANNESBURG("Johannesburg", "South Africa", -26.2041, 28.0473),
        ADDIS_ABABA("Addis Ababa", "Ethiopia", 9.0320, 38.7469),
        KIGALI("Kigali", "Rwanda", -1.9441, 30.0619),
        TUNIS("Tunis", "Tunisia", 36.8065, 10.1815),
        CUSTOM("Custom", "Custom", 0.0, 0.0)
    }
    
    fun isDemoModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEMO_MODE, false)
    }
    
    fun setDemoModeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DEMO_MODE, enabled).apply()
    }
    
    fun getDemoLocation(context: Context): DemoLocation {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val locationName = prefs.getString(KEY_DEMO_LOCATION, DemoLocation.LAGOS.name) ?: DemoLocation.LAGOS.name
        return try {
            DemoLocation.valueOf(locationName)
        } catch (e: IllegalArgumentException) {
            DemoLocation.LAGOS
        }
    }
    
    fun setDemoLocation(context: Context, location: DemoLocation) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DEMO_LOCATION, location.name)
            .putFloat(KEY_DEMO_LATITUDE, location.latitude.toFloat())
            .putFloat(KEY_DEMO_LONGITUDE, location.longitude.toFloat())
            .apply()
    }
    
    fun setCustomDemoLocation(context: Context, latitude: Double, longitude: Double) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DEMO_LOCATION, DemoLocation.CUSTOM.name)
            .putFloat(KEY_DEMO_LATITUDE, latitude.toFloat())
            .putFloat(KEY_DEMO_LONGITUDE, longitude.toFloat())
            .apply()
    }
    
    fun getDemoCoordinates(context: Context): Pair<Double, Double> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val latitude = prefs.getFloat(KEY_DEMO_LATITUDE, DemoLocation.LAGOS.latitude.toFloat()).toDouble()
        val longitude = prefs.getFloat(KEY_DEMO_LONGITUDE, DemoLocation.LAGOS.longitude.toFloat()).toDouble()
        return Pair(latitude, longitude)
    }
    
    fun createDemoLocation(context: Context): Location {
        val (latitude, longitude) = getDemoCoordinates(context)
        // Randomize within 500 meters radius
        val (randomLat, randomLon) = randomizeCoordinates(latitude, longitude, 500.0)
        val location = Location(LocationManager.GPS_PROVIDER)
        location.latitude = randomLat
        location.longitude = randomLon
        location.accuracy = 10f // Fake good accuracy
        location.time = System.currentTimeMillis()
        return location
    }
    
    /**
     * Randomize coordinates within a given radius (in meters) from a center point
     */
    private fun randomizeCoordinates(centerLat: Double, centerLon: Double, radiusMeters: Double): Pair<Double, Double> {
        // Convert radius from meters to degrees
        val radiusInDegrees = radiusMeters / 111320.0 // 1 degree is approximately 111320 meters
        
        // Generate random angle and distance
        val angle = Random.nextDouble() * 2 * Math.PI
        val distance = Random.nextDouble() * radiusInDegrees
        
        // Calculate new coordinates
        val deltaLat = distance * Math.cos(angle)
        val deltaLon = distance * Math.sin(angle) / Math.cos(Math.toRadians(centerLat))
        
        return Pair(centerLat + deltaLat, centerLon + deltaLon)
    }
    
    // Generate nearby agent locations for demo
    fun generateNearbyAgentLocations(context: Context, count: Int = 5): List<Pair<Double, Double>> {
        val (centerLat, centerLon) = getDemoCoordinates(context)
        val locations = mutableListOf<Pair<Double, Double>>()
        
        // Generate random points within 1-5km radius
        for (i in 0 until count) {
            val angle = Random.nextDouble() * 2 * Math.PI
            // Distance between 1km and 5km
            val distanceKm = 1.0 + Random.nextDouble() * 4.0
            val distanceInDegrees = distanceKm / 111.32 // Convert km to degrees
            
            val deltaLat = distanceInDegrees * Math.cos(angle)
            val deltaLon = distanceInDegrees * Math.sin(angle) / Math.cos(Math.toRadians(centerLat))
            
            val lat = centerLat + deltaLat
            val lon = centerLon + deltaLon
            
            locations.add(Pair(lat, lon))
        }
        
        return locations
    }
}