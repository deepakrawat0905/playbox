package com.playboxpro.tv.ui

import android.app.Activity
import android.app.Dialog
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.playboxpro.tv.R
import com.playboxpro.tv.data.AppRepository

/**
 * The legal terms page shown before first use. It cannot be dismissed
 * without accepting: BACK leaves the app instead.
 */
object LegalTerms {

    /** Shows the page unless it was already accepted; [onAccepted] runs once it is. */
    fun requireAccepted(activity: Activity, repo: AppRepository, onAccepted: () -> Unit = {}) {
        if (repo.termsAccepted) { onAccepted(); return }

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_terms, null)
        val dialog = Dialog(activity, R.style.Theme_PlayBoxPro_Dialog).apply {
            setContentView(view)
            setCancelable(false)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            ScreenFit.immersive(window)
        }

        val accept = view.findViewById<View>(R.id.btnAcceptTerms)
        accept.setOnClickListener {
            repo.termsAccepted = true
            dialog.dismiss()
            onAccepted()
        }
        view.findViewById<View>(R.id.termsLinkTerms).setOnClickListener {
            StbDialogs.showDetailInfo(activity, "Terms & Conditions", TERMS)
        }
        view.findViewById<View>(R.id.termsLinkPrivacy).setOnClickListener {
            StbDialogs.showDetailInfo(activity, "Privacy Policy", PRIVACY)
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dialog.dismiss()
                activity.finish()
                true
            } else false
        }

        dialog.show()
        accept.post { accept.requestFocus() }
    }

    private val TERMS = listOf(
        "The app" to "Pro Play Box is a media player only. It does not sell, host, supply or promote any channels, playlists or other content. You add your own playlist or provider details.",
        "Lawful use" to "You may use the app only with content you have the right to watch: your own media, services you subscribe to, private or internal streams you manage, and legally available free-to-air streams.",
        "Your responsibility" to "You must not use the app for unauthorised streaming of copyrighted content. You alone are responsible for the sources you add and for complying with the laws and copyrights that apply to you.",
        "Licence" to "Use of the app on a device needs an active licence for that device. A licence may be suspended or ended if the app is used unlawfully.",
        "No warranty" to "The app is provided as it is. We are not responsible for the availability, quality or legality of any stream you add."
    )

    private val PRIVACY = listOf(
        "What we store" to "Your device identifier (MAC address and app key) and the playlist details you register, so the licence and your playlists can be linked to this device.",
        "On the device" to "Settings, favourites, viewing history and resume positions are kept on this device only.",
        "Sharing" to "We do not sell your information. Playlist requests go directly from this device to the provider you added.",
        "Removal" to "You can delete your playlists from the management website at any time; clearing the app's data removes everything stored on the device."
    )
}
