package com.example.orman

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/** Ortam sesleri: ses dosyası kullanmadan, rastgele gürültüden üretilir. */
object Sound {
    @Volatile private var run = false
    private var th: Thread? = null

    fun play(kind: String) {
        stop()
        if (kind == "off") return
        run = true
        th = Thread {
            val sr = 22050
            val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(maxOf(min, 4096) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            at.play()
            val rnd = Random()
            val buf = ShortArray(2048)
            var brown = 0f; var lp = 0f; var soft = 0f
            var ph1 = 0.0; var ph2 = 0.0
            while (run) {
                for (i in buf.indices) {
                    val w = rnd.nextFloat() * 2f - 1f
                    var v: Float
                    when (kind) {
                        "rain" -> { lp += 0.15f * (w - lp); v = (w - lp) * 0.35f }
                        "wave" -> {
                            brown = (brown + 0.02f * w) / 1.02f
                            ph1 += 2 * PI * 0.12 / sr
                            v = brown * 3.5f * (0.55f + 0.45f * sin(ph1).toFloat()) * 0.7f
                        }
                        else -> {
                            brown = (brown + 0.02f * w) / 1.02f
                            soft += 0.05f * (brown * 3.5f - soft)
                            ph2 += 2 * PI * 0.17 / sr
                            v = soft * 2f * (0.6f + 0.4f * sin(ph2).toFloat())
                        }
                    }
                    v = v.coerceIn(-1f, 1f)
                    buf[i] = (v * 0.5f * 32767f).toInt().toShort()
                }
                at.write(buf, 0, buf.size)
            }
            try { at.stop() } catch (e: Exception) { }
            at.release()
        }.also { it.start() }
    }

    fun stop() {
        run = false
        th?.let { try { it.join(500) } catch (e: Exception) { } }
        th = null
    }
}
