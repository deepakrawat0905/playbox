package com.playboxpro.tv.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playboxpro.tv.BuildConfig
import com.playboxpro.tv.R
import com.playboxpro.tv.model.Category

/**
 * Settings, matching the STB+ tile grid: twelve tiles over six columns with
 * the device MAC, App Key and version along the bottom.
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {
    private val vm: AppViewModel by activityViewModels()

    private lateinit var grid: RecyclerView
    private lateinit var macText: TextView
    private lateinit var appKeyText: TextView
    private lateinit var versionText: TextView

    private data class Tile(val id: String, val label: String, val iconRes: Int)

    private val tiles = listOf(
        Tile("hide_live", "Hide Live\ncategory", R.drawable.ic_setting_hide_live),
        Tile("hide_vod", "Hide Vod\ncategory", R.drawable.ic_setting_hide_vod),
        Tile("hide_series", "Hide Series\ncategory", R.drawable.ic_setting_hide_series),
        Tile("live_player", "Live Internal\nPlayer", R.drawable.ic_setting_live_player),
        Tile("vod_player", "Vod Internal\nPlayer", R.drawable.ic_nav_movies),
        Tile("stream_format", "Stream Format", R.drawable.ic_setting_format),
        Tile("vod_external", "Vod External\nPlayer", R.drawable.ic_setting_ext_player),
        Tile("epg_offset", "Epg time Offset", R.drawable.ic_timer),
        Tile("parent_control", "Parent Control", R.drawable.ic_lock),
        Tile("clear_cache", "Clear cache", R.drawable.ic_setting_cache),
        Tile("check_update", "Check update", R.drawable.ic_setting_update),
        Tile("credits", "Credits", R.drawable.ic_info)
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        grid = view.findViewById(R.id.settingsGrid)
        macText = view.findViewById(R.id.settingsMac)
        appKeyText = view.findViewById(R.id.settingsAppKey)
        versionText = view.findViewById(R.id.settingsVersion)

        macText.text = vm.mac
        appKeyText.text = vm.deviceKey
        versionText.text = "Version: ${BuildConfig.VERSION_NAME}"

        grid.layoutManager = GridLayoutManager(requireContext(), 6)
        grid.adapter = TileAdapter(tiles) { onTile(it) }
    }

    // ------------------------------------------------------------------
    // Tile actions
    // ------------------------------------------------------------------

    private fun onTile(tile: Tile) {
        when (tile.id) {
            "hide_live" -> showHideCategories("live", "Hide Live category")
            "hide_vod" -> showHideCategories("movie", "Hide Vod category")
            "hide_series" -> showHideCategories("series", "Hide Series category")

            "live_player" -> showChoice(
                "Live Internal Player",
                listOf("internal" to "Internal player", "external" to "External player"),
                if (vm.repo.liveInternalPlayer) "internal" else "external"
            ) { vm.repo.liveInternalPlayer = it == "internal" }

            "vod_player" -> showChoice(
                "Vod Internal Player",
                listOf("internal" to "Internal player", "external" to "External player"),
                if (vm.repo.vodInternalPlayer) "internal" else "external"
            ) { vm.repo.vodInternalPlayer = it == "internal" }

            "stream_format" -> showChoice(
                "Stream Format",
                listOf("hls" to "HLS  (m3u8)", "ts" to "MPEG-TS"),
                vm.repo.streamFormat
            ) { vm.repo.streamFormat = it }

            "vod_external" -> showExternalPlayers()

            "epg_offset" -> showChoice(
                "Epg time Offset",
                (-6..6).map { it.toString() to if (it > 0) "+$it hours" else "$it hours" },
                vm.repo.epgTimeOffset.toString()
            ) { vm.repo.epgTimeOffset = it.toIntOrNull() ?: 0 }

            "parent_control" -> showParentControl()
            "clear_cache" -> clearCache()
            "check_update" -> showCheckUpdate()
            "credits" -> showCredits()
        }
    }

    /**
     * Multi-select over the section's categories. A checked row means hidden,
     * and nothing is written until OK - the same shape as STB+.
     */
    private fun showHideCategories(kind: String, title: String) {
        val lib = vm.library.value
        val categories: List<Category> = when (kind) {
            "movie" -> lib.movieCategories
            "series" -> lib.seriesCategories
            else -> lib.liveCategories
        }

        if (categories.isEmpty()) {
            toast("No categories to hide yet.")
            return
        }

        StbDialogs.showMultiSelection(
            requireContext(),
            title,
            // Labelled the same way as the rails: uppercase, pipe separated.
            categories.map { it.id to formatCategoryName(it.name) },
            vm.repo.hiddenCategories(kind)
        ) { hidden ->
            vm.repo.setHiddenCategories(kind, hidden)
            toast(
                if (hidden.isEmpty()) "All categories visible"
                else "${hidden.size} categories hidden"
            )
        }
    }

    private fun showExternalPlayers() {
        val pm = requireContext().packageManager
        val probe = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("http://example.com/video.mp4"), "video/*")
        }
        val players = pm.queryIntentActivities(probe, 0)
            .filter { it.activityInfo.packageName != requireContext().packageName }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }

        if (players.isEmpty()) {
            toast("No external video players installed.")
            return
        }

        showChoice("Vod External Player", players, "") { pkg ->
            toast("External player set to $pkg")
        }
    }

    private fun showParentControl() {
        val pinSet = vm.repo.parentPin.isNotBlank()
        showChoice(
            "Parent Control",
            listOf(
                "toggle" to if (vm.repo.lockAdult) "Adult lock:  On" else "Adult lock:  Off",
                "pin" to if (pinSet) "Change PIN" else "Set PIN"
            ),
            ""
        ) { choice ->
            when (choice) {
                "toggle" -> toggleAdultLock(pinSet)
                "pin" -> StbDialogs.showPin(
                    requireContext(),
                    if (pinSet) "Change PIN" else "Set PIN",
                    "Enter a 4 digit PIN"
                ) { pin ->
                    vm.repo.parentPin = pin
                    toast("PIN saved")
                }
            }
        }
    }

    /** Turning the lock off asks for the PIN first, if one is set. */
    private fun toggleAdultLock(pinSet: Boolean) {
        val turningOff = vm.repo.lockAdult

        if (turningOff && pinSet) {
            StbDialogs.showPin(requireContext(), "Enter PIN", "Confirm to unlock") { entered ->
                if (entered == vm.repo.parentPin) {
                    vm.repo.lockAdult = false
                    toast("Adult lock off")
                } else {
                    toast("Wrong PIN")
                }
            }
            return
        }

        vm.repo.lockAdult = !vm.repo.lockAdult
        toast(if (vm.repo.lockAdult) "Adult lock on" else "Adult lock off")
    }

    private fun clearCache() {
        StbDialogs.showConfirm(
            requireContext(),
            R.drawable.ic_setting_cache,
            "Clear cache",
            "Reload the playlist from the server?"
        ) {
            vm.repo.clearCache()
            vm.forceRefresh { toast("Cache cleared and playlist reloaded") }
        }
    }

    private fun showCheckUpdate() {
        StbDialogs.showConfirm(
            requireContext(),
            R.drawable.ic_setting_update,
            "Check update",
            "You are on version ${BuildConfig.VERSION_NAME}.\nNo update server is configured.",
            okLabel = "Close"
        ) { }
    }

    private fun showCredits() {
        StbDialogs.showCredits(
            requireContext(),
            "Device: ${android.os.Build.MODEL}   •   Android ${android.os.Build.VERSION.RELEASE}" +
                "\nPro Play Box ${BuildConfig.VERSION_NAME}"
        )
    }

    // ------------------------------------------------------------------
    // Dialog helpers
    // ------------------------------------------------------------------

    private fun showChoice(
        title: String,
        options: List<Pair<String, String>>,
        selected: String,
        onPick: (String) -> Unit
    ) = StbDialogs.showSelection(requireContext(), title, options, selected, onPick = onPick)

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // Tile adapter
    // ------------------------------------------------------------------

    private inner class TileAdapter(
        private val items: List<Tile>,
        private val onClick: (Tile) -> Unit
    ) : RecyclerView.Adapter<TileAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val root: View = v.findViewById(R.id.settingTileRoot)
            val icon: ImageView = v.findViewById(R.id.settingIcon)
            val title: TextView = v.findViewById(R.id.settingTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tile = items[position]
            holder.icon.setImageResource(tile.iconRes)
            holder.title.text = tile.label
            holder.root.setOnClickListener { onClick(tile) }
        }
    }
}
