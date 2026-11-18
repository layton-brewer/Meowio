package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * Audio fallback for AutoSync, used only when the video has no embedded subtitles to compare with.
 *
 * While AutoSync is on, the player's own decoded audio is summarised into one small value per
 * 50 ms of media time: how loud the voice band (about 200 Hz to 3 kHz) is, and how much of the
 * sound sits in that band. Nothing is decoded twice and nothing extra is downloaded. When a run
 * needs it, the speech pattern is compared with the add-on subtitle's cue pattern to find a single
 * whole-film offset. The offset is applied only when the match is strong, clearly better than any
 * other offset, and the same in both halves of what was heard.
 */
internal object AutoSyncSpeechReference {
    const val BIN_MS = 50L
    private const val MAX_BINS = (6L * 60L * 60L * 1_000L / BIN_MS).toInt()
    /** Enough listening for any match; past this the tap stops doing any work. */
    private const val MAX_FILLED_BINS = (30L * 60L * 1_000L / BIN_MS).toInt()

    private const val MIN_HEARD_MS = 120_000L
    private const val GIVE_UP_HEARD_MS = 600_000L
    private const val RETRY_EVERY_HEARD_MS = 30_000L
    private const val MAX_WAIT_MS = 12L * 60L * 1_000L
    private const val POLL_MS = 3_000L
    private const val NO_AUDIO_GIVE_UP_MS = 30_000L

    private const val MAX_SHIFT_MS = 30_000L
    private const val MIN_CUES_HEARD = 25
    private const val MIN_PEAK_CORRELATION = 0.22
    private const val MIN_PEAK_MARGIN = 0.06
    private const val MIN_PEAK_Z_SCORE = 4.0
    private const val MIN_PEAK_RATIO = 1.6
    /** Cue starts are matched to speech onsets within this distance to place the offset exactly. */
    private const val ONSET_KERNEL_BINS = 6
    private const val ONSET_SEARCH_BINS = 20
    private const val HALF_AGREEMENT_MS = 300L
    private const val MIN_HALF_HEARD_MS = 50_000L
    /** Audio timing is coarser than subtitle timing: smaller corrections are treated as in sync. */
    private const val IN_SYNC_MS = 250L

    private const val SPEECH_ABOVE_FLOOR_DB = 9f
    private const val SPEECH_MIN_BAND_RATIO = 0.35f
    private const val SILENCE_DB = -70f
    private const val FLOOR_WINDOW_BINS = 600 // 30 s
    private const val FLOOR_PERCENTILE = 0.15

    private val lock = Any()
    private var sessionKey: String? = null
    private var bandDb = FloatArray(0)
    private var bandRatio = FloatArray(0)
    private var hasData = BooleanArray(0)
    private var filledBins = 0
    /** False while no decoded audio reached the tap: passthrough audio, or an engine without one. */
    private var pcmSeen = false

    // Current 50 ms bin being summed, and the voice-band filter state.
    private var accBin = -1
    private var accBand = 0.0
    private var accTotal = 0.0
    private var accFrames = 0
    private var filterRate = 0
    private var hpAlpha = 0f
    private var lpAlpha = 0f
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private var lpOut = 0f

    /** A stream started with AutoSync on: listen to its audio. The same stream keeps what was heard. */
    fun beginSession(key: String) {
        synchronized(lock) {
            if (sessionKey == key) return
            sessionKey = key
            bandDb = FloatArray(0)
            bandRatio = FloatArray(0)
            hasData = BooleanArray(0)
            filledBins = 0
            pcmSeen = false
            resetAccumulator()
        }
    }

    fun endSession() {
        synchronized(lock) {
            sessionKey = null
            bandDb = FloatArray(0)
            bandRatio = FloatArray(0)
            hasData = BooleanArray(0)
            filledBins = 0
            resetAccumulator()
        }
    }

    fun isListening(key: String): Boolean = synchronized(lock) { sessionKey == key }

    /** Cheap check made per audio buffer before any work. */
    fun wantsPcm(): Boolean = synchronized(lock) { sessionKey != null && filledBins < MAX_FILLED_BINS }

    /** A seek or a new audio format: the next buffer does not continue the last one. */
    fun onDiscontinuity() {
        synchronized(lock) {
            flushBin()
            resetAccumulator()
        }
    }

    /** [mono] holds [frames] samples starting at [mediaTimeUs] on the media timeline. */
    fun onPcm(mono: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long) {
        if (frames <= 0 || sampleRate <= 0 || mediaTimeUs < 0) return
        synchronized(lock) {
            if (sessionKey == null || filledBins >= MAX_FILLED_BINS) return
            pcmSeen = true
            if (filterRate != sampleRate) {
                configureFilters(sampleRate)
                resetAccumulator()
            }
            val startMs = mediaTimeUs / 1_000L
            val startBin = (startMs / BIN_MS).toInt()
            if (accBin >= 0 && (startBin < accBin || startBin > accBin + 2)) {
                flushBin()
                resetAccumulator()
            }
            val samplesPerBin = sampleRate * BIN_MS / 1_000.0
            val offsetInBin = (startMs - startBin * BIN_MS) * sampleRate / 1_000.0
            var bin = startBin
            var untilNextBin = (samplesPerBin - offsetInBin).toInt().coerceAtLeast(1)
            if (accBin != bin) {
                flushBin()
                accBin = bin
            }
            for (i in 0 until frames) {
                if (untilNextBin <= 0) {
                    flushBin()
                    bin++
                    accBin = bin
                    untilNextBin = samplesPerBin.toInt().coerceAtLeast(1)
                }
                val x = mono[i]
                val hp = hpAlpha * (hpPrevOut + x - hpPrevIn)
                hpPrevIn = x
                hpPrevOut = hp
                lpOut += lpAlpha * (hp - lpOut)
                accBand += (lpOut * lpOut).toDouble()
                accTotal += (x * x).toDouble()
                accFrames++
                untilNextBin--
            }
        }
    }

    /**
     * Waits while the viewer watches until enough has been heard, then returns a confident
     * whole-film retime of [cues], or null. Returns null at once when this stream is not tapped.
     */
    suspend fun awaitAlignment(sourceKey: String, cues: List<SubtitleSyncCue>): AutoSyncTimelineRetimeResult? {
        if (cues.size < MIN_CUES_HEARD || !isListening(sourceKey)) return null
        val started = TimeSource.Monotonic.markNow()
        var lastTriedHeardMs = 0L
        while (started.elapsedNow().inWholeMilliseconds < MAX_WAIT_MS) {
            currentCoroutineContext().ensureActive()
            if (!isListening(sourceKey)) return null
            val heardMs = synchronized(lock) { filledBins * BIN_MS }
            val noAudio = synchronized(lock) { !pcmSeen }
            if (noAudio && started.elapsedNow().inWholeMilliseconds > NO_AUDIO_GIVE_UP_MS) return null
            if (heardMs >= MIN_HEARD_MS && heardMs - lastTriedHeardMs >= RETRY_EVERY_HEARD_MS) {
                lastTriedHeardMs = heardMs
                val snapshot = snapshot() ?: return null
                val result = withContext(Dispatchers.Default) { align(snapshot, cues) }
                if (result != null) return result
                if (heardMs >= GIVE_UP_HEARD_MS) return null
            }
            delay(POLL_MS)
        }
        return null
    }

    private class Snapshot(val bandDb: FloatArray, val bandRatio: FloatArray, val hasData: BooleanArray)

    private fun snapshot(): Snapshot? = synchronized(lock) {
        if (hasData.isEmpty()) null else Snapshot(bandDb.copyOf(), bandRatio.copyOf(), hasData.copyOf())
    }

    private fun align(s: Snapshot, cues: List<SubtitleSyncCue>): AutoSyncTimelineRetimeResult? {
        val bins = s.hasData.size
        val speech = detectSpeech(s)
        val subtitle = BooleanArray(bins)
        for (cue in cues) {
            val from = (cue.startTimeMs / BIN_MS).toInt().coerceAtLeast(0)
            val to = (cue.endTimeMs / BIN_MS).toInt().coerceAtMost(bins - 1)
            for (b in from..to) subtitle[b] = true
        }

        val heard = IntArray(bins)
        var heardCount = 0
        for (b in 0 until bins) if (s.hasData[b]) heard[heardCount++] = b
        if (heardCount * BIN_MS < MIN_HEARD_MS) return null
        val cuesHeard = cues.count { cue ->
            val b = (cue.startTimeMs / BIN_MS).toInt()
            b in 0 until bins && s.hasData[b]
        }
        if (cuesHeard < MIN_CUES_HEARD) return null

        val maxShift = (MAX_SHIFT_MS / BIN_MS).toInt()
        val whole = correlate(heard, 0, heardCount, speech, subtitle, maxShift) ?: return null
        val peak = whole.peak()
        if (whole.r[peak] < MIN_PEAK_CORRELATION) return null
        val secondBest = whole.secondBest(peak)
        if (whole.r[peak] - secondBest < MIN_PEAK_MARGIN) return null
        if (secondBest > 0 && whole.r[peak] / secondBest < MIN_PEAK_RATIO) return null
        if (whole.zScore(peak) < MIN_PEAK_Z_SCORE) return null

        // Both halves of what was heard must point at the same offset on their own.
        val half = heardCount / 2
        if (half * BIN_MS < MIN_HALF_HEARD_MS) return null
        val first = correlate(heard, 0, half, speech, subtitle, maxShift) ?: return null
        val second = correlate(heard, half, heardCount, speech, subtitle, maxShift) ?: return null
        val agreeBins = (HALF_AGREEMENT_MS / BIN_MS).toInt()
        if (abs(first.peak() - peak) > agreeBins || abs(second.peak() - peak) > agreeBins) return null

        // The overlap peak is broad and leans with how long cues stay up after the line ends:
        // place it exactly by matching cue starts to the starts of speech near that peak.
        val shiftBins = onsetRefinedShift(s.hasData, speech, cues, peak - maxShift)
        var offsetMs = (shiftBins * BIN_MS).roundToLong()
        if (abs(offsetMs) < IN_SYNC_MS) offsetMs = 0L
        return offsetTimeline(cues, offsetMs, whole.r[peak], whole.r[peak] - secondBest)
    }

    private fun onsetRefinedShift(
        hasData: BooleanArray,
        speech: BooleanArray,
        cues: List<SubtitleSyncCue>,
        coarseShift: Int,
    ): Double {
        val bins = speech.size
        val kernel = FloatArray(bins)
        for (b in 1 until bins) {
            if (!speech[b] || speech[b - 1] || !hasData[b - 1]) continue
            val from = (b - ONSET_KERNEL_BINS).coerceAtLeast(0)
            val to = (b + ONSET_KERNEL_BINS).coerceAtMost(bins - 1)
            for (i in from..to) {
                val weight = 1f - abs(i - b).toFloat() / (ONSET_KERNEL_BINS + 1)
                if (weight > kernel[i]) kernel[i] = weight
            }
        }
        val starts = cues.map { (it.startTimeMs / BIN_MS).toInt() }
        val scores = DoubleArray(2 * ONSET_SEARCH_BINS + 1)
        for (j in scores.indices) {
            val shift = coarseShift + j - ONSET_SEARCH_BINS
            var sum = 0.0
            for (start in starts) {
                val b = start + shift
                if (b in 0 until bins && hasData[b]) sum += kernel[b]
            }
            scores[j] = sum
        }
        var best = ONSET_SEARCH_BINS
        for (j in scores.indices) if (scores[j] > scores[best]) best = j
        if (scores[best] <= 0.0) return coarseShift.toDouble()
        var shift = (coarseShift + best - ONSET_SEARCH_BINS).toDouble()
        if (best > 0 && best < scores.lastIndex) {
            val a = scores[best - 1]
            val c = scores[best + 1]
            val denom = a - 2 * scores[best] + c
            if (abs(denom) > 1e-12) shift += (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
        }
        return shift
    }

    /**
     * Speech where the voice band is well above the local noise floor and holds most of the
     * sound; short gaps between words are bridged and isolated clicks dropped.
     */
    private fun detectSpeech(s: Snapshot): BooleanArray {
        val bins = s.hasData.size
        val floor = FloatArray(bins)
        val block = FLOOR_WINDOW_BINS / 3
        var start = 0
        val scratch = FloatArray(FLOOR_WINDOW_BINS)
        while (start < bins) {
            val from = (start - block).coerceAtLeast(0)
            val to = min(bins, start + 2 * block)
            var n = 0
            for (b in from until to) if (s.hasData[b]) scratch[n++] = s.bandDb[b]
            val value = if (n == 0) 0f else {
                scratch.sort(0, n)
                scratch[(n * FLOOR_PERCENTILE).toInt().coerceIn(0, n - 1)]
            }
            for (b in start until min(bins, start + block)) floor[b] = value
            start += block
        }
        val raw = BooleanArray(bins)
        for (b in 0 until bins) {
            raw[b] = s.hasData[b] &&
                s.bandDb[b] > SILENCE_DB &&
                s.bandDb[b] > floor[b] + SPEECH_ABOVE_FLOOR_DB &&
                s.bandRatio[b] > SPEECH_MIN_BAND_RATIO
        }
        // Bridge gaps up to 250 ms, then drop runs shorter than 150 ms.
        val bridged = raw.copyOf()
        var lastOn = -1
        for (b in 0 until bins) {
            if (raw[b]) {
                if (lastOn >= 0 && b - lastOn in 2..5) for (g in lastOn + 1 until b) bridged[g] = true
                lastOn = b
            }
        }
        var runStart = -1
        for (b in 0..bins) {
            val on = b < bins && bridged[b]
            if (on && runStart < 0) runStart = b
            if (!on && runStart >= 0) {
                if (b - runStart < 3) for (g in runStart until b) bridged[g] = false
                runStart = -1
            }
        }
        return bridged
    }

    private class Correlation(val r: DoubleArray) {
        fun peak(): Int {
            var best = 0
            for (i in r.indices) if (r[i] > r[best]) best = i
            return best
        }

        fun secondBest(peak: Int): Double {
            var best = -1.0
            for (i in r.indices) if (abs(i - peak) > 20 && r[i] > best) best = r[i]
            return best
        }

        fun zScore(peak: Int): Double {
            var sum = 0.0
            var sq = 0.0
            for (v in r) {
                sum += v
                sq += v * v
            }
            val mean = sum / r.size
            val sd = sqrt(max(1e-12, sq / r.size - mean * mean))
            return (r[peak] - mean) / sd
        }
    }

    /** Pearson correlation of speech with the subtitle pattern moved by every shift in range. */
    private fun correlate(
        heard: IntArray,
        from: Int,
        to: Int,
        speech: BooleanArray,
        subtitle: BooleanArray,
        maxShift: Int,
    ): Correlation? {
        val n = to - from
        if (n <= 0) return null
        val bins = speech.size
        val r = DoubleArray(2 * maxShift + 1)
        var speechOn = 0
        for (i in from until to) if (speech[heard[i]]) speechOn++
        val ps = speechOn.toDouble() / n
        if (ps < 0.05 || ps > 0.9) return null
        for (k in -maxShift..maxShift) {
            var both = 0
            var subOn = 0
            for (i in from until to) {
                val b = heard[i]
                val src = b - k
                if (src < 0 || src >= bins || !subtitle[src]) continue
                subOn++
                if (speech[b]) both++
            }
            val pt = subOn.toDouble() / n
            val cov = both.toDouble() / n - ps * pt
            val denom = sqrt(ps * (1 - ps) * pt * (1 - pt))
            r[k + maxShift] = if (denom < 1e-9) 0.0 else cov / denom
        }
        return Correlation(r)
    }

    private fun offsetTimeline(
        cues: List<SubtitleSyncCue>,
        offsetMs: Long,
        score: Double,
        margin: Double,
    ): AutoSyncTimelineRetimeResult = AutoSyncTimelineRetimeResult(
        cues = cues.map { cue ->
            val start = (cue.startTimeMs + offsetMs).coerceAtLeast(0L)
            AutoSyncRetimedCue(
                originalStartTimeMs = cue.startTimeMs,
                originalEndTimeMs = cue.endTimeMs,
                startTimeMs = start,
                endTimeMs = (cue.endTimeMs + offsetMs).coerceAtLeast(start + 1L),
            )
        },
        groups = emptyList(),
        targetCoverage = 1.0,
        referenceCoverage = 1.0,
        skippedTargetCues = 0,
        skippedReferenceCues = 0,
        longestTargetSkipRun = 0,
        averageGroupCost = 0.0,
        oneToOneGroups = 0,
        oneToTwoGroups = 0,
        twoToOneGroups = 0,
        oneToThreeGroups = 0,
        threeToOneGroups = 0,
        twoToTwoGroups = 0,
        confident = true,
        alignmentSource = "audio",
        alignmentScale = 1.0,
        alignmentInterceptMs = offsetMs.toDouble(),
        activityScore = score,
        activityMargin = margin,
    )

    private fun configureFilters(sampleRate: Int) {
        filterRate = sampleRate
        val dt = 1f / sampleRate
        val hpRc = 1f / (2f * PI.toFloat() * 200f)
        val lpRc = 1f / (2f * PI.toFloat() * 3_000f)
        hpAlpha = hpRc / (hpRc + dt)
        lpAlpha = dt / (lpRc + dt)
    }

    private fun resetAccumulator() {
        accBin = -1
        accBand = 0.0
        accTotal = 0.0
        accFrames = 0
        hpPrevIn = 0f
        hpPrevOut = 0f
        lpOut = 0f
    }

    private fun flushBin() {
        val bin = accBin
        val frames = accFrames
        val band = accBand
        val total = accTotal
        accBand = 0.0
        accTotal = 0.0
        accFrames = 0
        if (bin < 0 || bin >= MAX_BINS || frames < 32) return
        ensureCapacity(bin + 1)
        if (!hasData[bin]) filledBins++
        hasData[bin] = true
        bandDb[bin] = (10.0 * log10(band / frames + 1e-12)).toFloat()
        bandRatio[bin] = if (total <= 1e-12) 0f else (band / total).toFloat().coerceIn(0f, 1f)
    }

    private fun ensureCapacity(size: Int) {
        if (size <= hasData.size) return
        val newSize = min(MAX_BINS, max(size, max(hasData.size * 2, 12_000)))
        bandDb = bandDb.copyOf(newSize)
        bandRatio = bandRatio.copyOf(newSize)
        hasData = hasData.copyOf(newSize)
    }
}
