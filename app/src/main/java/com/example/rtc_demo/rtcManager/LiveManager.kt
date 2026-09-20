package com.example.rtc_demo.rtcManager

import android.content.Context
import org.webrtc.PeerConnectionFactory

object LiveManager {

    fun init(context: Context) {
        val init = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(init)


    }


}