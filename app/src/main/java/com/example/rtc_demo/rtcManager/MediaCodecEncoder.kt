package com.example.rtc_demo.rtcManager

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

class MediaCodecEncoder(
    private val width: Int,
    private val height: Int,
    private val scope: CoroutineScope,
    private val frameRate: Int = DEFAULT_FRAME_RATE,
    private val bitRate: Int = width * height * BITRATE_PER_PIXEL,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val channelCount: Int = DEFAULT_CHANNEL_COUNT,
    private val audioBitRate: Int = DEFAULT_AUDIO_BIT_RATE
) {

    private val videoEncoder: MediaCodec = MediaCodec.createEncoderByType(MimeTypes.VIDEO_H264)

    private val audioEncoder: MediaCodec = MediaCodec.createEncoderByType(MimeTypes.AUDIO_AAC)

    /** 编码结果回调，回调线程就是调用 [encode2H264] 的线程 */
    var onH264Frame: ((data: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) -> Unit)? = null

    /**
     * AAC 编码结果回调，回调线程就是调用 [encode2AAC] 的线程。
     * 第一帧带 BUFFER_FLAG_CODEC_CONFIG，也就是 AudioSpecificConfig，
     * yasea 靠它生成 FLV 的 audio sequence header，所以这一帧也必须转发出去。
     */
    var onAacFrame: ((data: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) -> Unit)? = null

    /** 编码器输出的 SPS/PPS，RTMP 发 sequence header 时要用 */
    var codecConfig: ByteBuffer? = null
        private set

    lateinit var videoFormat: MediaFormat

    lateinit var audioFormat: MediaFormat

    private val collectJob = CopyOnWriteArrayList<Job>()


    /**
     * 视频、音频各自的时间基 0 点，用来把上层时间戳归零，避免 FLV 时间戳从开机时刻算起。
     *
     * 两条流必须各自持有基准：视频时间戳来自相机传感器时钟（由 SENSOR_INFO_TIMESTAMP_SOURCE 决定，
     * 可能是 CLOCK_BOOTTIME），音频时间戳来自 System.nanoTime()（CLOCK_MONOTONIC），
     * 两者相差开机以来的深度睡眠时长。共用一个基准的话，后归零的那条流会算出巨大的负数 pts。
     */
    private var videoBaseTimestampNs = NO_TIMESTAMP

    private var audioBaseTimestampNs = NO_TIMESTAMP

    /** 上层送进来的 NV12 数据和对应的 image.timestamp（纳秒） */
    private var yuvChannel = Channel<Pair<ByteArray, Long>>(10)

    private var pcmChannel = Channel<Pair<ByteArray, Long>>(10)

    @Volatile
    private var isStartEncoding = false

    @Volatile
    private var state = State.ACTIVE



    init {
        //启动decoder
        videoFormat = MediaFormat.createVideoFormat(MimeTypes.VIDEO_H264, width, height).apply {
            // 必须和 imageToByteArray 输出的 NV12 对上
            setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, EncoderCapabilities.BITRATE_MODE_CBR)
        }
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // createAudioFormat 会把 KEY_SAMPLE_RATE / KEY_CHANNEL_COUNT 填好，
        // yasea 的 SrsFlvMuxer.addTrack 靠这两个值拼 AAC sequence header，缺了会抛异常
        audioFormat = MediaFormat.createAudioFormat(MimeTypes.AUDIO_AAC, sampleRate, channelCount).apply {
            // RTMP/FLV 只认 AAC-LC，HE-AAC 那些带 SBR 的播放端支持不一致
            setInteger(MediaFormat.KEY_AAC_PROFILE, CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_MAX_INPUT_SIZE)
        }
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        if (state == State.RELEASE) {
            Log.i(TAG, "start-- isRelease now")
            return
        } else if (state == State.RUNNING) {
            Log.i(TAG, "start-- running now")
            return
        }
        state = State.RUNNING
        isStartEncoding = true
        videoEncoder.start()
        audioEncoder.start()
        scope.launch {
            yuvChannel.receiveAsFlow().collect { (data, timestampNs) ->
                encode2H264(data, timestampNs)
            }
        }
        scope.launch {
            pcmChannel.receiveAsFlow().collect { (data, timestampNs) ->
                encode2AAC(data, timestampNs)
            }
        }
    }

    fun release() {
        if (state == State.RELEASE) {
            Log.i(TAG, "release-- isRelease now")
            return
        }
        state = State.RELEASE
        onH264Frame = null
        onAacFrame = null
        videoEncoder.release()
        audioEncoder.release()
    }

    fun stop() {
        // 已经 release 的 codec 再调 stop 会抛 IllegalStateException，
        // 没 start 过的 codec 调 stop 同样非法，所以只有 RUNNING 能往下走
        if (state != State.RUNNING) {
            Log.i(TAG, "stop-- not running now, state = $state")
            return
        }
        state = State.STOP
        videoEncoder.stop()
        audioEncoder.stop()
    }



    /**
     * 把 YUV_420_888 的 [ImageProxy] 拍平成 NV12（完整 Y 平面 + UV 交错），
     * 对应编码器的 COLOR_FormatYUV420SemiPlanar。
     *
     * 开了 ImageAnalysis 的 setOutputImageRotationEnabled 之后 ImageProxy.getImage() 可能为 null，
     * 所以只能走 planes，另外 image 由调用方负责 close。
     */
    fun imageToByteArray(image: ImageProxy): ByteArray {
        require(image.format == ImageFormat.YUV_420_888) {
            "unsupported image format: ${image.format}"
        }
        val width = image.width
        val height = image.height
        val ySize = width * height
        val output = ByteArray(ySize + ySize / 2)

        copyPlane(image.planes[0], width, height, output, 0, 1)
        // NV12 的色度是 U 在前、V 在后交错写入同一段
        copyPlane(image.planes[1], width / 2, height / 2, output, ySize, 2)
        copyPlane(image.planes[2], width / 2, height / 2, output, ySize + 1, 2)

        return output
    }

    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
        outputPixelStride: Int
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride  //行步长，同一列中，相邻两行起始位置在缓冲区中的字节距离，单位：字节
        val pixelStride = plane.pixelStride //像素步长，同一行内，相邻两个像素样本在缓冲区中的字节距离，单位：字节

        // 源和目标都紧凑排列时整块拷，绕开逐像素循环
        if (pixelStride == 1 && outputPixelStride == 1 && rowStride == width) {
            buffer.position(0)
            buffer.get(output, outputOffset, width * height)
            return
        }

        val row = ByteArray(rowStride)
        var outputPos = outputOffset
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(row, 0, minOf(rowStride, buffer.remaining()))
            if (pixelStride == 1 && outputPixelStride == 1) {
                System.arraycopy(row, 0, output, outputPos, width)
                outputPos += width
            } else {
                for (x in 0 until width) {
                    output[outputPos] = row[x * pixelStride]
                    outputPos += outputPixelStride
                }
            }
        }
    }

    /**
     * @param timestampNs 采集帧的 [Image.timestamp]，单位纳秒
     */
    fun encodeYUV2H264(data: ByteArray, timestampNs: Long) {
        yuvChannel.trySend(data to timestampNs)
    }

    fun encodePCM2AAC(data: ByteArray, timestampNs: Long) {
        pcmChannel.trySend(data to timestampNs)
    }


    private fun encode2H264(data: ByteArray, timestampNs: Long) {
        if (state != State.RUNNING) return
//        Log.i(TAG, "encode2H264 timestampNs:${toVideoPresentationTimeUs(timestampNs)}")
    //通过videoDecoder将data（YUV数据）转化为h264数据
        val inputIndex = videoEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex >= 0) {
            videoEncoder.getInputBuffer(inputIndex)?.apply {
                clear()
                put(data)
            }
            videoEncoder.queueInputBuffer(inputIndex, 0, data.size, toVideoPresentationTimeUs(timestampNs), 0)
        }
        drainEncoder(videoEncoder) { h264, bufferInfo ->
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                // duplicate 共享数据但各自维护 position，回调读走数据不影响这份缓存
                codecConfig = h264.duplicate()
            }
            onH264Frame?.invoke(h264, bufferInfo)
        }
    }


    /**
     * 取出 [codec] 里已经就绪的编码数据，通过 [onFrame] 回调出去。
     * bufferInfo.flags 带 BUFFER_FLAG_CODEC_CONFIG 时是 SPS/PPS（音频则是 AudioSpecificConfig），
     * 带 BUFFER_FLAG_KEY_FRAME 时是 IDR 帧。
     */
    private fun drainEncoder(
        codec: MediaCodec,
        onFrame: (data: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) -> Unit
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (outputIndex < 0) continue // 例如 INFO_OUTPUT_FORMAT_CHANGED，无缓冲区可读，继续取下一个

            val outputBuffer = codec.getOutputBuffer(outputIndex)
            if (outputBuffer != null && bufferInfo.size > 0) {
                outputBuffer.position(bufferInfo.offset)
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                // 必须拷出来，releaseOutputBuffer 之后 outputBuffer 就失效了
                val encoded = ByteBuffer.allocate(bufferInfo.size).put(outputBuffer).apply { flip() }
                onFrame(encoded, bufferInfo)
            }
            codec.releaseOutputBuffer(outputIndex, false)

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    /**
     * @param data PCM 数据，格式要和 [audioFormat] 一致（16bit、[sampleRate]、[channelCount]）
     * @param timestampNs 这段采样的采集时刻，单位纳秒，必须和视频用同一个时间基
     */
    private fun encode2AAC(data: ByteArray, timestampNs: Long) {
        if (state != State.RUNNING) return
//        Log.i(TAG, "encode2AAC timestampNs:${toAudioPresentationTimeUs(timestampNs)}")
        //audioEncoder对采样数据进行编码
        val inputIndex = audioEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex >= 0) {
            audioEncoder.getInputBuffer(inputIndex)?.apply {
                clear()
                put(data)
            }
            audioEncoder.queueInputBuffer(inputIndex, 0, data.size, toAudioPresentationTimeUs(timestampNs), 0)
        }
        drainEncoder(audioEncoder) { aac, bufferInfo ->
            onAacFrame?.invoke(aac, bufferInfo)
        }
    }

    /** 以第一帧画面为 0 点，把纳秒的采集时间戳换成编码器要的微秒 pts */
    private fun toVideoPresentationTimeUs(timestampNs: Long): Long {
        if (videoBaseTimestampNs == NO_TIMESTAMP) {
            videoBaseTimestampNs = timestampNs
        }
        return (timestampNs - videoBaseTimestampNs) / NANOS_PER_MICRO
    }

    /** 以第一段采样为 0 点，和视频分开的原因见 [videoBaseTimestampNs] */
    private fun toAudioPresentationTimeUs(timestampNs: Long): Long {
        if (audioBaseTimestampNs == NO_TIMESTAMP) {
            audioBaseTimestampNs = timestampNs
        }
        return (timestampNs - audioBaseTimestampNs) / NANOS_PER_MICRO
    }

    private enum class State {
        ACTIVE, RUNNING, STOP, RELEASE
    }

    private companion object {
        const val DEFAULT_FRAME_RATE = 30
        const val I_FRAME_INTERVAL_SECONDS = 1
        const val BITRATE_PER_PIXEL = 4
        const val DEFAULT_SAMPLE_RATE = 44_100
        const val DEFAULT_CHANNEL_COUNT = 1
        const val DEFAULT_AUDIO_BIT_RATE = 64_000
        /** 一帧 AAC 是 1024 个采样点，双声道 16bit 也就 4KB，留一倍余量 */
        const val AUDIO_MAX_INPUT_SIZE = 8 * 1024
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val NANOS_PER_MICRO = 1_000L
        const val NO_TIMESTAMP = -1L

        private const val TAG = "MediaCodecEncoder"
    }
}