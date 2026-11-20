package com.mitrahome.hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
 * Native Home Assistant boundary. The dashboard never receives the hub token.
 * MITRA Brain uses this same boundary, so every tool call is validated locally
 * and verified by reading Home Assistant state again after the action.
 */
final class HomeAssistantBridge {
  private static final String TOKEN_KEY_ALIAS = "jarvis_home_assistant_token";
  private static final String TOKEN_VALUE_KEY = "home_assistant_token";
  private static final Set<String> ASSISTANT_DOMAINS = new HashSet<>();
  private static final Set<String> SENSITIVE_DOMAINS = new HashSet<>();

  static {
    ASSISTANT_DOMAINS.add("light");
    ASSISTANT_DOMAINS.add("switch");
    ASSISTANT_DOMAINS.add("fan");
    ASSISTANT_DOMAINS.add("input_boolean");
    ASSISTANT_DOMAINS.add("media_player");
    ASSISTANT_DOMAINS.add("cover");
    ASSISTANT_DOMAINS.add("lock");
    SENSITIVE_DOMAINS.add("cover");
    SENSITIVE_DOMAINS.add("lock");
  }

  private final WebView webView;
  private final SharedPreferences preferences;
  private final ExecutorService stateExecutor = Executors.newSingleThreadExecutor();
  private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
  private volatile String baseUrl = "";
  private volatile String token = "";

  HomeAssistantBridge(Context context, WebView webView) {
    this.webView = webView;
    this.preferences = context.getSharedPreferences("jarvis_home", Context.MODE_PRIVATE);
    this.baseUrl = preferences.getString("home_assistant_url", "");
    this.token = loadToken();
  }

  @JavascriptInterface
  public String getLastUrl() { return baseUrl; }

  @JavascriptInterface
  public void restoreConnection() {
    if (baseUrl.isEmpty() || token.isEmpty()) return;
    send("jarvisNativeConnection", "connecting");
    fetchStates();
  }

  @JavascriptInterface
  public void connect(String serverUrl, String accessToken) {
    baseUrl = serverUrl.replaceAll("/+$", "");
    token = accessToken;
    preferences.edit().putString("home_assistant_url", baseUrl).putString(TOKEN_VALUE_KEY, encrypt(accessToken)).apply();
    send("jarvisNativeConnection", "connecting");
    fetchStates();
  }

  @JavascriptInterface
  public void fetchStates() {
    stateExecutor.execute(() -> requestForDashboard("GET", "/api/states", null, true));
  }

  @JavascriptInterface
  public void callService(String domain, String service, String entityIdsJson) {
    controlExecutor.execute(() -> {
      try {
        JSONObject payload = new JSONObject().put("entity_id", new JSONArray(entityIdsJson));
        requestForDashboard("POST", "/api/services/" + domain + "/" + service, payload.toString(), false);
      } catch (Exception error) {
        sendError("Could not prepare the Home Assistant action.");
      }
    });
  }

  /** MITRA Brain entry point: only current, registered controllable entities are returned. */
  JSONObject assistantGetDevices() {
    try {
      JSONArray states = refreshAssistantStates();
      JSONArray devices = new JSONArray();
      for (int index = 0; index < states.length(); index++) {
        JSONObject entity = states.optJSONObject(index);
        if (entity != null && isAllowedEntity(entity)) devices.put(toAssistantDevice(entity));
      }
      return new JSONObject().put("ok", true).put("devices", devices);
    } catch (Exception error) {
      return assistantError("home_assistant_unavailable", "MITRA could not read Home Assistant right now.");
    }
  }

  JSONObject assistantGetDeviceState(String entityId) {
    try {
      JSONObject entity = findAllowedEntity(refreshAssistantStates(), entityId);
      if (entity == null) return assistantError("unknown_device", "That device is not a registered controllable Home Assistant entity.");
      return new JSONObject().put("ok", true).put("device", toAssistantDevice(entity));
    } catch (Exception error) {
      return assistantError("home_assistant_unavailable", "MITRA could not read Home Assistant right now.");
    }
  }

  JSONObject assistantTurnDevice(String entityId, boolean turnOn, boolean confirmed) {
    try {
      JSONObject entity = findAllowedEntity(refreshAssistantStates(), entityId);
      if (entity == null) return assistantError("unknown_device", "That device is not a registered controllable Home Assistant entity.");
      if (isSensitive(entity) && !confirmed) return confirmationRequired(new JSONArray().put(entityId), turnOn, deviceName(entity));
      return runVerifiedControl(new JSONArray().put(entityId), turnOn);
    } catch (Exception error) {
      return assistantError("home_assistant_unavailable", "MITRA could not control Home Assistant right now.");
    }
  }

  JSONObject assistantControlRoom(String room, boolean turnOn, boolean confirmed) {
    try {
      String normalizedRoom = normalize(room);
      if (normalizedRoom.isEmpty()) return assistantError("invalid_room", "A Home Assistant Area name is required.");
      JSONArray states = refreshAssistantStates();
      JSONArray targets = new JSONArray();
      boolean sensitive = false;
      for (int index = 0; index < states.length(); index++) {
        JSONObject entity = states.optJSONObject(index);
        if (entity == null || !isAllowedEntity(entity) || !normalizedRoom.equals(normalize(roomFor(entity)))) continue;
        targets.put(entity.optString("entity_id", ""));
        sensitive = sensitive || isSensitive(entity);
      }
      if (targets.length() == 0) return assistantError("room_not_found", "No controllable Home Assistant entities have the Area '" + room + "'.");
      if (sensitive && !confirmed) return confirmationRequired(targets, turnOn, room);
      return runVerifiedControl(targets, turnOn);
    } catch (Exception error) {
      return assistantError("home_assistant_unavailable", "MITRA could not control Home Assistant right now.");
    }
  }

  /** Used only after the person confirms a pending sensitive action in MITRA Brain. */
  JSONObject assistantConfirmEntities(JSONArray entityIds, boolean turnOn) {
    try {
      if (entityIds == null || entityIds.length() == 0) return assistantError("no_pending_action", "There is no sensitive Home Assistant action to confirm.");
      return runVerifiedControl(entityIds, turnOn);
    } catch (Exception error) {
      return assistantError("home_assistant_unavailable", "MITRA could not control Home Assistant right now.");
    }
  }

  private JSONObject runVerifiedControl(JSONArray targetIds, boolean turnOn) throws Exception {
    JSONArray before = refreshAssistantStates();
    Map<String, JSONArray> groups = new HashMap<>();
    for (int index = 0; index < targetIds.length(); index++) {
      String entityId = targetIds.optString(index, "");
      JSONObject entity = findAllowedEntity(before, entityId);
      if (entity == null) return assistantError("unknown_device", "A requested device is no longer registered in Home Assistant.");
      String domain = domainOf(entityId);
      JSONArray group = groups.get(domain);
      if (group == null) { group = new JSONArray(); groups.put(domain, group); }
      group.put(entityId);
    }
    for (Map.Entry<String, JSONArray> entry : groups.entrySet()) {
      JSONObject payload = new JSONObject().put("entity_id", entry.getValue());
      requestApi("POST", "/api/services/" + entry.getKey() + "/" + serviceFor(entry.getKey(), turnOn), payload.toString());
    }
    // Matter and cloud-connected devices can update shortly after the service response.
    Thread.sleep(450);
    JSONArray after = refreshAssistantStates();
    // Keep the existing real-time dashboard in sync without waiting for its next poll.
    send("jarvisNativeStates", after.toString());
    JSONArray states = new JSONArray();
    boolean verified = true;
    for (int index = 0; index < targetIds.length(); index++) {
      String entityId = targetIds.optString(index, "");
      JSONObject entity = findAllowedEntity(after, entityId);
      if (entity == null) { verified = false; continue; }
      JSONObject device = toAssistantDevice(entity);
      boolean matches = matchesRequestedState(entity, turnOn);
      device.put("verified", matches);
      states.put(device);
      verified = verified && matches;
    }
    JSONObject result = new JSONObject().put("ok", verified).put("requested", turnOn ? "on" : "off").put("verified", verified).put("devices", states);
    if (!verified) result.put("error", "Home Assistant accepted the command but the requested state was not confirmed.");
    return result;
  }

  private JSONObject confirmationRequired(JSONArray entityIds, boolean turnOn, String label) {
    try {
      return new JSONObject().put("ok", false).put("confirmation_required", true).put("entity_ids", entityIds)
        .put("requested", turnOn ? "on" : "off").put("label", label == null || label.isEmpty() ? "sensitive Home Assistant device" : label);
    } catch (Exception error) {
      return assistantError("confirmation_error", "MITRA could not prepare the confirmation.");
    }
  }

  private JSONArray refreshAssistantStates() throws Exception {
    ApiResponse response = requestApi("GET", "/api/states", null);
    return new JSONArray(response.body);
  }

  private JSONObject findAllowedEntity(JSONArray states, String entityId) {
    if (entityId == null || !entityId.matches("^[a-z_]+\\.[a-zA-Z0-9_]+$")) return null;
    for (int index = 0; index < states.length(); index++) {
      JSONObject entity = states.optJSONObject(index);
      if (entity != null && entityId.equals(entity.optString("entity_id", "")) && isAllowedEntity(entity)) return entity;
    }
    return null;
  }

  private boolean isAllowedEntity(JSONObject entity) {
    String entityId = entity.optString("entity_id", "");
    String state = entity.optString("state", "");
    return ASSISTANT_DOMAINS.contains(domainOf(entityId)) && !"unavailable".equals(state) && !"unknown".equals(state);
  }

  private boolean isSensitive(JSONObject entity) { return SENSITIVE_DOMAINS.contains(domainOf(entity.optString("entity_id", ""))); }

  private JSONObject toAssistantDevice(JSONObject entity) throws Exception {
    JSONObject attributes = entity.optJSONObject("attributes");
    String entityId = entity.optString("entity_id", "");
    return new JSONObject().put("entity_id", entityId).put("name", deviceName(entity)).put("domain", domainOf(entityId))
      .put("state", entity.optString("state", "unknown")).put("room", roomFor(entity)).put("sensitive", isSensitive(entity));
  }

  private String deviceName(JSONObject entity) {
    JSONObject attributes = entity.optJSONObject("attributes");
    return attributes == null ? entity.optString("entity_id", "") : attributes.optString("friendly_name", entity.optString("entity_id", ""));
  }

  private String roomFor(JSONObject entity) {
    JSONObject attributes = entity.optJSONObject("attributes");
    if (attributes == null) return "";
    String area = attributes.optString("area_name", "").trim();
    return area.isEmpty() ? attributes.optString("room", "").trim() : area;
  }

  private String serviceFor(String domain, boolean turnOn) {
    if ("lock".equals(domain)) return turnOn ? "lock" : "unlock";
    if ("cover".equals(domain)) return turnOn ? "open_cover" : "close_cover";
    return turnOn ? "turn_on" : "turn_off";
  }

  private boolean matchesRequestedState(JSONObject entity, boolean turnOn) {
    String domain = domainOf(entity.optString("entity_id", ""));
    String state = entity.optString("state", "");
    if ("lock".equals(domain)) return turnOn ? "locked".equals(state) : "unlocked".equals(state);
    if ("cover".equals(domain)) return turnOn ? "open".equals(state) || "opening".equals(state) : "closed".equals(state) || "closing".equals(state);
    return turnOn ? !"off".equals(state) : "off".equals(state);
  }

  private String domainOf(String entityId) {
    int separator = entityId.indexOf('.');
    return separator > 0 ? entityId.substring(0, separator) : "";
  }

  private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", " "); }

  private JSONObject assistantError(String code, String message) {
    try { return new JSONObject().put("ok", false).put("error_code", code).put("error", message); }
    catch (Exception ignored) { return new JSONObject(); }
  }

  private void requestForDashboard(String method, String path, String body, boolean isStateRequest) {
    try {
      ApiResponse response = requestApi(method, path, body);
      if (isStateRequest) {
        send("jarvisNativeStates", response.body);
        send("jarvisNativeConnection", "connected");
      } else {
        fetchStates();
      }
    } catch (HttpError error) {
      sendError("Home Assistant rejected the connection (HTTP " + error.status + ").");
    } catch (Exception error) {
      sendError("Could not reach Home Assistant. Check the URL and network.");
    }
  }

  private ApiResponse requestApi(String method, String path, String body) throws Exception {
    if (baseUrl.isEmpty() || token.isEmpty()) throw new IOException("Home Assistant is not configured");
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
      connection.setConnectTimeout(8000);
      connection.setReadTimeout(10000);
      connection.setRequestMethod(method);
      connection.setRequestProperty("Authorization", "Bearer " + token);
      connection.setRequestProperty("Accept", "application/json");
      if (body != null) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
      }
      int status = connection.getResponseCode();
      String response = read(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
      if (status < 200 || status >= 300) throw new HttpError(status, response);
      return new ApiResponse(status, response);
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private String loadToken() {
    String encrypted = preferences.getString(TOKEN_VALUE_KEY, "");
    if (encrypted == null || encrypted.isEmpty()) return "";
    try {
      String[] parts = encrypted.split(":", 2);
      if (parts.length != 2) return "";
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
      return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    } catch (Exception ignored) {
      preferences.edit().remove(TOKEN_VALUE_KEY).apply();
      return "";
    }
  }

  private String encrypt(String value) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
      return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    } catch (Exception ignored) { return ""; }
  }

  private SecretKey getSecretKey() throws Exception {
    KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
    keyStore.load(null);
    if (keyStore.containsAlias(TOKEN_KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) keyStore.getEntry(TOKEN_KEY_ALIAS, null)).getSecretKey();
    KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    keyGenerator.init(new KeyGenParameterSpec.Builder(TOKEN_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
    return keyGenerator.generateKey();
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

  private void sendError(String message) { send("jarvisNativeError", message); }

  private void send(String callback, String value) {
    webView.post(() -> webView.evaluateJavascript("window." + callback + "(" + JSONObject.quote(value) + ");", null));
  }

  void shutdown() { stateExecutor.shutdownNow(); controlExecutor.shutdownNow(); }

  private static final class ApiResponse {
    final int status;
    final String body;
    ApiResponse(int status, String body) { this.status = status; this.body = body; }
  }

  private static final class HttpError extends IOException {
    final int status;
    HttpError(int status, String body) { super(body); this.status = status; }
  }
}
