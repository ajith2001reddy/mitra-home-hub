package com.mitrahome.hub;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONObject;

/** Lenovo-native, user-initiated speech input and spoken MITRA replies. */
final class VoiceBridge {
  private final MainActivity activity;
  private final WebView webView;
  private SpeechRecognizer recognizer;
  private TextToSpeech textToSpeech;
  private boolean textToSpeechReady;
  private boolean listening;

  VoiceBridge(MainActivity activity, WebView webView) {
    this.activity = activity;
    this.webView = webView;
    activity.runOnUiThread(this::initializeTextToSpeech);
  }

  @JavascriptInterface
  public void getStatus() { sendState("idle", "Tap the microphone and speak to MITRA."); }

  @JavascriptInterface
  public void startListening() { activity.runOnUiThread(() -> activity.requestVoiceMicrophonePermission()); }

  @JavascriptInterface
  public void stopListening() {
    activity.runOnUiThread(() -> {
      if (recognizer != null && listening) recognizer.stopListening();
    });
  }

  @JavascriptInterface
  public void speak(String text) { activity.runOnUiThread(() -> speakReply(text)); }

  @JavascriptInterface
  public void stopSpeaking() {
    activity.runOnUiThread(() -> {
      if (textToSpeech != null) textToSpeech.stop();
      sendState("idle", "Voice reply stopped.");
    });
  }

  void beginListening() {
    if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
      sendError("Speech recognition is not available on this Lenovo Duet.");
      return;
    }
    try {
      ensureRecognizer();
      if (listening) recognizer.cancel();
      Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask MITRA anything");
      listening = true;
      sendState("listening", "Listening…");
      recognizer.startListening(intent);
    } catch (Exception error) {
      listening = false;
      sendError("MITRA could not start the microphone.");
    }
  }

  void onMicrophonePermissionResult(boolean granted) {
    if (granted) beginListening();
    else sendError("Microphone access is needed for voice commands. You can enable it in Android app permissions.");
  }

  private void ensureRecognizer() {
    if (recognizer != null) return;
    recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
    recognizer.setRecognitionListener(new RecognitionListener() {
      @Override public void onReadyForSpeech(Bundle params) { sendState("listening", "Listening…"); }
      @Override public void onBeginningOfSpeech() { sendState("listening", "I can hear you…"); }
      @Override public void onRmsChanged(float rmsdB) { }
      @Override public void onBufferReceived(byte[] buffer) { }
      @Override public void onEndOfSpeech() { sendState("processing", "Thinking…"); }
      @Override public void onError(int error) {
        listening = false;
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
          sendState("idle", "I did not catch that. Tap the microphone and try again.");
        } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
          sendError("Microphone permission was not granted to MITRA.");
        } else {
          sendError("Voice recognition could not finish. Please check the Duet connection and try again.");
        }
      }
      @Override public void onResults(Bundle results) {
        listening = false;
        ArrayList<String> phrases = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String transcript = phrases == null || phrases.isEmpty() ? "" : phrases.get(0).trim();
        if (transcript.isEmpty()) {
          sendState("idle", "I did not catch that. Tap the microphone and try again.");
          return;
        }
        sendState("processing", "MITRA is thinking…");
        send("mitraVoiceTranscript", transcript);
      }
      @Override public void onPartialResults(Bundle partialResults) {
        ArrayList<String> phrases = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (phrases != null && !phrases.isEmpty()) sendState("listening", phrases.get(0));
      }
      @Override public void onEvent(int eventType, Bundle params) { }
    });
  }

  private void initializeTextToSpeech() {
    textToSpeech = new TextToSpeech(activity, status -> {
      textToSpeechReady = status == TextToSpeech.SUCCESS;
      if (textToSpeechReady) {
        textToSpeech.setLanguage(Locale.getDefault());
        textToSpeech.setSpeechRate(1.0f);
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
          @Override public void onStart(String utteranceId) { sendState("speaking", "MITRA is speaking…"); }
          @Override public void onDone(String utteranceId) { sendState("idle", "Ready for your next question."); }
          @Override public void onError(String utteranceId) { sendError("MITRA could not speak that response."); }
        });
      }
    });
  }

  private void speakReply(String text) {
    if (!textToSpeechReady || textToSpeech == null) {
      sendError("Text-to-speech is not ready on this Duet.");
      return;
    }
    String cleaned = text == null ? "" : text.replaceAll("https?://\\S+", "").replaceAll("\\[[^\\]]+\\]\\([^)]*\\)", "$1").trim();
    if (cleaned.isEmpty()) return;
    textToSpeech.speak(cleaned.length() > 3800 ? cleaned.substring(0, 3800) : cleaned, TextToSpeech.QUEUE_FLUSH, null, "mitra-reply");
  }

  private void sendState(String state, String message) {
    try { send("mitraVoiceState", new JSONObject().put("state", state).put("message", message).toString()); }
    catch (Exception ignored) { }
  }


  private void sendError(String message) { send("mitraVoiceError", message); }
  private void send(String callback, String value) { webView.post(() -> webView.evaluateJavascript("window." + callback + "(" + JSONObject.quote(value) + ");", null)); }

  void shutdown() {
    activity.runOnUiThread(() -> {
      if (recognizer != null) { recognizer.destroy(); recognizer = null; }
      if (textToSpeech != null) { textToSpeech.stop(); textToSpeech.shutdown(); textToSpeech = null; }
    });
  }
}
