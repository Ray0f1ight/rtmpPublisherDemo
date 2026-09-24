package com.example.rtc_demo.rtcManager

import androidx.camera.core.ImageProxy
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.github.faucamp.simplertmp.RtmpHandler
import kotlinx.coroutines.CoroutineScope
import yasea.SrsFlvMuxer

class RtmpPusher(private val scope: CoroutineScope) {

    /** 编码尺寸要和 CameraX 旋转后的帧尺寸一致，所以推到 [start] 里才建 */
    private var mediaCodecEncoder: MediaCodecEncoder? = null

    val isStartPush: Boolean
        get() = _isStartPush

    val isStartPushLiveData: LiveData<Boolean>
        get() = _isStartPushLiveData

    private val _isStartPushLiveData = MutableLiveData(false)

    @Volatile
    private var _isStartPush = false

    lateinit var flvMuxer: SrsFlvMuxer

    private var videoFlvTrack: Int = 100 //取自yasea.SrsFlvMuxer.VIDEO_TRACK

    private var audioFlvTrack: Int = 101  //取自yasea.SrsFlvMuxer.AUDIO_TRACK

    fun setRtmpHandler(listener: RtmpHandler.RtmpListener) {
        flvMuxer = SrsFlvMuxer(RtmpHandler(listener))
    }

    fun start(rtmp: String, width: Int, height: Int) {
        if (_isStartPush) return
        if (mediaCodecEncoder != null) {
            mediaCodecEncoder?.release()
        }
        val encoder = MediaCodecEncoder(width, height, scope)
        encoder.onH264Frame = { data, bufferInfo ->
            flvMuxer.writeSampleData(videoFlvTrack, data, bufferInfo)
        }
        encoder.onAacFrame = { data, bufferInfo ->
            flvMuxer.writeSampleData(audioFlvTrack, data, bufferInfo)
        }
        mediaCodecEncoder = encoder
        videoFlvTrack = flvMuxer.addTrack(encoder.videoFormat)
        audioFlvTrack = flvMuxer.addTrack(encoder.audioFormat)
        // 必须在 start 之前，否则 worker 线程连上后发的 onMetaData 里分辨率还是 0
        flvMuxer.setVideoResolution(width, height)
        encoder.start()
        flvMuxer.start(rtmp)
        _isStartPush = true
        notifyStartPushLiveData()
    }

    fun stop() {
        _isStartPush = false
        notifyStartPushLiveData()
        mediaCodecEncoder?.apply {
            release()
        }
        mediaCodecEncoder = null
        flvMuxer.stop()
    }

    private fun notifyStartPushLiveData() {
        _isStartPushLiveData.value = _isStartPush
    }


    fun writeImageToPush(image: ImageProxy) {
        val encoder = mediaCodecEncoder ?: return
        if (_isStartPush) {
            encoder.encodeYUV2H264(encoder.imageToByteArray(image), image.imageInfo.timestamp)
        }
    }

    fun writeAudioToPush(data: ByteArray, timestampNs: Long) {
        val encoder = mediaCodecEncoder ?: return
        if (_isStartPush) {
            encoder.encodePCM2AAC(data, timestampNs)
        }
    }

}
