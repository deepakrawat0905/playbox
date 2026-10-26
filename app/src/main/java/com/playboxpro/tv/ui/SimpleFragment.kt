package com.playboxpro.tv.ui
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import com.playboxpro.tv.R
class SimpleFragment:Fragment(R.layout.fragment_simple){companion object{fun newInstance(t:String,b:String)=SimpleFragment().apply{arguments=Bundle().apply{putString("t",t);putString("b",b)}}};override fun onViewCreated(v:View,b:Bundle?){super.onViewCreated(v,b);v.findViewById<android.widget.TextView>(R.id.title).text=requireArguments().getString("t");v.findViewById<android.widget.TextView>(R.id.body).text=requireArguments().getString("b")}}
