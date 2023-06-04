package top.saymzx.scrcpy_android

import android.content.ClipData
import android.content.ClipDescription.MIMETYPE_TEXT_PLAIN
import android.media.*
import android.media.audiofx.LoudnessEnhancer
import android.util.Base64
import android.util.Log
import android.view.SurfaceView
import android.widget.Toast
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.*
import okio.BufferedSink
import okio.BufferedSource
import java.net.Inet4Address
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class Scrcpy(val device: Device, val main: MainActivity) {

  // ip地址
  private var ip = ""

  // 协程
  private var mainScope = MainScope()

  // 视频悬浮窗
  private lateinit var floatVideo: FloatVideo

  // 音频
  private var canAudio = true
  private lateinit var audioTrack: AudioTrack
  private lateinit var loudnessEnhancer: LoudnessEnhancer

  // 连接流
  private lateinit var adb: Dadb
  private lateinit var videoStream: BufferedSource
  private lateinit var audioStream: BufferedSource
  private lateinit var controlOutStream: BufferedSink
  private lateinit var controlInStream: BufferedSource

  // 解码器
  private lateinit var videoDecodec: MediaCodec
  private lateinit var audioDecodec: MediaCodec

  // 剪切板
  private var clipText = ""

  // 开始投屏
  fun start() {
    device.status = 0
    // 显示加载中
    main.appData.showLoading("连接中...", true) {
      main.appData.loadingDialog.cancel()
      stop()
    }
    main.appData.loadingDialog.show()
    mainScope.launch {
      try {
        // 获取IP地址
        ip = withContext(Dispatchers.IO) {
          Inet4Address.getByName(device.address)
        }.hostAddress!!
        // 发送server
        sendServer()
        // 转发端口
        tcpForward()
        // 配置视频解码
        setVideoDecodec()
        // 配置音频解码
        setAudioDecodec()
        // 配置音频播放
        if (canAudio) setAudioTrack()
        // 视频解码
        launch { decodeInput("video") }
        launch { decodeOutput("video") }
        // 音频解码
        if (canAudio) {
          launch { decodeInput("audio") }
          launch { decodeOutput("audio") }
        }
        // 配置控制
        launch { setControlOutput() }
        launch { setControlInput() }
        // 投屏中
        device.status = 1
        // 设置被控端熄屏（默认投屏后熄屏）
        delay(200)
        setPowerOff()
      } catch (e: Exception) {
        Toast.makeText(main, e.toString(), Toast.LENGTH_SHORT).show()
        Log.e("Scrcpy", e.toString())
        stop()
      }
    }
  }

  // 停止投屏
  fun stop() {
    main.appData.loadingDialog.cancel()
    device.status = -1
    try {
      floatVideo.hide()
    } catch (_: Exception) {
    }
    try {
      if (canAudio) {
        audioDecodec.stop()
        audioDecodec.release()
        loudnessEnhancer.release()
        audioTrack.stop()
        audioTrack.release()
      }
    } catch (_: Exception) {
    }
    try {
      videoDecodec.stop()
      videoDecodec.release()
    } catch (_: Exception) {
    }
    try {
      videoStream.close()
      audioStream.close()
      controlOutStream.close()
      controlInStream.close()
    } catch (_: Exception) {
    }
    try {
      // 恢复分辨率
      if (device.setResolution) mainScope.launch { runAdbCmd("wm size reset") }
      mainScope.launch { runAdbCmd("ps -ef | grep scrcpy | grep -v grep | grep -E \"^[a-z]+ +[0-9]+\" -o | grep -E \"[0-9]+\" -o | xargs kill -9") }
      mainScope.cancel()
      adb.close()
    } catch (_: Exception) {
    }
  }

  // 初始化音频播放器
  private fun setAudioTrack() {
    val sampleRate = 48000
    // 初始化音频播放器
    val minBufferSize = AudioTrack.getMinBufferSize(
      sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
    )
    audioTrack = AudioTrack.Builder().setAudioAttributes(
      AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    ).setAudioFormat(
      AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
    ).setBufferSizeInBytes(minBufferSize * 4).build()
    // 声音增强
    try {
      loudnessEnhancer = LoudnessEnhancer(audioTrack.audioSessionId)
      loudnessEnhancer.setTargetGain(4000)
      loudnessEnhancer.enabled = true
    } catch (_: IllegalArgumentException) {
    }
    audioTrack.play()
  }

  // 发送server
  private suspend fun sendServer() {
    // 连接ADB
    adb =
      Dadb.create(ip, device.port, AdbKeyPair.read(main.appData.privateKey, main.appData.publicKey))
    // 修改分辨率
    if (device.setResolution) runAdbCmd(
      "wm size ${main.appData.deviceWidth}x${main.appData.deviceHeight}"
    )
    // 停止旧服务
    runAdbCmd("ps -ef | grep scrcpy | grep -v grep | grep -E \"^[a-z]+ +[0-9]+\" -o | grep -E \"[0-9]+\" -o | xargs kill -9")
    // 快速启动
    val versionCode = BuildConfig.VERSION_CODE
    if (runAdbCmd(" ls -l /data/local/tmp/scrcpy_server$versionCode.jar ").contains("No such file or directory")) {
      runAdbCmd("rm /data/local/tmp/serverBase64")
      runAdbCmd("rm /data/local/tmp/scrcpy_server*")
      val serverFileBase64 = Base64.encodeToString(withContext(Dispatchers.IO) {
        val server = main.resources.openRawResource(R.raw.scrcpy_server)
        val buffer = ByteArray(server.available())
        server.read(buffer)
        server.close()
        buffer
      }, 2)
      runAdbCmd("echo $serverFileBase64 >> /data/local/tmp/serverBase64\n")
      runAdbCmd("base64 -d < /data/local/tmp/serverBase64 > /data/local/tmp/scrcpy_server$versionCode.jar && rm /data/local/tmp/serverBase64")
    }
    runAdbCmd("CLASSPATH=/data/local/tmp/scrcpy_server$versionCode.jar app_process / com.genymobile.scrcpy.Server 2.0 video_codec=${device.videoCodec} max_size=${device.maxSize} video_bit_rate=${device.videoBit} max_fps=${device.fps} > /dev/null 2>&1 &")
  }

  // 转发端口
  private suspend fun tcpForward() {
    var connect = 0
    withContext(Dispatchers.IO) {
      for (i in 1..100) {
        try {
          if (connect == 0) {
            videoStream = adb.open("tcp:6006").source
            connect = 1
          }
          if (connect == 1) {
            audioStream = adb.open("tcp:6006").source
            connect = 2
          }
          val control = adb.open("tcp:6006")
          controlOutStream = control.sink
          controlInStream = control.source
          break
        } catch (_: Exception) {
          Log.i("Scrcpy", "连接失败，再次尝试")
          delay(50)
        }
      }
    }
  }

  // 视频解码器
  private suspend fun setVideoDecodec() {
    // CodecMeta
    withContext(Dispatchers.IO) {
      videoStream.readInt()
      val remoteVideoWidth = videoStream.readInt()
      val remoteVideoHeight = videoStream.readInt()
      // 显示悬浮窗
      floatVideo = FloatVideo(this@Scrcpy, remoteVideoWidth, remoteVideoHeight)
    }
    main.appData.loadingDialog.cancel()
    floatVideo.show()
    // 创建解码器
    val codecMime =
      if (device.videoCodec == "h265") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
    videoDecodec = MediaCodec.createDecoderByType(codecMime)
    val mediaFormat = MediaFormat.createVideoFormat(
      codecMime,
      floatVideo.remoteVideoWidth,
      floatVideo.remoteVideoHeight
    )
    // 获取视频标识头
    val csd0 = readFrame(videoStream)
    val csd1 = readFrame(videoStream)
    mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
    mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
    // 配置解码器
    videoDecodec.configure(
      mediaFormat,
      floatVideo.floatVideo.findViewById<SurfaceView>(R.id.float_video_surface).holder.surface,
      null,
      0
    )
    // 启动解码器
    videoDecodec.start()
    // 解析首帧，解决开始黑屏问题
    var inIndex: Int
    do {
      inIndex = videoDecodec.dequeueInputBuffer(0)
    } while (inIndex < 0)
    videoDecodec.getInputBuffer(inIndex)!!.put(csd0)
    videoDecodec.queueInputBuffer(inIndex, 0, csd0.size, 0, 0)
    do {
      inIndex = videoDecodec.dequeueInputBuffer(0)
    } while (inIndex < 0)
    videoDecodec.getInputBuffer(inIndex)!!.put(csd1)
    videoDecodec.queueInputBuffer(inIndex, 0, csd1.size, 0, 0)
  }

  // 音频解码器
  private suspend fun setAudioDecodec() {
    // 创建音频解码器
    audioDecodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    // 是否不支持音频（安卓11以下不支持）
    val can = withContext(Dispatchers.IO) { audioStream.readInt() }
    if (can == 0) {
      canAudio = false
      return
    }
    // 音频参数
    val sampleRate = 48000
    val channelCount = 2
    val bitRate = 128000
    val mediaFormat =
      MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channelCount)
    mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
    // 获取音频标识头
    mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(readFrame(audioStream)))
    // csd1和csd2暂时没用到，所以默认全是用0
    val csd12bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    val csd12ByteBuffer = ByteBuffer.wrap(csd12bytes, 0, csd12bytes.size)
    mediaFormat.setByteBuffer("csd-1", csd12ByteBuffer)
    mediaFormat.setByteBuffer("csd-2", csd12ByteBuffer)
    // 配置解码器
    audioDecodec.configure(mediaFormat, null, null, 0)
    // 启动解码器
    audioDecodec.start()
  }

  // 输入
  private suspend fun decodeInput(mode: String) {
    val stream = if (mode == "video") videoStream else audioStream
    val decodec = if (mode == "video") videoDecodec else audioDecodec
    var inIndex = -1
    val isCheckScreen = mode == "video"
    var zeroFrameNum = 0
    // 开始解码
    while (mainScope.isActive) {
      // 向缓冲区输入数据帧
      val buffer = readFrame(stream)
      // 连续4个空包检测是否熄屏了
      if (isCheckScreen) {
        if (buffer.size < 150) {
          zeroFrameNum++
          if (zeroFrameNum > 4) {
            zeroFrameNum = 0
            checkScreenOff()
          }
        }
      }
      // 找到一个空的输入缓冲区
      while (mainScope.isActive) {
        inIndex = decodec.dequeueInputBuffer(0)
        if (inIndex >= 0) break
        delay(4)
      }
      decodec.getInputBuffer(inIndex)!!.put(buffer)
      // 提交解码器解码
      decodec.queueInputBuffer(inIndex, 0, buffer.size, 0, 0)
    }
  }

  // 输出
  private suspend fun decodeOutput(mode: String) {
    val decodec = if (mode == "video") videoDecodec else audioDecodec
    var outIndex: Int
    val bufferInfo = MediaCodec.BufferInfo()
    if (mode == "video") {
      var decodeNum = 0
      val decodeMaxNum = device.fps * 2 - 1
      while (mainScope.isActive) {
        // 找到已完成的输出缓冲区
        outIndex = decodec.dequeueOutputBuffer(bufferInfo, 0)
        if (outIndex >= 0) {
          // 每两秒检查一次是否旋转，防止未收到旋转信息
          decodeNum++
          if (decodeNum > decodeMaxNum) {
            decodeNum = 0
            floatVideo.checkRotation(
              decodec.getOutputFormat(outIndex).getInteger("width"),
              decodec.getOutputFormat(outIndex).getInteger("height")
            )
          }
          decodec.releaseOutputBuffer(outIndex, true)
        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          floatVideo.checkRotation(
            decodec.outputFormat.getInteger("width"),
            decodec.outputFormat.getInteger("height")
          )
        } else {
          delay(4)
          continue
        }
      }
    } else {
      while (mainScope.isActive) {
        // 找到已完成的输出缓冲区
        outIndex = decodec.dequeueOutputBuffer(bufferInfo, 0)
        if (outIndex < 0) {
          delay(4)
          continue
        }
        audioTrack.write(
          decodec.getOutputBuffer(outIndex)!!, bufferInfo.size, AudioTrack.WRITE_NON_BLOCKING
        )
        decodec.releaseOutputBuffer(outIndex, false)
      }
    }
  }

  // 控制报文输出
  private suspend fun setControlOutput() {
    // 清除投屏之前的信息
    floatVideo.controls.clear()
    // 检查剪切板
    var checkNum = 0
    while (mainScope.isActive) {
      checkNum++
      if (checkNum > 100) {
        checkNum = 0
        checkClipBoard()
      }
      if (floatVideo.controls.isEmpty()) {
        delay(4)
        continue
      }
      try {
        val control = floatVideo.controls.poll()
        withContext(Dispatchers.IO) {
          control?.let { controlOutStream.write(it) }
          controlOutStream.flush()
        }
      } catch (_: NullPointerException) {
      }
    }
  }

  // 防止被控端熄屏
  private fun checkScreenOff() {
    mainScope.launch {
      if (!runAdbCmd("dumpsys deviceidle | grep mScreenOn").contains("mScreenOn=true")) runAdbCmd("input keyevent 26")
    }
  }

  // 被控端熄屏
  private fun setPowerOff() {
    val byteBuffer = ByteBuffer.allocate(2)
    byteBuffer.clear()
    byteBuffer.put(10)
    byteBuffer.put(0)
    byteBuffer.flip()
    floatVideo.controls.offer(byteBuffer.array())
  }

  // 控制报文输入
  private suspend fun setControlInput() {
    while (mainScope.isActive) {
      when (withContext(Dispatchers.IO) {
        try {
          controlInStream.readByte().toInt()
        } catch (_: IllegalStateException) {
          -1
        }
      }) {
        // 剪切板报告报文
        0 -> {
          val newClipText = String(
            withContext(Dispatchers.IO) {
              controlInStream.readByteArray(
                controlInStream.readInt().toLong()
              )
            },
            StandardCharsets.UTF_8
          )
          main.appData.clipBorad.setPrimaryClip(
            ClipData.newPlainText(
              MIMETYPE_TEXT_PLAIN,
              newClipText
            )
          )
          clipText = newClipText
          break
        }
        // 设置剪切板回应报文
        1 -> {
          withContext(Dispatchers.IO) {
            controlInStream.readLong()
          }
          break
        }
      }
      delay(500)
    }
  }

  // 检测剪切板
  private fun checkClipBoard() {
    val clip = main.appData.clipBorad.primaryClip
    val newClipText =
      if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text.toString() else ""
    if (clipText != newClipText) {
      clipText = newClipText
      setClipBoard(newClipText)
    }
  }

  // 设置剪切板文本
  private fun setClipBoard(text: String) {
    val textByteArray = text.toByteArray(StandardCharsets.UTF_8)
    val byteBuffer = ByteBuffer.allocate(14 + textByteArray.size)
    byteBuffer.clear()
    byteBuffer.put(9)
    byteBuffer.putLong(101)
    byteBuffer.put(0)
    byteBuffer.putInt(textByteArray.size)
    byteBuffer.put(textByteArray)
    byteBuffer.flip()
    floatVideo.controls.offer(byteBuffer.array())
  }

  // 从socket流中解析数据
  private suspend fun readFrame(stream: BufferedSource): ByteArray {
    return withContext(Dispatchers.IO) {
      try {
        stream.readByteArray(stream.readInt().toLong())
      } catch (_: IllegalStateException) {
        ByteArray(1)
      }
    }
  }

  // 执行adb命令
  private suspend fun runAdbCmd(cmd: String): String {
    return withContext(Dispatchers.IO) { adb.shell(cmd).allOutput }
  }

}