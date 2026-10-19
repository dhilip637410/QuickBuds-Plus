package com.spizganed.quickbuds.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.calls.BikeMode

/**
 * The Bike mode tile in the quick settings panel: a tap turns Bike mode on or off (what it turns on: [BikeMode]).
 * Without the permissions the chosen features need, a tap opens the Bike mode screen to allow them.
 */
class BikeModeTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        paint()
    }

    override fun onClick() {
        super.onClick()
        if (BikeMode.isOn(this)) BikeMode.set(this, false)
        else if (!BikeMode.set(this, true)) return openBikeMode()
        paint()
    }

    private fun paint() {
        val tile = qsTile ?: return
        val on = BikeMode.isOn(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.bike_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = getString(when {
            !on && BikeMode.missingPermissions(this).isNotEmpty() -> R.string.bike_tile_needs
            on && !BudsService.backgroundAllowed(this) -> R.string.bike_tile_background
            on -> R.string.bike_tile_on
            else -> R.string.bike_tile_off
        })
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openBikeMode() {
        val intent = Intent(this, BikeModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android 14+ takes a PendingIntent here; the Intent form is refused there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}
