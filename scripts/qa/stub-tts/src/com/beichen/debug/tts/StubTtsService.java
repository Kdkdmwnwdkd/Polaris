package com.beichen.debug.tts;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.util.Log;

/**
 * 桩 TTS 引擎：不真的合成语音，只按文本长度吐一段等长静音 PCM。
 *
 * 目的：让 TextSpeaker 的整条链路（ensureReady → setLanguage → speak →
 * UtteranceProgressListener → speaking 状态 → 音频焦点）能在没有语音包的
 * AOSP 镜像上跑起来，从而验证「朗读被长按打断」这类仲裁行为。
 *
 * 为什么是静音、且一口气写完：模拟器没有声卡，出真音也没人听得见；但 PCM
 * 的长度决定 AudioTrack 的播放时长，而播放时长决定 onDone 何时回调、speaking
 * 何时回落 —— 这一点对仲裁至关重要。不在 onSynthesizeText 里 sleep，
 * 是为了不占住 binder 线程（否则 onStop 可能排不进来）。
 */
public class StubTtsService extends TextToSpeechService {

    private static final String TAG = "StubTTS";
    private static final int SAMPLE_RATE = 22050;
    /** 每字毫秒数：中文 4.5 字/秒 ≈ 222ms，取整方便心算。 */
    private static final int MS_PER_CHAR = 220;
    private static final int MAX_MS = 12_000;

    private volatile boolean stopped = false;

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        Log.i(TAG, "onIsLanguageAvailable " + lang + "-" + country);
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[]{"cmn", "CHN", ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        Log.i(TAG, "onLoadLanguage " + lang + "-" + country);
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }

    @Override
    protected void onStop() {
        Log.i(TAG, "onStop");
        stopped = true;
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        String text = request.getCharSequenceText() == null
                ? "" : request.getCharSequenceText().toString();
        int ms = Math.min(Math.max(text.length(), 1) * MS_PER_CHAR, MAX_MS);
        Log.i(TAG, "onSynthesizeText len=" + text.length() + " -> " + ms + "ms : " + text);

        stopped = false;
        int maxBuf = callback.getMaxBufferSize();
        int ret = callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1);
        if (ret != TextToSpeech.SUCCESS) {
            Log.w(TAG, "start ret=" + ret);
            callback.done();
            return;
        }

        int bytesPerMs = SAMPLE_RATE * 2 / 1000;   // 16bit 单声道 = 44 B/ms
        int total = ms * bytesPerMs;
        byte[] zeros = new byte[Math.min(maxBuf, 4096)];
        int written = 0;
        while (written < total && !stopped) {
            int n = Math.min(zeros.length, total - written);
            int r = callback.audioAvailable(zeros, 0, n);
            if (r != TextToSpeech.SUCCESS) {
                Log.w(TAG, "audioAvailable ret=" + r + " at " + written);
                break;
            }
            written += n;
        }
        callback.done();
        Log.i(TAG, "done written=" + written + "/" + total + (stopped ? " (stopped)" : ""));
    }
}
