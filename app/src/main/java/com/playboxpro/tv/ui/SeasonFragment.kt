package com.playboxpro.tv.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playboxpro.tv.MainActivity
import com.playboxpro.tv.R
import com.playboxpro.tv.model.Category
import com.playboxpro.tv.model.Series
import kotlinx.coroutines.launch

/**
 * Season / episode picker opened by the Episodes button on a series page,
 * behaving as STB+ EpisodeActivity does: the season list in the left
 * column, its episodes on the right, and the first episode of the chosen
 * season under the remote. Moving through the season list re-fills the
 * episodes; OK on an episode plays it.
 */
class SeasonFragment : Fragment(R.layout.fragment_season) {
    private val vm: AppViewModel by activityViewModels()

    companion object {
        fun newInstance(series: Series) = SeasonFragment().apply {
            arguments = Bundle().apply { putString("id", series.id) }
        }
    }

    private lateinit var rvSeason: RecyclerView
    private lateinit var rvEpisode: RecyclerView
    private lateinit var tvSeasonNumber: TextView
    private lateinit var tvTotalEpisodes: TextView

    /** The episode last started from here; BACK from the player returns to it. */
    private var playedSeason = -1
    private var playedEpisode = -1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvSeason = view.findViewById(R.id.rvSeason)
        rvEpisode = view.findViewById(R.id.rvEpisode)
        tvSeasonNumber = view.findViewById(R.id.tvSeasonNumber)
        tvTotalEpisodes = view.findViewById(R.id.tvTotalEpisodes)

        view.findViewById<View>(R.id.btnReturn).setOnClickListener { parentFragmentManager.popBackStack() }
        view.findViewById<TextView>(R.id.tvProfileName).text = vm.repo.account()?.name.orEmpty()

        val id = requireArguments().getString("id", "")
        val cached = vm.library.value.series.firstOrNull { it.id == id } ?: Series(id, "Series")
        bind(cached)
        if (cached.seasons.isEmpty()) {
            lifecycleScope.launch {
                runCatching { vm.repo.series(id) }.getOrNull()?.let { if (isAdded) bind(it) }
            }
        }
    }

    private fun focusEpisode(index: Int, attempt: Int = 0) {
        if (!isAdded) return
        if (attempt == 0 && index > 0) rvEpisode.scrollToPosition(index)
        val row = rvEpisode.findViewHolderForAdapterPosition(index)?.itemView
        if (row != null && row.requestFocus()) return
        if (attempt < 20) rvEpisode.postDelayed({ focusEpisode(index, attempt + 1) }, 50L)
    }

    private fun bind(series: Series) {
        ImageLoader.loadPoster(requireView().findViewById(R.id.ivSeriesPoster), series.poster, cornerRadiusDp = 4)
        requireView().findViewById<TextView>(R.id.tvSeriesYear).text = series.year?.toString().orEmpty()
        requireView().findViewById<TextView>(R.id.tvSeriesGenre).text = series.genre.ifBlank { series.categoryName }
        requireView().findViewById<TextView>(R.id.tvSeriesTitle).text = series.name

        val seasons = series.seasons
        if (seasons.isEmpty()) {
            tvSeasonNumber.text = "Season -"
            tvTotalEpisodes.text = "0 Episodes"
            return
        }

        val episodeAdapter = EpisodeCardAdapter(
            items = seasons.first().episodes,
            getProgress = { epId -> vm.repo.getResume(epId)?.progress ?: 0f },
            onClick = { ep ->
                playedSeason = ep.season
                playedEpisode = ep.episode
                (activity as? MainActivity)?.play(
                    url = ep.url,
                    title = series.name,
                    subtitle = "S${ep.season} E${ep.episode} - ${ep.name}",
                    poster = series.poster,
                    backdrop = series.backdrop,
                    kind = "episode",
                    seriesId = series.id,
                    season = ep.season,
                    episode = ep.episode,
                    id = ep.id
                )
            }
        )
        rvEpisode.layoutManager = LinearLayoutManager(requireContext())
        rvEpisode.adapter = episodeAdapter

        fun show(number: Int) {
            val season = seasons.firstOrNull { it.number == number } ?: seasons.first()
            tvSeasonNumber.text = "Season ${season.number}"
            tvTotalEpisodes.text = "${season.episodes.size} Episodes"
            episodeAdapter.submitList(season.episodes)
        }

        // Back from the player: the season and episode that were playing.
        // Otherwise STB+ opens on the first episode of the first season.
        val season = seasons.firstOrNull { it.number == playedSeason } ?: seasons.first()

        val rows = seasons.map { s ->
            Category(id = s.number.toString(), name = "Season ${s.number}", kind = "series", count = s.episodes.size)
        }
        rvSeason.layoutManager = LinearLayoutManager(requireContext())
        rvSeason.adapter = CategoryRailAdapter(
            rows,
            season.number.toString(),
            onClick = { cat ->
                show(cat.id.toIntOrNull() ?: 1)
                rvEpisode.post { rvEpisode.getChildAt(0)?.requestFocus() }
            },
            onFocus = { cat -> show(cat.id.toIntOrNull() ?: 1) }
        )
        show(season.number)
        val index = season.episodes.indexOfFirst { it.episode == playedEpisode }.coerceAtLeast(0)
        focusEpisode(index)
    }
}
