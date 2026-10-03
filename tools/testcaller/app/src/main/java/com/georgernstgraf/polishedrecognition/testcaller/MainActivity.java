package com.georgernstgraf.polishedrecognition.testcaller;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Minimal {@link SpeechRecognizer} client that binds to Polished's
 * {@code RecognitionService} by <b>explicit component</b>.
 *
 * <p>Rationale: some callers (Duolingo on Android 11) ignore the system's
 * default voice-input setting and bind Google instead, so the delivery path
 * cannot be exercised through them. An explicit component bypasses that
 * routing and lets us verify that our service actually delivers the result.
 *
 * <p>See {@code tools/testcaller/README.md} for build / install / run and the
 * Android-11 {@code BIND_RECOGNITION_SERVICE} caveat.
 */
public class MainActivity extends Activity {

    private static final String TAG = "PolishedTestCaller";
    private static final String TARGET_PKG = "com.georgernstgraf.polishedrecognition";
    private static final String TARGET_SVC = TARGET_PKG + ".service.PolishedRecognitionService";
    /** Safety stop so the mic is never left recording indefinitely. */
    private static final long AUTO_STOP_MS = 30_000L;

    private SpeechRecognizer recognizer;
    private TextView statusView;
    private TextView resultView;
    private Button toggleButton;
    private boolean listening;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 180, 48, 48);

        TextView title = new TextView(this);
        title.setTextSize(20f);
        title.setText("Polished RecognitionService test caller");
        root.addView(title);

        TextView target = new TextView(this);
        target.setTextSize(12f);
        target.setText("Explicit component:\n" + TARGET_PKG
                + "\n.service.PolishedRecognitionService");
        root.addView(target);

        toggleButton = new Button(this);
        toggleButton.setText("Start listening");
        toggleButton.setOnClickListener(v -> {
            if (listening) stopRecognition();
            else startRecognition();
        });
        root.addView(toggleButton);

        statusView = new TextView(this);
        statusView.setTextSize(18f);
        statusView.setPadding(0, 24, 0, 0);
        statusView.setText("idle");
        root.addView(statusView);

        resultView = new TextView(this);
        resultView.setTextSize(18f);
        resultView.setPadding(0, 24, 0, 0);
        resultView.setText("(no result yet)");
        root.addView(resultView);

        setContentView(root);
        Log.i(TAG, "onCreate; isRecognitionAvailable="
                + SpeechRecognizer.isRecognitionAvailable(this));
    }

    private void startRecognition() {
        if (listening) return;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(
                    this, new ComponentName(TARGET_PKG, TARGET_SVC));
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { status("ready — speak now"); }
                @Override public void onBeginningOfSpeech() { status("listening…"); }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { status("processing…"); }
                @Override public void onError(int error) {
                    status("ERROR " + error);
                    Log.i(TAG, "onError " + error);
                    finishRun();
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> list = (results == null) ? null
                            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = (list == null) ? "(null)" : list.toString();
                    result("RESULT: " + text);
                    Log.i(TAG, "onResults " + text);
                    finishRun();
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> list = (partialResults == null) ? null
                            : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) status("partial: " + list.get(0));
                }
                @Override public void onEvent(int eventType, Bundle params) { }
            });

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
            recognizer.startListening(intent);

            listening = true;
            toggleButton.setText("Stop & transcribe");
            status("startListening sent…");
            handler.postDelayed(this::stopRecognition, AUTO_STOP_MS);
        } catch (Throwable t) {
            status("EXCEPTION: " + t);
            Log.e(TAG, "startRecognition failed", t);
            finishRun();
        }
    }

    private void stopRecognition() {
        if (!listening) return;
        try {
            if (recognizer != null) recognizer.stopListening();
        } catch (Throwable t) {
            status("stop EXCEPTION: " + t);
            finishRun();
        }
    }

    private void finishRun() {
        handler.removeCallbacksAndMessages(null);
        listening = false;
        if (toggleButton != null) toggleButton.setText("Start listening");
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        super.onDestroy();
    }

    private void status(final String text) {
        runOnUiThread(() -> statusView.setText(text));
        Log.i(TAG, text);
    }

    private void result(final String text) {
        runOnUiThread(() -> resultView.setText(text));
        Log.i(TAG, text);
    }
}
