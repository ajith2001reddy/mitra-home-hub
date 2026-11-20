package com.mitrahome.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.EditText;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MITRA's trusted native AI boundary. The API key is entered in an Android
 * dialog, encrypted with Android Keystore, and never passed into WebView code.
 */
final class MitraBrainBridge {
  private static final String OPENAI_URL = "https://api.openai.com/v1/responses";
  private static final String KEY_ALIAS = "mitra_openai_api_key";
  private static final String KEY_VALUE = "openai_api_key";
  private static final String MODEL_VALUE = "model";
  private static final String WEB_SEARCH_VALUE = "web_search";
  private static final String SMART_HOME_VALUE = "smart_home";
  private static final int MAX_HISTORY_MESSAGES = 16;
  private static final int MAX_MESSAGE_LENGTH = 5000;
  private static final int MAX_TOOL_STEPS = 4;
  private static final int MAX_LOGS = 60;

  private final Activity activity;
  private final WebView webView;
  private final HomeAssistantBridge homeAssistant;
  private final SharedPreferences preferences;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private volatile PendingAction pendingAction;

  MitraBrainBridge(Activity activity, WebView webView, HomeAssistantBridge homeAssistant) {
    this.activity = activity;
    this.webView = webView;
    this.homeAssistant = homeAssistant;
    this.preferences = activity.getSharedPreferences("mitra_brain", Context.MODE_PRIVATE);
  }

  @JavascriptInterface
  public void getStatus() { sendStatus(); }

  @JavascriptInterface
  public void configure(String rawConfiguration) {
    try {
      JSONObject configuration = new JSONObject(rawConfiguration);
      String model = configuration.optString("model", currentModel());
      if (!model.matches("^[A-Za-z0-9._-]{1,80}$")) {
        sendError("Choose a valid OpenAI model name.");
        return;
      }
      preferences.edit()
        .putString(MODEL_VALUE, model)
        .putBoolean(WEB_SEARCH_VALUE, configuration.optBoolean("webSearch", false))
        .putBoolean(SMART_HOME_VALUE, configuration.optBoolean("smartHome", true))
        .apply();
      appendLog("configuration", "AI preferences updated", "success");
      sendStatus();
    } catch (Exception error) {
      sendError("MITRA could not save AI settings.");
    }
  }

  /** Opens a native password field. The WebView never sees the API key. */
  @JavascriptInterface
  public void configureApiKey() {
    activity.runOnUiThread(() -> {
      EditText input = new EditText(activity);
      input.setSingleLine(true);
      input.setHint("sk-…");
      input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
      input.setPadding(padding, 0, padding, 0);
      new AlertDialog.Builder(activity)
        .setTitle("Connect OpenAI to MITRA")
        .setMessage("Your key is encrypted in this Lenovo Duet’s Android Keystore. It is never stored in the dashboard or sent to Home Assistant.")
        .setView(input)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Save securely", (dialog, which) -> saveApiKey(input.getText().toString()))
        .show();
    });
  }

  @JavascriptInterface
  public void clearApiKey() {
    preferences.edit().remove(KEY_VALUE).apply();
    appendLog("configuration", "OpenAI key removed", "success");
    sendStatus();
  }

  @JavascriptInterface
  public void ask(String rawRequest) {
    if (rawRequest == null || rawRequest.length() > 50000) {
      sendError("That message is too large for MITRA to process safely.");
      return;
    }
    executor.execute(() -> runConversation(rawRequest));
  }

  @JavascriptInterface
  public void confirmPendingAction() {
    executor.execute(() -> {
      PendingAction pending = pendingAction;
      if (pending == null) {
        sendResponse("There is no sensitive action waiting for confirmation.", new JSONArray(), null);
        return;
      }
      pendingAction = null;
      JSONObject result = homeAssistant.assistantConfirmEntities(pending.entityIds, pending.turnOn);
      boolean verified = result.optBoolean("verified", false);
      appendLog("smart-home", pending.label + " " + (pending.turnOn ? "on" : "off"), verified ? "verified" : "not verified");
      String reply = verified
        ? "Confirmed. Home Assistant verified the requested state for " + pending.label + "."
        : "I sent the confirmed action, but Home Assistant did not verify the requested state. Please check the device.";
      sendResponse(reply, new JSONArray(), null);
    });
  }

  @JavascriptInterface
  public void cancelPendingAction() {
    PendingAction pending = pendingAction;
    pendingAction = null;
    if (pending != null) appendLog("smart-home", "Sensitive action cancelled", "cancelled");
    sendResponse("Cancelled. No Home Assistant action was sent.", new JSONArray(), null);
  }

  @JavascriptInterface
  public void getActivityLog() {
    send("mitraBrainActivityLog", preferences.getString("activity_log", "[]"));
  }

  @JavascriptInterface
  public void clearActivityLog() {
    preferences.edit().remove("activity_log").apply();
    send("mitraBrainActivityLog", "[]");
  }

  private void saveApiKey(String value) {
    String apiKey = value == null ? "" : value.trim();
    if (!apiKey.startsWith("sk-") || apiKey.length() < 20) {
      sendError("That does not look like an OpenAI API key.");
      return;
    }
    String encrypted = encrypt(apiKey);
    if (encrypted.isEmpty()) {
      sendError("MITRA could not secure the API key on this device.");
      return;
    }
    preferences.edit().putString(KEY_VALUE, encrypted).apply();
    appendLog("configuration", "OpenAI key stored securely", "success");
    sendStatus();
  }

  private void runConversation(String rawRequest) {
    String apiKey = loadApiKey();
    if (apiKey.isEmpty()) {
      sendError("Connect OpenAI in MITRA Settings before starting a conversation.");
      return;
    }
    try {
      JSONObject request = new JSONObject(rawRequest);
      String message = request.optString("message", "").trim();
      if (message.isEmpty() || message.length() > MAX_MESSAGE_LENGTH) {
        sendError("Enter a shorter message for MITRA.");
        return;
      }
      JSONArray tools = buildTools();
      JSONObject initialRequest = firstRequest(message, request.optJSONArray("history"), request.optJSONArray("memories"), tools);
      // We deliberately keep Responses API storage disabled. For tool calls, carry the
      // complete local transcript forward instead of referencing a stored response ID.
      JSONArray toolTranscript = initialRequest.getJSONArray("input");
      JSONObject response = createResponse(apiKey, initialRequest);
      for (int step = 0; step < MAX_TOOL_STEPS; step++) {
        JSONObject toolCall = firstFunctionCall(response.optJSONArray("output"));
        if (toolCall == null) {
          appendLog("conversation", "OpenAI response", "success");
          sendResponse(extractText(response), extractCitations(response), null);
          return;
        }
        JSONObject result = executeFunction(toolCall);
        if (result.optBoolean("confirmation_required", false)) {
          JSONArray entityIds = result.optJSONArray("entity_ids");
          pendingAction = new PendingAction(entityIds == null ? new JSONArray() : entityIds, "on".equals(result.optString("requested")), result.optString("label", "sensitive device"));
          appendLog("smart-home", "Confirmation requested for " + pendingAction.label, "waiting");
          JSONObject confirmation = new JSONObject().put("label", pendingAction.label).put("action", pendingAction.turnOn ? "turn on" : "turn off");
          sendResponse("I need your confirmation before I " + confirmation.optString("action") + " " + pendingAction.label + ".", new JSONArray(), confirmation);
          return;
        }
        appendResponseOutput(toolTranscript, response.optJSONArray("output"));
        toolTranscript.put(new JSONObject()
          .put("type", "function_call_output")
          .put("call_id", toolCall.optString("call_id", ""))
          .put("output", result.toString()));
        JSONObject continuation = responseRequest(toolTranscript, tools);
        response = createResponse(apiKey, continuation);
      }
      appendLog("conversation", "Tool limit reached", "error");
      sendError("MITRA stopped after too many tool steps. Please try a simpler request.");
    } catch (HttpError error) {
      appendLog("conversation", "OpenAI HTTP " + error.status, "error");
      if (error.status == 401) sendError("OpenAI rejected the configured key. Check it in MITRA Settings.");
      else if (error.status == 429) sendError("OpenAI is busy or the account has reached its limit. Please try again shortly.");
      else if (error.status >= 500) sendError("OpenAI is temporarily unavailable. Please try again shortly.");
      else sendError("OpenAI could not complete that request (HTTP " + error.status + ").");
    } catch (Exception error) {
      Log.e("MitraBrain", "MITRA conversation failed", error);
      appendLog("conversation", "OpenAI request failed", "error");
      sendError("MITRA could not reach OpenAI. Check the Duet internet connection and try again.");
    }
  }

  private JSONObject firstRequest(String message, JSONArray history, JSONArray memories, JSONArray tools) throws Exception {
    JSONArray input = new JSONArray().put(new JSONObject().put("role", "user").put("content", conversationInput(message, history, memories)));
    return responseRequest(input, tools);
  }

  private JSONObject responseRequest(JSONArray input, JSONArray tools) throws Exception {
    return new JSONObject()
      .put("model", currentModel())
      .put("store", false)
      .put("instructions", instructions())
      .put("input", input)
      .put("tools", tools)
      .put("parallel_tool_calls", false);
  }

  private void appendResponseOutput(JSONArray transcript, JSONArray output) {
    if (output == null) return;
    for (int index = 0; index < output.length(); index++) {
      Object item = output.opt(index);
      if (item != null) transcript.put(item);
    }
  }

  private String instructions() {
    StringBuilder text = new StringBuilder();
    text.append("You are MITRA, a friendly, calm, intelligent personal AI companion. ");
    text.append("Answer naturally and concisely unless asked for detail. Explain complex topics clearly. ");
    text.append("Do not claim a Home Assistant action happened unless a tool result says verified is true. ");
    text.append("Never invent devices, rooms, states, emails, or results. ");
    text.append("If a device name is ambiguous, use get_devices and ask a clarifying question. ");
    text.append("Use Home Assistant tools only for the user's real registered entities. ");
    text.append("Google Mail, calendar data, photos, and any other private integration are not available to you in this conversation. ");
    if (!smartHomeEnabled()) text.append("Smart-home tools are disabled in MITRA Settings, so do not attempt device control. ");
    if (webSearchEnabled()) text.append("Use web search when current information is needed; preserve source citations in your answer. ");
    return text.toString();
  }

  private String conversationInput(String message, JSONArray history, JSONArray memories) {
    StringBuilder input = new StringBuilder();
    if (memories != null && memories.length() > 0) {
      input.append("User-approved personal memory:\n");
      for (int index = 0; index < Math.min(memories.length(), 24); index++) {
        JSONObject memory = memories.optJSONObject(index);
        String text = memory == null ? "" : memory.optString("text", "").trim();
        if (!text.isEmpty()) input.append("- ").append(limit(text, 500)).append("\n");
      }
    }
    if (history != null && history.length() > 0) {
      input.append("Recent conversation:\n");
      int start = Math.max(0, history.length() - MAX_HISTORY_MESSAGES);
      for (int index = start; index < history.length(); index++) {
        JSONObject item = history.optJSONObject(index);
        if (item == null) continue;
        String role = "assistant".equals(item.optString("role")) ? "MITRA" : "User";
        String text = item.optString("text", "").trim();
        if (!text.isEmpty()) input.append(role).append(": ").append(limit(text, 1600)).append("\n");
      }
    }
    input.append("User: ").append(message);
    return input.toString();
  }

  private JSONArray buildTools() throws Exception {
    JSONArray tools = new JSONArray();
    if (smartHomeEnabled()) {
      tools.put(function("get_devices", "List registered, controllable Home Assistant devices and their actual current state.", emptySchema()));
      tools.put(function("get_device_state", "Read the actual current state of one registered Home Assistant entity.", entitySchema()));
      tools.put(function("turn_on_device", "Turn on one registered Home Assistant entity and verify its resulting state.", entitySchema()));
      tools.put(function("turn_off_device", "Turn off one registered Home Assistant entity and verify its resulting state.", entitySchema()));
      JSONObject roomProperties = new JSONObject().put("room", new JSONObject().put("type", "string").put("description", "Exact Home Assistant Area name."))
        .put("action", new JSONObject().put("type", "string").put("enum", new JSONArray().put("on").put("off")));
      JSONObject roomSchema = new JSONObject().put("type", "object").put("properties", roomProperties).put("required", new JSONArray().put("room").put("action")).put("additionalProperties", false);
      tools.put(function("control_room", "Control every registered controllable device in one exact Home Assistant Area, then verify the states.", roomSchema));
    }
    if (webSearchEnabled()) tools.put(new JSONObject().put("type", "web_search").put("search_context_size", "low"));
    return tools;
  }

  private JSONObject function(String name, String description, JSONObject parameters) throws Exception {
    return new JSONObject().put("type", "function").put("name", name).put("description", description).put("strict", true).put("parameters", parameters);
  }

  private JSONObject emptySchema() throws Exception {
    return new JSONObject().put("type", "object").put("properties", new JSONObject()).put("required", new JSONArray()).put("additionalProperties", false);
  }

  private JSONObject entitySchema() throws Exception {
    return new JSONObject().put("type", "object")
      .put("properties", new JSONObject().put("entity_id", new JSONObject().put("type", "string").put("description", "Exact entity_id returned by get_devices.")))
      .put("required", new JSONArray().put("entity_id")).put("additionalProperties", false);
  }

  private JSONObject executeFunction(JSONObject call) {
    String name = call.optString("name", "");
    try {
      JSONObject arguments = new JSONObject(call.optString("arguments", "{}"));
      JSONObject result;
      if ("get_devices".equals(name)) result = homeAssistant.assistantGetDevices();
      else if ("get_device_state".equals(name)) result = homeAssistant.assistantGetDeviceState(arguments.optString("entity_id", ""));
      else if ("turn_on_device".equals(name)) result = homeAssistant.assistantTurnDevice(arguments.optString("entity_id", ""), true, false);
      else if ("turn_off_device".equals(name)) result = homeAssistant.assistantTurnDevice(arguments.optString("entity_id", ""), false, false);
      else if ("control_room".equals(name)) {
        String action = arguments.optString("action", "");
        result = ("on".equals(action) || "off".equals(action))
          ? homeAssistant.assistantControlRoom(arguments.optString("room", ""), "on".equals(action), false)
          : new JSONObject().put("ok", false).put("error", "MITRA rejected an invalid room action.");
      }
      else result = new JSONObject().put("ok", false).put("error", "Unknown MITRA tool.");
      if (!result.optBoolean("confirmation_required", false)) {
        appendLog("smart-home", name, result.optBoolean("verified", result.optBoolean("ok", false)) ? "verified" : "not verified");
      }
      return result;
    } catch (Exception error) {
      appendLog("smart-home", name, "error");
      try { return new JSONObject().put("ok", false).put("error", "MITRA rejected malformed tool arguments."); }
      catch (Exception ignored) { return new JSONObject(); }
    }
  }

  private JSONObject firstFunctionCall(JSONArray output) {
    if (output == null) return null;
    for (int index = 0; index < output.length(); index++) {
      JSONObject item = output.optJSONObject(index);
      if (item != null && "function_call".equals(item.optString("type"))) return item;
    }
    return null;
  }

  private String extractText(JSONObject response) {
    StringBuilder text = new StringBuilder();
    JSONArray output = response.optJSONArray("output");
    if (output != null) {
      for (int itemIndex = 0; itemIndex < output.length(); itemIndex++) {
        JSONObject item = output.optJSONObject(itemIndex);
        if (item == null || !"message".equals(item.optString("type"))) continue;
        JSONArray content = item.optJSONArray("content");
        if (content == null) continue;
        for (int contentIndex = 0; contentIndex < content.length(); contentIndex++) {
          JSONObject part = content.optJSONObject(contentIndex);
          if (part != null && "output_text".equals(part.optString("type"))) text.append(part.optString("text", ""));
        }
      }
    }
    String responseText = text.toString().trim();
    return responseText.isEmpty() ? "I could not form a response for that request." : responseText;
  }

  private JSONArray extractCitations(JSONObject response) {
    JSONArray citations = new JSONArray();
    Set<String> seen = new HashSet<>();
    JSONArray output = response.optJSONArray("output");
    if (output == null) return citations;
    for (int itemIndex = 0; itemIndex < output.length(); itemIndex++) {
      JSONObject item = output.optJSONObject(itemIndex);
      JSONArray content = item == null ? null : item.optJSONArray("content");
      if (content == null) continue;
      for (int contentIndex = 0; contentIndex < content.length(); contentIndex++) {
        JSONObject part = content.optJSONObject(contentIndex);
        JSONArray annotations = part == null ? null : part.optJSONArray("annotations");
        if (annotations == null) continue;
        for (int annotationIndex = 0; annotationIndex < annotations.length(); annotationIndex++) {
          JSONObject annotation = annotations.optJSONObject(annotationIndex);
          JSONObject citation = annotation == null ? null : annotation.optJSONObject("url_citation");
          String url = citation == null ? "" : citation.optString("url", "");
          if (url.isEmpty() || !seen.add(url)) continue;
          try { citations.put(new JSONObject().put("url", url).put("title", citation.optString("title", url))); }
          catch (Exception ignored) { }
        }
      }
    }
    return citations;
  }

  private JSONObject createResponse(String apiKey, JSONObject body) throws Exception {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(OPENAI_URL).openConnection();
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(45000);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setRequestProperty("Authorization", "Bearer " + apiKey);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Accept", "application/json");
      try (OutputStream output = connection.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
      int status = connection.getResponseCode();
      String response = read(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
      if (status < 200 || status >= 300) throw new HttpError(status);
      return new JSONObject(response);
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private boolean webSearchEnabled() { return preferences.getBoolean(WEB_SEARCH_VALUE, false); }
  private boolean smartHomeEnabled() { return preferences.getBoolean(SMART_HOME_VALUE, true); }
  private String currentModel() { return preferences.getString(MODEL_VALUE, "gpt-5-mini"); }

  private String loadApiKey() {
    String encrypted = preferences.getString(KEY_VALUE, "");
    if (encrypted == null || encrypted.isEmpty()) return "";
    try {
      String[] parts = encrypted.split(":", 2);
      if (parts.length != 2) return "";
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
      return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    } catch (Exception error) {
      preferences.edit().remove(KEY_VALUE).apply();
      return "";
    }
  }

  private String encrypt(String value) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
      return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    } catch (Exception error) { return ""; }
  }

  private SecretKey getSecretKey() throws Exception {
    KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
    keyStore.load(null);
    if (keyStore.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
    return generator.generateKey();
  }

  private void appendLog(String type, String detail, String status) {
    try {
      JSONArray logs = new JSONArray(preferences.getString("activity_log", "[]"));
      JSONArray next = new JSONArray();
      next.put(new JSONObject().put("time", System.currentTimeMillis()).put("type", type).put("detail", detail).put("status", status));
      for (int index = 0; index < Math.min(logs.length(), MAX_LOGS - 1); index++) next.put(logs.get(index));
      preferences.edit().putString("activity_log", next.toString()).apply();
      send("mitraBrainActivityLog", next.toString());
    } catch (Exception error) {
      Log.w("MitraBrain", "Could not write activity log", error);
    }
  }

  private void sendStatus() {
    try {
      JSONObject status = new JSONObject().put("available", true).put("provider", "OpenAI").put("configured", !loadApiKey().isEmpty())
        .put("model", currentModel()).put("webSearch", webSearchEnabled()).put("smartHome", smartHomeEnabled()).put("chatScope", "lenovo-only");
      send("mitraBrainStatus", status.toString());
    } catch (Exception ignored) { }
  }

  private String read(InputStream input) throws IOException {
    if (input == null) return "";
    StringBuilder text = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) text.append(line);
    }
    return text.toString();
  }

  private String limit(String value, int maximum) { return value.length() <= maximum ? value : value.substring(0, maximum); }

  private void sendResponse(String text, JSONArray citations, JSONObject confirmation) {
    try {
      JSONObject payload = new JSONObject().put("text", text).put("citations", citations);
      if (confirmation != null) payload.put("confirmation", confirmation);
      send("mitraBrainResponse", payload.toString());
    } catch (Exception ignored) { }
  }

  private void sendError(String message) { send("mitraBrainError", message); }
  private void send(String callback, String value) { webView.post(() -> webView.evaluateJavascript("window." + callback + "(" + JSONObject.quote(value) + ");", null)); }

  void shutdown() { executor.shutdownNow(); }

  private static final class PendingAction {
    final JSONArray entityIds;
    final boolean turnOn;
    final String label;
    PendingAction(JSONArray entityIds, boolean turnOn, String label) { this.entityIds = entityIds; this.turnOn = turnOn; this.label = label; }
  }

  private static final class HttpError extends IOException {
    final int status;
    HttpError(int status) { this.status = status; }
  }
}
