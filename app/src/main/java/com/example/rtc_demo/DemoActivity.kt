package com.example.rtc_demo

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.rtc_demo.rtcManager.RtmpPusher
import com.github.faucamp.simplertmp.RtmpHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.IOException
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DemoActivity : AppCompatActivity() {

    private val previewView by lazy { findViewById<PreviewView>(R.id.preview) }

    private val btPush by lazy { findViewById<Button>(R.id.bt_push) }

    private val btPause by lazy { findViewById<Button>(R.id.bt_pause) }

    private val etRtmp by lazy { findViewById<EditText>(R.id.et_rtmp) }

    private val tvPushStatus by lazy { findViewById<TextView>(R.id.tv_push_status) }

    /** 相机是否已开，provider 异步就绪时靠它判断要不要补绑定 */
    private var isPreviewRequested = false

    private var cameraProvider: ProcessCameraProvider? = null

    /** 帧分析回调跑在这个单线程上，不占主线程 */
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** CameraX 旋转之后的帧尺寸，编码器要按它来建，所以要等第一帧到了才知道 */
    @Volatile
    private var frameSize: Size? = null

    private lateinit var pusher: RtmpPusher

    private var mic: AudioRecord? = null

    private var audioSampleJob: Job? = null

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == false) {
                Toast.makeText(this, R.string.record_audio_permission_denied, Toast.LENGTH_SHORT).show()
            }
            // 录音被拒不影响预览，相机权限才是开预览的硬条件
            if (result[Manifest.permission.CAMERA] == false) {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_demo)
        initPusher()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        initCameraProvider()
        btPush.setOnClickListener {
            // INTERNET 是安装时授予的普通权限，弹不出授权框，只能在这里兜底校验
            if (!hasPermission(Manifest.permission.INTERNET)) {
                Toast.makeText(this, R.string.internet_permission_denied, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 编码器宽高要和实际帧尺寸对齐，所以必须等相机出帧之后才能推
            val size = frameSize
            if (size == null) {
                Toast.makeText(this, R.string.camera_not_ready, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!pusher.isStartPush) {
                Toast.makeText(this, "开始推流", Toast.LENGTH_SHORT).show()
                pusher.start(etRtmp.text.toString(), size.width, size.height)
                startAudioRecord()
            }
        }
        btPause.setOnClickListener {
            if (!pusher.isStartPush) return@setOnClickListener
            Toast.makeText(this, "暂停推流", Toast.LENGTH_SHORT).show()
            pusher.stop()
            endAudioRecord()
        }
        // 进页面直接开相机，权限缺失时先申请，授权回调里再开
        val missing = REQUIRED_PERMISSIONS.filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            startCamera()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun initPusher() {
        pusher = RtmpPusher(lifecycleScope)
        pusher.setRtmpHandler(createRtmpListener())
        pusher.isStartPushLiveData.observe(this) { isStartPush ->
            tvPushStatus.text = if (isStartPush) "开始推流" else "未开始"
        }
    }

    private fun startAudioRecord() {
        mic = createMic()
        mic?.apply {
            startRecording()
            audioSampleJob = lifecycleScope.launch {
                readAudioSample()
            }
        }
    }

    private fun endAudioRecord() {
        audioSampleJob?.cancel()
        audioSampleJob = null
    }

    private fun initCameraProvider() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            // onCreate 里已经调过 startCamera，这里把当时缺 provider 的绑定补上
            if (isPreviewRequested) {
                bindUseCases()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        isPreviewRequested = true
        // provider 还没就绪时什么都不做，等 initCameraProvider 的回调
        bindUseCases()
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    TARGET_RESOLUTION,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            // 关键：CameraX 直接把输出帧转到 targetRotation（默认取当前显示方向），
            // 回调里的 ImageProxy 已经是正方向，rotationDegrees 恒为 0
            .setOutputImageRotationEnabled(true)
            // 编码慢于采集时丢中间帧，别让帧堆积
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(analysisExecutor, ::onFrameAvailable) }

        try {
            // 重复绑定会抛异常，先解绑
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        } catch (e: Exception) {
            Log.e(TAG, "bindToLifecycle failed", e)
            Toast.makeText(this, R.string.camera_open_failed, Toast.LENGTH_SHORT).show()
            stopCamera()
        }
    }

    private fun onFrameAvailable(image: ImageProxy) {
        image.use {
            frameSize = Size(it.width, it.height)
//            Log.v(
//                TAG,
//                "frame ${it.width}x${it.height}, rotation = ${it.imageInfo.rotationDegrees}, " +
//                        "timestamp = ${it.imageInfo.timestamp}"
//            )
            pusher.writeImageToPush(it)
        }
    }

    /** 采集参数必须和 [MediaCodecEncoder] 的 audioFormat 完全一致，否则编出来的音频会变速 */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createMic(): AudioRecord {
        //创建一个采集音频用于MediaCodec作编码的AudioRecord
        val minBufferSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBufferSize > 0) { "unsupported audio config, minBufferSize = $minBufferSize" }

        val recordFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        return AudioRecord.Builder()
            // VOICE_COMMUNICATION 会走系统的回声消除，外放拉流时不会把喇叭的声音又收回去
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(recordFormat)
            // 放大到最小值的若干倍，给编码线程留出卡顿余量，否则容易 overrun 丢采样
            .setBufferSizeInBytes(minBufferSize * PCM_BUFFER_FACTOR)
            .build()
    }

    private fun stopCamera() {
        isPreviewRequested = false
        cameraProvider?.unbindAll()
        frameSize = null
    }

    override fun onDestroy() {
        stopCamera()
        analysisExecutor.shutdown()
        mic?.stop()
        mic?.release()
        super.onDestroy()
    }

    /** 阻塞读麦克风，一直采到协程被取消为止，所以调用方要单独 launch 一个协程 */
    suspend fun readAudioSample() = withContext(Dispatchers.IO) {
        //采集mic的音频数据，并将输入传入com.example.rtc_demo.rtcManager.RtmpPusher.writeAudioToPush中做编码处理
        val record = mic ?: return@withContext
        // 时间戳按累计采样数推算，不用 read 返回的时刻，避免线程调度抖动让音视频对不齐
        val baseTimestampNs = System.nanoTime()
        var totalSamples = 0L
        // 一次读一个 AAC 帧的量（1024 个采样点），编码那边正好一帧一帧喂
        val buffer = ByteArray(PCM_FRAME_BYTES)
        try {
            while (isActive) {
                val readSize = record.read(buffer, 0, buffer.size)
                if (readSize < 0) {
                    Log.e(TAG, "AudioRecord.read failed, code = $readSize")
                    break
                }
                if (readSize == 0) {
                    continue
                }
                val timestampNs = baseTimestampNs + totalSamples * NANOS_PER_SECOND / AUDIO_SAMPLE_RATE
                totalSamples += readSize / BYTES_PER_SAMPLE
                // 必须拷一份：writeAudioToPush 是异步入队，复用 buffer 会把排队中的数据冲掉
                pusher.writeAudioToPush(buffer.copyOf(readSize), timestampNs)
            }
        } finally {
            record.release()
        }
    }

    private fun createRtmpListener(): RtmpHandler.RtmpListener {
        return object : RtmpHandler.RtmpListener {
            override fun onRtmpConnecting(msg: String?) {
                Log.d(TAG, "rtmp connecting, msg = $msg")
            }

            override fun onRtmpConnected(msg: String?) {
                Log.d(TAG, "rtmp connected, msg = $msg")
            }

            override fun onRtmpVideoStreaming() {
                Log.d(TAG, "rtmp video streaming")
            }

            override fun onRtmpAudioStreaming() {
                Log.d(TAG, "rtmp audio streaming")
            }

            override fun onRtmpStopped() {
                Log.d(TAG, "rtmp stopped")
            }

            override fun onRtmpDisconnected() {
                Log.d(TAG, "rtmp disconnected")
            }

            override fun onRtmpVideoFpsChanged(fps: Double) {
                Log.v(TAG, "rtmp video fps = $fps")
            }

            override fun onRtmpVideoBitrateChanged(bitrate: Double) {
                Log.v(TAG, "rtmp video bitrate = $bitrate")
            }

            override fun onRtmpAudioBitrateChanged(bitrate: Double) {
                Log.v(TAG, "rtmp audio bitrate = $bitrate")
            }

            override fun onRtmpSocketException(e: SocketException?) {
                Log.e(TAG, "rtmp socket exception", e)
            }

            override fun onRtmpIOException(e: IOException?) {
                Log.e(TAG, "rtmp io exception", e)
            }

            override fun onRtmpIllegalArgumentException(e: IllegalArgumentException?) {
                Log.e(TAG, "rtmp illegal argument exception", e)
            }

            override fun onRtmpIllegalStateException(e: IllegalStateException?) {
                Log.e(TAG, "rtmp illegal state exception", e)
            }

        }
    }

    private companion object {
        const val TAG = "DemoActivity"
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val TARGET_RESOLUTION = Size(1280, 720)

        /**
         * 必须和 MediaCodecEncoder 的 sampleRate 一致。
         * yasea 拼 AAC sequence header 时只映射了 44100/32000/22050/16000/11025，其余都会被当成 44100。
         */
        const val AUDIO_SAMPLE_RATE = 44_100
        const val PCM_BUFFER_FACTOR = 4

        /** 一帧 AAC 固定 1024 个采样点，单声道 16bit 就是 2048 字节 */
        const val PCM_FRAME_BYTES = 2048
        const val BYTES_PER_SAMPLE = 2
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
