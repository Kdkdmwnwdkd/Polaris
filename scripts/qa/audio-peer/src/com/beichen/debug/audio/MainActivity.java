package com.beichen.debug.audio;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/**
 * 「另一个应用」的替身，两个模式：
 *
 *   music  —— 以 USAGE_MEDIA 持焦点并持续放一段正弦波；收到
 *             LOSS_TRANSIENT_CAN_DUCK 就把自己的音量降到 20%，收到 GAIN 恢复。
 *             用来验 P12-6：桌宠朗读时音乐是否真的被压低、说完是否恢复。
 *
 *   record —— 持续开麦克风录音并打印 RMS；用来验 P12-7：
 *             别的应用占着麦克风时，桌宠是否给出「被占用」而不是「识别失败」。
 *
 * 两个模式都把关键事件打到 logcat 的 DuckDemo tag，便于取证。
 * 用法：
 *   adb shell am start -n com.beichen.debug.audio/.MainActivity --es mode music
 *   adb shell am start -n com.beichen.debug.audio/.MainActivity --es mode record
 */
public class MainActivity extends Activity {

    private static final String TAG = "DuckDemo";
    private static final int SR = 44100;

    private AudioTrack track;
    private AudioRecord recorder;
    private AudioManager am;
    private AudioFocusRequest focusRequest;
    private volatile boolean running = true;
    private volatile boolean ducked = false;
    private TextView label;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        label = new TextView(this);
        label.setTextSize(22);
        label.setPadding(40, 120, 40, 40);
        setContentView(label);

        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        String mode = getIntent() == null ? "music" : getIntent().getStringExtra("mode");
        if (mode == null) mode = "music";
        label.setText("AudioDemo: " + mode);
        Log.i(TAG, "启动 mode=" + mode);

        if ("record".equals(mode)) {
            startRecording();
        } else {
            startMusic();
        }
    }

    // ---------- music ----------

    private void startMusic() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(change -> {
                    // 关键日志：duck 请求到没到、恢复有没有来
                    Log.i(TAG, "focus change=" + name(change)
                            + " (CAN_DUCK=" + AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK + ")");
                    if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        ducked = true;
                        runCatching(() -> track.setVolume(0.2f));
                        Log.i(TAG, "已压低到 0.2");
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN
                            || change == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) {
                        if (ducked) {
                            ducked = false;
                            runCatching(() -> track.setVolume(1.0f));
                            Log.i(TAG, "已恢复到 1.0");
                        }
                    } else if (change == AudioManager.AUDIOFOCUS_LOSS
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        Log.i(TAG, "焦点被拿走，暂停播放");
                    }
                })
                .build();

        int r = am.requestAudioFocus(focusRequest);
        Log.i(TAG, "requestAudioFocus -> " + r + " (GRANTED=" + AudioManager.AUDIOFOCUS_REQUEST_GRANTED + ")");

        int buf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(buf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        Log.i(TAG, "AudioTrack 开始播放 buf=" + buf + " state=" + track.getState());

        short[] pcm = new short[1024];
        new Thread(() -> {
            double phase = 0;
            while (running) {
                for (int i = 0; i < pcm.length; i++) {
                    // 440Hz 正弦，幅度随 duck 状态变化（听得出，也看得出）
                    pcm[i] = (short) (Math.sin(phase) * 8000);
                    phase += 2 * Math.PI * 440 / SR;
                    if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                }
                track.write(pcm, 0, pcm.length);
            }
        }, "music-writer").start();
    }

    // ---------- record ----------

    private void startRecording() {
        int buf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf * 4);
        Log.i(TAG, "AudioRecord state=" + recorder.getState()
                + " (RECORDSTATE_RECORDING=" + AudioRecord.RECORDSTATE_RECORDING + ")");
        recorder.startRecording();
        Log.i(TAG, "startRecording -> recordingState=" + recorder.getRecordingState());
        label.setText("AudioDemo: recording");

        short[] pcm = new short[1600];   // 100ms
        new Thread(() -> {
            long last = 0;
            while (running) {
                int n = recorder.read(pcm, 0, pcm.length);
                long now = System.currentTimeMillis();
                if (now - last > 2000) {
                    last = now;
                    long sum = 0;
                    for (int i = 0; i < Math.max(n, 0); i++) sum += (long) pcm[i] * pcm[i];
                    long rms = n > 0 ? (long) Math.sqrt((double) sum / n) : -1;
                    // n<=0 说明麦克风被拒绝/被抢；RMS=0 说明通道开着但没声音
                    Log.i(TAG, "read n=" + n + " rms=" + rms + " state=" + recorder.getRecordingState());
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }, "record-reader").start();
    }

    private static String name(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN: return "GAIN";
            case AudioManager.AUDIOFOCUS_LOSS: return "LOSS";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: return "LOSS_TRANSIENT";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: return "LOSS_TRANSIENT_CAN_DUCK";
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT: return "GAIN_TRANSIENT";
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK: return "GAIN_TRANSIENT_MAY_DUCK";
            default: return "?" + change;
        }
    }

    private static void runCatching(Runnable r) {
        try { r.run(); } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (track != null) {
            runCatching(() -> { track.stop(); track.release(); });
        }
        if (recorder != null) {
            runCatching(() -> { recorder.stop(); recorder.release(); });
        }
        if (am != null && focusRequest != null) {
            runCatching(() -> am.abandonAudioFocusRequest(focusRequest));
        }
        super.onDestroy();
    }
}
