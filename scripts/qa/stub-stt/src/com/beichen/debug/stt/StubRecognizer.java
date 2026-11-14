package com.beichen.debug.stt;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;

/**
 * 只做一件事：把 SpeechRecognizer 这条链路接通，行为由系统属性控制，方便 adb 改。
 *
 * 这不是语音识别。它不认识任何话，只按 debug.stt.mode 决定「回结果 / 回某个错误 / 不回」。
 * 用来验证 App 侧的状态机（开始、结束、取消、超时、错误映射），验证不了识别质量。
 */
public class StubRecognizer extends RecognitionService {

    private static final String TAG = "StubSTT";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Callback cb;
    private boolean cancelled;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "service created");
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "service destroyed");
        super.onDestroy();
    }

    @Override
    protected void onStartListening(Intent intent, Callback callback) {
        cb = callback;
        cancelled = false;
        handler.removeCallbacksAndMessages(null);

        String mode = prop("debug.stt.mode", "result");
        String text = prop("debug.stt.text", "现在几点");
        long delay = parseLong(prop("debug.stt.delay", "1500"), 1500);

        String lang = intent == null ? "?" : String.valueOf(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
        boolean partial = intent != null && intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        Log.i(TAG, "onStartListening mode=" + mode + " text=" + text + " delay=" + delay
                + " partial=" + partial + " lang=" + lang);

        if ("hang".equals(mode)) {
            // 什么都不回：用来测 App 侧的硬超时与取消
            Log.i(TAG, "hang: no callback at all");
            return;
        }

        schedule(150, () -> safe(() -> cb.beginningOfSpeech()));

        if ("nomatch".equals(mode)) {
            schedule(delay, () -> safe(() -> cb.error(SpeechRecognizer.ERROR_NO_MATCH)));
            return;
        }
        if ("speech_timeout".equals(mode)) {
            schedule(delay, () -> safe(() -> cb.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)));
            return;
        }
        if ("client".equals(mode)) {
            schedule(delay, () -> safe(() -> cb.error(SpeechRecognizer.ERROR_CLIENT)));
            return;
        }
        if ("busy".equals(mode)) {
            schedule(delay, () -> safe(() -> cb.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)));
            return;
        }

        long endAt = Math.max(300, delay - 400);
        if (partial) {
            schedule(endAt / 2, () -> safe(() -> cb.partialResults(bundleOf("现在"))));
        }
        schedule(endAt, () -> safe(() -> cb.endOfSpeech()));
        schedule(delay, () -> safe(() -> cb.results(bundleOf(text))));
    }

    @Override
    protected void onStopListening(Callback callback) {
        Log.i(TAG, "onStopListening (App 请求提前结束)");
    }

    @Override
    protected void onCancel(Callback callback) {
        cancelled = true;
        Log.i(TAG, "onCancel (App 取消，麦克风应释放)");
        handler.removeCallbacksAndMessages(null);
    }

    // ---- helpers ----

    private void schedule(long ms, Runnable r) {
        handler.postDelayed(() -> {
            if (cancelled) {
                Log.i(TAG, "skip callback after cancel");
                return;
            }
            r.run();
        }, ms);
    }

    private interface Thunk {
        void run() throws Exception;
    }

    private void safe(Thunk t) {
        try {
            t.run();
        } catch (Throwable e) {
            Log.w(TAG, "callback failed: " + e);
        }
    }

    private static Bundle bundleOf(String text) {
        Bundle b = new Bundle();
        ArrayList<String> list = new ArrayList<>();
        list.add(text);
        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, list);
        b.putFloat(SpeechRecognizer.CONFIDENCE_SCORES, 0.95f);
        return b;
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 读系统属性，adb shell setprop 即可改；不用反射 @hide API。 */
    private static String prop(String key, String def) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            if (line == null) return def;
            line = line.trim();
            return line.isEmpty() ? def : line;
        } catch (Exception e) {
            return def;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
