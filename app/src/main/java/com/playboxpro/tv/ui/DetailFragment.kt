package com.playboxpro.tv.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.playboxpro.tv.MainActivity
import com.playboxpro.tv.R
import com.playboxpro.tv.model.*
import kotlinx.coroutines.launch

/**
 * Movie / series detail page.
 *
 * Backdrop behind everything, the title and genre line top-left, the poster
 * with the rating line, short synopsis and action pills beside it, then the
 * Summary block and the RELATED FEATURES / CAST & CREW tabs whose rail runs
 * along the bottom. The remote lands on PLAY NOW; DOWN reaches the tabs and
 * then the rail, and moving across the tabs switches the rail.
 *
 * A series adds an EPISODES pill (the season picker) and its PLAY NOW picks
 * up the episode last watched, or starts at the first one.
 */
class DetailFragment : Fragment(R.layout.fragment_detail) {
    private val vm: AppViewModel by activityViewModels()

    private lateinit var backdrop: ImageView
    private lateinit var poster: ImageView
    private lateinit var title: TextView
    private lateinit var tagline: TextView
    private lateinit var ratingLine: TextView
    private lateinit var shortPlot: TextView
    private lateinit var plot: TextView
    private lateinit var castLine: TextView

    private lateinit var btnPlay: View
    private lateinit var btnPlayText: TextView
    private lateinit var btnStartOver: View
    private lateinit var btnEpisodes: View
    private lateinit var btnTrailer: View
    private lateinit var btnFavorite: View
    private lateinit var favoriteIcon: ImageView
    private lateinit var favoriteText: TextView
    private lateinit var btnMoreInfo: View

    private lateinit var tabRelated: TextView
    private lateinit var tabCast: TextView
    private lateinit var railLabel: TextView
    private lateinit var rail: RecyclerView
    private lateinit var railEmpty: View

    private var related: List<DetailCard> = emptyList()
    private var castCards: List<DetailCard> = emptyList()
    private var relatedLabel = "Related Movies"
    private var castTabShown = false

    /** False until this title's own details have been fetched. */
    private var detailsLoaded = false

    companion object {
        fun newMovie(movie: Movie) = DetailFragment().apply {
            arguments = Bundle().apply {
                putString("id", movie.id)
                putString("kind", "movie")
            }
        }

        fun newSeries(series: Series) = DetailFragment().apply {
            arguments = Bundle().apply {
                putString("id", series.id)
                putString("kind", "series")
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backdrop = view.findViewById(R.id.detailBackdrop)
        poster = view.findViewById(R.id.detailPoster)
        title = view.findViewById(R.id.detailTitle)
        tagline = view.findViewById(R.id.detailTagline)
        ratingLine = view.findViewById(R.id.detailRatingLine)
        shortPlot = view.findViewById(R.id.detailShortPlot)
        plot = view.findViewById(R.id.detailPlot)
        castLine = view.findViewById(R.id.detailCastLine)

        btnPlay = view.findViewById(R.id.btnDetailPlay)
        btnPlayText = view.findViewById(R.id.btnDetailPlayText)
        btnStartOver = view.findViewById(R.id.btnDetailStartOver)
        btnEpisodes = view.findViewById(R.id.btnDetailEpisodes)
        btnTrailer = view.findViewById(R.id.btnDetailTrailer)
        btnFavorite = view.findViewById(R.id.btnDetailFavorite)
        favoriteIcon = view.findViewById(R.id.btnDetailFavoriteIcon)
        favoriteText = view.findViewById(R.id.btnDetailFavoriteText)
        btnMoreInfo = view.findViewById(R.id.btnDetailMoreInfo)

        tabRelated = view.findViewById(R.id.tabRelated)
        tabCast = view.findViewById(R.id.tabCast)
        railLabel = view.findViewById(R.id.detailRailLabel)
        rail = view.findViewById(R.id.detailRail)
        railEmpty = view.findViewById(R.id.detailRailEmpty)

        rail.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        // Moving across the tabs switches the rail, as OK does.
        tabRelated.setOnFocusChangeListener { _, has -> if (has) showTab(cast = false) }
        tabCast.setOnFocusChangeListener { _, has -> if (has) showTab(cast = true) }
        tabRelated.setOnClickListener { showTab(cast = false); focusRail() }
        tabCast.setOnClickListener { showTab(cast = true); focusRail() }

        val id = requireArguments().getString("id", "")
        if (requireArguments().getString("kind", "movie") == "series") {
            val series = vm.library.value.series.firstOrNull { it.id == id } ?: Series(id, "Series")
            bindSeries(series)
            lifecycleScope.launch {
                val full = runCatching { vm.repo.series(id) }.getOrNull() ?: series
                detailsLoaded = true
                if (isAdded) bindSeries(full)
            }
        } else {
            val movie = vm.library.value.movies.firstOrNull { it.id == id } ?: Movie(id, "Movie")
            bindMovie(movie)
            lifecycleScope.launch {
                val full = runCatching { vm.repo.movie(id) }.getOrNull() ?: movie
                detailsLoaded = true
                if (isAdded) bindMovie(full)
            }
        }

        btnPlay.post { btnPlay.requestFocus() }
    }

    // ------------------------------------------------------------------
    // Shared header
    // ------------------------------------------------------------------

    private fun bindHeader(
        name: String, backdropUrl: String, posterUrl: String, rating: Double?,
        length: String, year: String, genre: String, story: String, cast: String, director: String
    ) {
        title.text = if (year.isNotBlank() && !name.contains("($year)")) "$name ($year)" else name
        tagline.text = listOf(genre, year).filter { it.isNotBlank() }.joinToString("  •  ")
        tagline.visibility = if (tagline.text.isBlank()) View.GONE else View.VISIBLE

        // No art at all: leave the page black rather than show a placeholder
        // stretched across the whole screen.
        val art = backdropUrl.ifBlank { posterUrl }
        if (art.isBlank()) backdrop.setImageDrawable(null) else ImageLoader.loadBackdrop(backdrop, art)
        ImageLoader.loadPoster(poster, posterUrl.ifBlank { backdropUrl }, cornerRadiusDp = 8)

        // "TMDb 8.1 / 1h 57m": the score in yellow, the rest in white.
        val line = android.text.SpannableStringBuilder()
        if (rating != null && rating > 0) {
            line.append("TMDb ${String.format("%.1f", rating)}")
            line.setSpan(
                android.text.style.ForegroundColorSpan(requireContext().getColor(R.color.yellow)),
                0, line.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (length.isNotBlank()) {
            if (line.isNotEmpty()) line.append("  /  ")
            line.append(length)
        }
        ratingLine.text = line
        ratingLine.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE

        // A one-sentence tagline up top; the full synopsis under Summary.
        // The listing carries no synopsis; until this title's own details
        // are in, leave the text empty rather than claim there is none.
        val text = story.ifBlank { if (detailsLoaded) "No description available." else "" }
        val firstSentence = Regex("""^.*?[.!?](\s|$)""").find(text)?.value?.trim().orEmpty()
        shortPlot.text = firstSentence.ifBlank { text }
        shortPlot.visibility = if (story.isBlank()) View.GONE else View.VISIBLE
        plot.text = text

        val castNames = cast.split(",").map { it.trim() }.filter { it.isNotBlank() }
        castLine.text = when {
            castNames.isNotEmpty() -> "Cast: " + castNames.take(4).joinToString(", ") +
                if (castNames.size > 4) ", ..." else ""
            director.isNotBlank() -> "Director: $director"
            else -> ""
        }
        castLine.visibility = if (castLine.text.isBlank()) View.GONE else View.VISIBLE

        castCards = buildList {
            if (director.isNotBlank()) {
                director.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    .forEach { add(DetailCard(it, "Director", initials = initials(it))) }
            }
            castNames.forEach { add(DetailCard(it, "Cast", initials = initials(it))) }
        }
    }

    private fun initials(name: String) =
        name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }

    private fun bindFavorite(id: String) {
        fun paint() {
            val fav = vm.repo.isFavorite(id)
            favoriteIcon.setImageResource(if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            favoriteText.text = if (fav) "IN FAVORITES" else "ADD TO FAVORITES"
        }
        paint()
        btnFavorite.setOnClickListener { vm.toggleFavorite(id); paint() }
    }

    private fun bindTrailer(raw: String) {
        val id = youtubeId(raw)
        btnTrailer.visibility = if (id.isBlank()) View.GONE else View.VISIBLE
        btnTrailer.setOnClickListener {
            val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id"))
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id"))
            runCatching { startActivity(app) }.onFailure { runCatching { startActivity(web) } }
        }
    }

    /** The provider sends either a bare video id or a full YouTube link. */
    private fun youtubeId(raw: String): String {
        val t = raw.trim()
        if (t.isBlank()) return ""
        if (!t.contains("/") && !t.contains("=")) return t
        val uri = runCatching { Uri.parse(t) }.getOrNull() ?: return ""
        return uri.getQueryParameter("v") ?: uri.lastPathSegment.orEmpty()
    }

    private fun formatRuntime(seconds: Int?): String {
        val s = seconds ?: return ""
        if (s <= 0) return ""
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    // ------------------------------------------------------------------
    // Movie
    // ------------------------------------------------------------------

    private fun bindMovie(m: Movie) {
        val year = m.year?.toString() ?: m.releaseDate.take(4)
        bindHeader(
            name = m.name, backdropUrl = m.backdrop, posterUrl = m.poster, rating = m.rating,
            length = formatRuntime(m.duration), year = year,
            genre = m.genre.ifBlank { m.categoryName }, story = m.plot,
            cast = m.cast, director = m.director
        )
        bindFavorite(m.id)
        bindTrailer(m.trailer)
        btnEpisodes.visibility = View.GONE

        val play = { startOver: Boolean ->
            if (startOver) vm.repo.clearResume(m.id)
            (activity as? MainActivity)?.play(
                url = m.url, title = m.name, poster = m.poster,
                backdrop = m.backdrop, kind = "movie", id = m.id
            )
        }
        btnPlay.setOnClickListener { play(false) }
        btnStartOver.setOnClickListener { play(true) }

        // A film part-watched resumes from PLAY NOW, with START OVER beside it.
        val resume = vm.repo.getResume(m.id)
        val resumable = resume != null && resume.positionMs > 10_000 && resume.progress < 0.95f
        btnPlayText.text = if (resumable) "RESUME" else "PLAY NOW"
        btnStartOver.visibility = if (resumable) View.VISIBLE else View.GONE

        btnMoreInfo.setOnClickListener {
            StbDialogs.showDetailInfo(
                requireContext(), m.name, listOf(
                    "Genre" to m.genre.ifBlank { m.categoryName },
                    "Release Date" to m.releaseDate.ifBlank { year },
                    "Duration" to formatRuntime(m.duration),
                    "Rating" to (m.rating?.takeIf { it > 0 }?.let { String.format("%.1f / 10", it) } ?: ""),
                    "Country" to m.country,
                    "Director" to m.director,
                    "Cast" to m.cast,
                    "Summary" to m.plot
                )
            )
        }

        relatedLabel = "Related Movies"
        related = vm.library.value.movies
            .filter { it.categoryId == m.categoryId && it.id != m.id }
            .take(30)
            .map { DetailCard(it.name, it.genre.ifBlank { it.categoryName }, image = it.poster.ifBlank { it.backdrop }, payload = it) }
        showTab(castTabShown)
    }

    // ------------------------------------------------------------------
    // Series
    // ------------------------------------------------------------------

    private fun bindSeries(s: Series) {
        val episodes = s.seasons.sumOf { it.episodes.size }
        val length = listOfNotNull(
            s.seasons.size.takeIf { it > 0 }?.let { if (it == 1) "1 Season" else "$it Seasons" },
            episodes.takeIf { it > 0 }?.let { "$it Episodes" }
        ).joinToString("  /  ")
        bindHeader(
            name = s.name, backdropUrl = s.backdrop, posterUrl = s.poster, rating = s.rating,
            length = length, year = s.year?.toString().orEmpty(),
            genre = s.genre.ifBlank { s.categoryName }, story = s.plot,
            cast = s.cast, director = s.director
        )
        bindFavorite(s.id)
        bindTrailer(s.trailer)
        btnStartOver.visibility = View.GONE
        btnEpisodes.visibility = View.VISIBLE
        btnEpisodes.setOnClickListener { (activity as? MainActivity)?.openSeasons(s) }

        // PLAY NOW carries on from the last episode watched, else starts at 1.
        val last = vm.repo.getContinueWatching().firstOrNull { it.kind == "episode" && it.seriesId == s.id }
        val lastEpisode = last?.let { r ->
            s.seasons.firstOrNull { it.number == r.seasonNumber }?.episodes?.firstOrNull { it.episode == r.episodeNumber }
        }
        val target = lastEpisode ?: s.seasons.firstOrNull()?.episodes?.firstOrNull()
        btnPlayText.text = when {
            lastEpisode != null -> "RESUME S${lastEpisode.season} E${lastEpisode.episode}"
            else -> "PLAY NOW"
        }
        btnPlay.setOnClickListener {
            if (target == null) {
                (activity as? MainActivity)?.openSeasons(s)
            } else {
                (activity as? MainActivity)?.play(
                    url = target.url,
                    title = s.name,
                    subtitle = "S${target.season} E${target.episode} - ${target.name}",
                    poster = s.poster,
                    backdrop = s.backdrop,
                    kind = "episode",
                    seriesId = s.id,
                    season = target.season,
                    episode = target.episode,
                    id = target.id
                )
            }
        }

        btnMoreInfo.setOnClickListener {
            StbDialogs.showDetailInfo(
                requireContext(), s.name, listOf(
                    "Genre" to s.genre.ifBlank { s.categoryName },
                    "Year" to s.year?.toString().orEmpty(),
                    "Seasons" to length,
                    "Rating" to (s.rating?.takeIf { it > 0 }?.let { String.format("%.1f / 10", it) } ?: ""),
                    "Director" to s.director,
                    "Cast" to s.cast,
                    "Summary" to s.plot
                )
            )
        }

        relatedLabel = "Related Series"
        related = vm.library.value.series
            .filter { it.categoryId == s.categoryId && it.id != s.id }
            .take(30)
            .map { DetailCard(it.name, it.genre.ifBlank { it.categoryName }, image = it.poster.ifBlank { it.backdrop }, payload = it) }
        showTab(castTabShown)
    }

    // ------------------------------------------------------------------
    // Tabs + bottom rail
    // ------------------------------------------------------------------

    private fun showTab(cast: Boolean) {
        castTabShown = cast
        tabRelated.isSelected = !cast
        tabCast.isSelected = cast
        railLabel.text = if (cast) "Cast & Crew" else relatedLabel

        val items = if (cast) castCards else related
        railEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rail.adapter = DetailCardAdapter(items) { card ->
            when (val p = card.payload) {
                is Movie -> (activity as? MainActivity)?.openMovieDetail(p)
                is Series -> (activity as? MainActivity)?.openSeriesDetail(p)
            }
        }
    }

    private fun focusRail() {
        rail.post { rail.getChildAt(0)?.requestFocus() }
    }
}

// ----------------------------------------------------------------------
// Bottom-rail cards
// ----------------------------------------------------------------------

data class DetailCard(
    val title: String,
    val caption: String,
    val image: String = "",
    val initials: String = "",
    val payload: Any? = null
)

class DetailCardAdapter(
    private val items: List<DetailCard>,
    private val onClick: (DetailCard) -> Unit
) : RecyclerView.Adapter<DetailCardAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val frame: View = v.findViewById(R.id.cardFrame)
        val image: ImageView = v.findViewById(R.id.cardImage)
        val initials: TextView = v.findViewById(R.id.cardInitials)
        val title: TextView = v.findViewById(R.id.cardTitle)
        val caption: TextView = v.findViewById(R.id.cardCaption)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_detail_card, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.title.text = c.title
        holder.caption.text = c.caption
        if (c.image.isNotBlank()) {
            holder.initials.visibility = View.GONE
            ImageLoader.loadPoster(holder.image, c.image, cornerRadiusDp = 4)
        } else {
            holder.image.setImageDrawable(null)
            holder.initials.visibility = View.VISIBLE
            holder.initials.text = c.initials
        }

        // Same focus treatment as the catalogue: yellow outline, yellow title.
        fun paint(focused: Boolean) {
            val ctx = holder.itemView.context
            holder.frame.background =
                if (focused) androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.series_selector) else null
            holder.title.setTextColor(ctx.getColor(if (focused) R.color.yellow else R.color.white))
        }
        paint(holder.itemView.isFocused)
        holder.itemView.setOnFocusChangeListener { _, has -> paint(has) }
        holder.itemView.setOnClickListener { if (c.payload != null) onClick(c) }
    }
}
