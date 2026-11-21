package com.gathin.tvhome;

import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * System speech recognizer backed by Home Assistant's STT (faster-whisper). Apps that call SpeechRecognizer
 * (search boxes with a mic icon) get Whisper's transcript instead of Google's.
 */
public final class WhisperRecognitionService extends RecognitionService {
    private HaPipeline run;

    @Override
    protected void onStartListening(Intent intent, Callback cb) {
        if (run != null) run.cancel();
        run = new HaPipeline(HaPipeline.Settings.load(this), true, new HaPipeline.Events() {
            @Override public void onListening() {
                try { cb.readyForSpeech(new Bundle()); cb.beginningOfSpeech(); } catch (RemoteException ignored) { }
            }
            @Override public void onLevel(float level) {
                try { cb.rmsChanged(level * 10f); } catch (RemoteException ignored) { }
            }
            @Override public void onThinking() { }
            @Override public void onHeard(String text) {
                try {
                    cb.endOfSpeech();
                    if (text.isEmpty()) { cb.error(SpeechRecognizer.ERROR_NO_MATCH); return; }
                    Bundle b = new Bundle();
                    ArrayList<String> r = new ArrayList<>();
                    r.add(text);
                    b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, r);
                    b.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, new float[]{1f});
                    cb.results(b);
                } catch (RemoteException ignored) { }
            }
            @Override public void onReply(String text) { }
            @Override public void onSpeech(String url) { }
            @Override public void onError(String message) {
                Log.w("GoatVoice", "recognizer: " + message);
                try {
                    cb.error(message.startsWith("Didn't") ? SpeechRecognizer.ERROR_NO_MATCH : SpeechRecognizer.ERROR_NETWORK);
                } catch (RemoteException ignored) { }
            }
            @Override public void onDone() { }
        });
        run.start();
    }

    @Override
    protected void onStopListening(Callback cb) {
        if (run != null) run.endSpeech();
    }

    @Override
    protected void onCancel(Callback cb) {
        if (run != null) run.cancel();
        run = null;
    }
}
