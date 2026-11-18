package com.nuvio.tv.ui.screens.player.autosync

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hands the decoded audio the player is about to output to [AutoSyncSpeechReference], unchanged.
 * It does nothing unless AutoSync is listening to this stream, and ignores passthrough audio
 * (still encoded, so there is nothing to listen to).
 */
@OptIn(UnstableApi::class)
internal class AutoSyncSpeechTapAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private var pcmFormat: Format? = null
    private var outputStreamOffsetUs = 0L
    private var pendingBuffer: ByteBuffer? = null
    private var mono = FloatArray(0)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        pcmFormat = inputFormat.takeIf {
            it.sampleMimeType == MimeTypes.AUDIO_RAW &&
                (it.pcmEncoding == C.ENCODING_PCM_16BIT || it.pcmEncoding == C.ENCODING_PCM_FLOAT) &&
                it.channelCount > 0 && it.sampleRate > 0
        }
        pendingBuffer = null
        AutoSyncSpeechReference.onDiscontinuity()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        this.outputStreamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val format = pcmFormat
        // A buffer the sink could not take at once comes back again: listen to it only once.
        if (format != null && buffer !== pendingBuffer) {
            pendingBuffer = buffer
            try {
                if (AutoSyncSpeechReference.wantsPcm()) {
                    tap(buffer, format, presentationTimeUs - outputStreamOffsetUs)
                }
            } catch (_: Throwable) {
                // Listening must never disturb playback.
            }
        }
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (consumed) pendingBuffer = null
        return consumed
    }

    override fun flush() {
        pendingBuffer = null
        AutoSyncSpeechReference.onDiscontinuity()
        super.flush()
    }

    override fun handleDiscontinuity() {
        pendingBuffer = null
        AutoSyncSpeechReference.onDiscontinuity()
        super.handleDiscontinuity()
    }

    private fun tap(buffer: ByteBuffer, format: Format, mediaTimeUs: Long) {
        val channels = format.channelCount
        val isFloat = format.pcmEncoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        val start = data.position()
        val frames = data.remaining() / (bytesPerSample * channels)
        if (frames <= 0) return
        if (mono.size < frames) mono = FloatArray(frames)
        // Dialogue lives in the centre channel of surround mixes; otherwise average the channels.
        val center = if (channels >= 3) 2 else -1
        for (frame in 0 until frames) {
            val base = start + frame * channels * bytesPerSample
            mono[frame] = if (center >= 0) {
                0.7f * sample(data, base + center * bytesPerSample, isFloat) +
                    0.15f * (sample(data, base, isFloat) + sample(data, base + bytesPerSample, isFloat))
            } else {
                var sum = 0f
                for (c in 0 until channels) sum += sample(data, base + c * bytesPerSample, isFloat)
                sum / channels
            }
        }
        AutoSyncSpeechReference.onPcm(mono, frames, format.sampleRate, mediaTimeUs)
    }

    private fun sample(buffer: ByteBuffer, index: Int, isFloat: Boolean): Float =
        if (isFloat) buffer.getFloat(index) else buffer.getShort(index) / 32_768f
}
