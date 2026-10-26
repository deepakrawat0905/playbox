package com.playboxpro.tv.ui

import android.app.Dialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.playboxpro.tv.R

/**
 * The STB+ dialog family: a full-screen scrim, a back arrow top-left and a
 * centred column of pill rows. Options are inflated into a LinearLayout the
 * way STB+ does it, rather than a RecyclerView, so focus order is simple and
 * the list scrolls as one block.
 */
object StbDialogs {

    /**
     * STB+'s Select Sort Type choices, in its own order and wording.
     * "Default" is the order the provider sent the catalogue in.
     */
    val SORT_OPTIONS = listOf(
        "default" to "Default",
        "added" to "Latest Added",
        "rating" to "Rating Based",
        "az" to "A - Z",
        "za" to "Z - A"
    )


    private fun newDialog(context: Context, view: View): Dialog =
        Dialog(context, R.style.Theme_PlayBoxPro_Dialog).apply {
            setContentView(view)
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            ScreenFit.immersive(window)
        }

    /**
     * MORE INFO on the detail page. [rows] are label / value pairs; blank
     * values are left out so the panel never lists empty fields.
     */
    fun showDetailInfo(context: Context, title: String, rows: List<Pair<String, String>>) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_detail_info, null)
        val dialog = newDialog(context, view)
        view.findViewById<TextView>(R.id.infoTitle).text = title

        val body = android.text.SpannableStringBuilder()
        rows.filter { it.second.isNotBlank() }.forEachIndexed { i, (label, value) ->
            if (i > 0) body.append("\n\n")
            val start = body.length
            body.append(label).append("\n")
            body.setSpan(
                android.text.style.ForegroundColorSpan(context.getColor(R.color.yellow)),
                start, body.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            body.append(value)
        }
        view.findViewById<TextView>(R.id.infoBody).text = body

        val close = view.findViewById<View>(R.id.infoClose)
        close.setOnClickListener { dialog.dismiss() }
        dialog.show()
        close.post { close.requestFocus() }
    }

    /** Inflates one option pill into [container]. */
    private fun addRow(
        container: LinearLayout,
        label: String,
        checked: Boolean,
        onClick: () -> Unit
    ): View {
        val row = LayoutInflater.from(container.context)
            .inflate(R.layout.item_dialog_selection, container, false)

        row.findViewById<TextView>(R.id.text).text = label
        row.findViewById<ImageView>(R.id.check).visibility =
            if (checked) View.VISIBLE else View.GONE
        row.setOnClickListener { onClick() }

        container.addView(row)
        return row
    }

    /**
     * Single choice. The current value carries a check mark, matching STB+.
     */
    fun showSelection(
        context: Context,
        title: String,
        options: List<Pair<String, String>>,
        selectedId: String,
        onClose: () -> Unit = {},
        onPick: (String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_selection, null)
        val dialog = newDialog(context, view)
        // Runs however the dialog goes away: a pick, Cancel or BACK.
        dialog.setOnDismissListener { onClose() }

        view.findViewById<TextView>(R.id.dialogTitle).text = title
        val container = view.findViewById<LinearLayout>(R.id.itemContainer)

        var firstRow: View? = null
        options.forEach { (id, label) ->
            val row = addRow(container, label, id == selectedId) {
                onPick(id)
                dialog.dismiss()
            }
            if (firstRow == null || id == selectedId) firstRow = row
        }

        view.findViewById<Button>(R.id.btnDialogCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<ImageView>(R.id.btnBack).setOnClickListener { dialog.dismiss() }

        dialog.show()
        firstRow?.post { firstRow?.requestFocus() }
    }

    /**
     * Multi-select, used for hiding categories. Rows toggle in place and the
     * result is only committed on OK, so Cancel really cancels.
     */
    fun showMultiSelection(
        context: Context,
        title: String,
        options: List<Pair<String, String>>,
        initiallyChecked: Set<String>,
        onConfirm: (Set<String>) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_multiselection, null)
        val dialog = newDialog(context, view)

        view.findViewById<TextView>(R.id.dialogTitle).text = title
        val container = view.findViewById<LinearLayout>(R.id.itemContainer)

        val checked = initiallyChecked.toMutableSet()
        var firstRow: View? = null

        options.forEach { (id, label) ->
            lateinit var row: View
            row = addRow(container, label, id in checked) {
                if (id in checked) checked.remove(id) else checked.add(id)
                row.findViewById<ImageView>(R.id.check).visibility =
                    if (id in checked) View.VISIBLE else View.GONE
            }
            if (firstRow == null) firstRow = row
        }

        val ok = view.findViewById<Button>(R.id.btnOk)
        ok.visibility = View.VISIBLE
        ok.setOnClickListener {
            onConfirm(checked)
            dialog.dismiss()
        }

        val cancel = view.findViewById<Button>(R.id.btnCancel)
        cancel.visibility = View.VISIBLE
        cancel.setOnClickListener { dialog.dismiss() }

        view.findViewById<ImageView>(R.id.btnBack).setOnClickListener { dialog.dismiss() }

        dialog.show()
        firstRow?.post { firstRow?.requestFocus() }
    }

    /** Two-button confirm with an icon, title and message. */
    fun showConfirm(
        context: Context,
        iconRes: Int,
        title: String,
        message: String,
        okLabel: String = "OK",
        onOk: () -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_qsmart_confirm, null)
        val dialog = newDialog(context, view)

        view.findViewById<ImageView>(R.id.confirmIcon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.confirmTitle).text = title
        view.findViewById<TextView>(R.id.confirmMessage).text = message

        val ok = view.findViewById<Button>(R.id.btnOk)
        ok.text = okLabel
        ok.setOnClickListener {
            onOk()
            dialog.dismiss()
        }

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<ImageView>(R.id.btnBack).setOnClickListener { dialog.dismiss() }

        dialog.show()
        ok.post { ok.requestFocus() }
    }

    /** Credits sheet. */
    fun showCredits(context: Context, deviceLine: String) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_credits, null)
        val dialog = newDialog(context, view)

        view.findViewById<TextView>(R.id.creditsDevice).text = deviceLine

        val close = view.findViewById<Button>(R.id.btnClose)
        close.setOnClickListener { dialog.dismiss() }
        view.findViewById<ImageView>(R.id.btnBack).setOnClickListener { dialog.dismiss() }

        dialog.show()
        close.post { close.requestFocus() }
    }

    /**
     * Four-box PIN entry. A transparent field over the boxes takes the digits
     * so the remote's number keys work without a custom keypad.
     */
    fun showPin(
        context: Context,
        title: String,
        subtitle: String? = null,
        onEntered: (String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.pin_dialog, null)
        val dialog = newDialog(context, view)

        view.findViewById<TextView>(R.id.pinTitle).text = title
        subtitle?.let {
            view.findViewById<TextView>(R.id.pinSubtitle).apply {
                text = it
                visibility = View.VISIBLE
            }
        }

        val boxes = listOf(
            view.findViewById<TextView>(R.id.pin1),
            view.findViewById<TextView>(R.id.pin2),
            view.findViewById<TextView>(R.id.pin3),
            view.findViewById<TextView>(R.id.pin4)
        )
        val input = view.findViewById<EditText>(R.id.pinInput)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val digits = s?.toString().orEmpty()
                boxes.forEachIndexed { i, box ->
                    box.text = if (i < digits.length) digits[i].toString() else ""
                }
                // Four digits in: hand focus to OK so one press submits.
                if (digits.length == 4) view.findViewById<Button>(R.id.btnOk).requestFocus()
            }
        })

        val ok = view.findViewById<Button>(R.id.btnOk)
        ok.setOnClickListener {
            val pin = input.text.toString()
            if (pin.length == 4) {
                onEntered(pin)
                dialog.dismiss()
            }
        }
        // OK / Enter on the field itself submits too.
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == android.view.KeyEvent.KEYCODE_ENTER)
            ) { ok.performClick(); true } else false
        }

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<ImageView>(R.id.btnBack).setOnClickListener { dialog.dismiss() }

        // Remote digits are taken at the window level, so the PIN works no
        // matter which view the dialog decided to focus. Anything the hidden
        // field already consumed (touch, soft keyboard) never reaches here.
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val digit = when (keyCode) {
                in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
                    keyCode - android.view.KeyEvent.KEYCODE_0
                in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 ->
                    keyCode - android.view.KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            when {
                digit >= 0 -> {
                    if (input.text.length < 4) input.append(digit.toString())
                    true
                }
                keyCode == android.view.KeyEvent.KEYCODE_DEL -> {
                    val t = input.text.toString()
                    if (t.isNotEmpty()) input.setText(t.dropLast(1))
                    true
                }
                else -> false
            }
        }
        dialog.setOnShowListener { input.post { input.requestFocus() } }
        dialog.show()
    }

    /**
     * The EPG guide the live player opens with OK (STB+ alertdialog_epg).
     *
     * Days run down the left, programmes for the chosen day on the right:
     * start time, an archive marker, a LIVE badge on the programme on air,
     * the title and its synopsis. Today is selected when it has entries.
     */
    fun showEpgGuide(
        context: Context,
        channel: com.playboxpro.tv.model.Channel,
        programmes: List<com.playboxpro.tv.model.EpgProgram>
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_epg_guide, null)
        val dialog = newDialog(context, view)

        view.findViewById<TextView>(R.id.epgTitle).text = channel.name
        ImageLoader.loadLogo(view.findViewById(R.id.epgLogo), channel.logo)
        view.findViewById<View>(R.id.epgBack).setOnClickListener { dialog.dismiss() }

        val dates = view.findViewById<LinearLayout>(R.id.epgDates)
        val list = view.findViewById<LinearLayout>(R.id.epgList)
        val listScroll = view.findViewById<android.widget.ScrollView>(R.id.epgListScroll)
        val inflater = LayoutInflater.from(context)
        val locale = java.util.Locale.getDefault()
        val dayKey = java.text.SimpleDateFormat("yyyy-MM-dd", locale)
        val dayName = java.text.SimpleDateFormat("EEEE", locale)
        val dayDate = java.text.SimpleDateFormat("d MMM yyyy", locale)
        val clock = java.text.SimpleDateFormat("HH:mm", locale)
        val now = System.currentTimeMillis()

        fun showDay(items: List<com.playboxpro.tv.model.EpgProgram>) {
            list.removeAllViews()
            if (items.isEmpty()) {
                val row = inflater.inflate(R.layout.item_epg_programme, list, false)
                row.findViewById<TextView>(R.id.epgTime).text = "--:--"
                row.findViewById<TextView>(R.id.epgProgTitle).text = "No Information"
                row.findViewById<TextView>(R.id.epgProgDesc).text =
                    "No guide data is available for this channel."
                list.addView(row)
                return
            }
            var onAirRow: View? = null
            for (p in items) {
                val row = inflater.inflate(R.layout.item_epg_programme, list, false)
                row.findViewById<TextView>(R.id.epgTime).text = clock.format(java.util.Date(p.start))
                row.findViewById<TextView>(R.id.epgProgTitle).text = p.title
                row.findViewById<TextView>(R.id.epgProgDesc).text =
                    p.description.ifBlank { "No Description Available" }
                // Only the clock decides; the panel flag is derived from UTC and drifts.
                val onAir = now in p.start until p.end
                row.findViewById<View>(R.id.epgLiveBadge).visibility = if (onAir) View.VISIBLE else View.GONE
                if (onAir && onAirRow == null) onAirRow = row
                row.findViewById<View>(R.id.epgArchiveIcon).visibility =
                    if (p.hasArchive && channel.archive) View.VISIBLE else View.INVISIBLE
                list.addView(row)
            }
            // Open on what is airing now rather than at midnight.
            listScroll.post { listScroll.scrollTo(0, onAirRow?.top ?: 0) }
        }

        // One row per day the guide covers, today first when present.
        val byDay = programmes.groupBy { dayKey.format(java.util.Date(it.start)) }.toSortedMap()
        val todayKey = dayKey.format(java.util.Date(now))
        var firstRow: View? = null
        if (byDay.isEmpty()) {
            val dayRow = inflater.inflate(R.layout.item_epg_date, dates, false)
            dayRow.findViewById<TextView>(R.id.epgDayName).text = dayName.format(java.util.Date(now))
            dayRow.findViewById<TextView>(R.id.epgDate).text = dayDate.format(java.util.Date(now))
            dates.addView(dayRow)
            firstRow = dayRow
            showDay(emptyList())
        } else {
            for ((key, items) in byDay) {
                val day = items.first().start
                val dayRow = inflater.inflate(R.layout.item_epg_date, dates, false)
                dayRow.findViewById<TextView>(R.id.epgDayName).text = dayName.format(java.util.Date(day))
                dayRow.findViewById<TextView>(R.id.epgDate).text = dayDate.format(java.util.Date(day))
                dayRow.setOnFocusChangeListener { _, has -> if (has) showDay(items) }
                dayRow.setOnClickListener { showDay(items) }
                dates.addView(dayRow)
                if (firstRow == null || key == todayKey) firstRow = dayRow
            }
            showDay(byDay[todayKey] ?: byDay.values.first())
        }

        dialog.show()
        firstRow?.post { firstRow.requestFocus() }
    }
}
