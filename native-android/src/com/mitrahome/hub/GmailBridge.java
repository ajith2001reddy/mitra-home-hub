package com.mitrahome.hub;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Direct, client-side Gmail access. Tokens remain in Google Play services memory;
 * no token or message data is passed to Home Assistant or stored by this bridge.
 */
final class GmailBridge {
  static final int AUTHORIZATION_REQUEST_CODE = 73;
  private static final String GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly";
  private static final int MAX_MESSAGES = 8;
  private final Activity activity;
  private final WebView webView;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private volatile String accessToken = "";
  private volatile String email = "";
  private volatile String profileName = "";

  GmailBridge(Activity activity, WebView webView) {
    this.activity = activity;
    this.webView = webView;
  }

  @JavascriptInterface
  public void connect() {
    activity.runOnUiThread(() -> {
      sendConnection("authorizing", "Opening Google’s secure permission screen.");
      AuthorizationRequest request = AuthorizationRequest.builder()
        .setRequestedScopes(Arrays.asList(new Scope(GMAIL_READONLY), new Scope("email"), new Scope("profile")))
        .build();
      Identity.getAuthorizationClient(activity).authorize(request)
        .addOnSuccessListener(result -> {
          if (result.hasResolution()) {
            try {
              activity.startIntentSenderForResult(result.getPendingIntent().getIntentSender(), AUTHORIZATION_REQUEST_CODE, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException error) {
              sendError("Could not open Google sign-in.");
            }
            return;
          }
          acceptAuthorization(result);
        })
        .addOnFailureListener(error -> sendError("Google sign-in could not start. Make sure Google Play services are available."));
    });
  }

  @JavascriptInterface
  public void refresh() {
    if (accessToken.isEmpty()) {
      sendConnection("setup", "Connect Google to read new mail privately in MITRA.");
      return;
    }
    executor.execute(this::fetchInbox);
  }

  @JavascriptInterface
  public void disconnect() {
    String tokenToRevoke = accessToken;
    accessToken = "";
    email = "";
    profileName = "";
    executor.execute(() -> {
      if (!tokenToRevoke.isEmpty()) revokeToken(tokenToRevoke);
      sendConnection("setup", "Google mail access was removed from MITRA.");
    });
  }

  boolean handleActivityResult(int requestCode, Intent data) {
    if (requestCode != AUTHORIZATION_REQUEST_CODE) return false;
    try {
      AuthorizationResult result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data);
      acceptAuthorization(result);
    } catch (ApiException error) {
      sendError("Google permission was not completed.");
    }
    return true;
  }

  private void acceptAuthorization(AuthorizationResult result) {
    String token = result.getAccessToken();
    if (token == null || token.isEmpty()) {
      sendError("Google did not return a mail access token.");
      return;
    }
    accessToken = token;
    GoogleSignInAccount account = result.toGoogleSignInAccount();
    if (account != null && account.getDisplayName() != null) profileName = account.getDisplayName();
    sendConnection("connected", "Google mail connected directly to MITRA.");
    executor.execute(this::fetchInbox);
  }

  private void fetchInbox() {
    String token = accessToken;
    if (token.isEmpty()) return;
    try {
      JSONObject profile = requestJson("https://gmail.googleapis.com/gmail/v1/users/me/profile", token);
      email = profile.optString("emailAddress", "");
      JSONObject listed = requestJson("https://gmail.googleapis.com/gmail/v1/users/me/messages?q=is%3Aunread%20in%3Ainbox&maxResults=" + MAX_MESSAGES, token);
      JSONArray rawMessages = listed.optJSONArray("messages");
      JSONArray messages = new JSONArray();
      if (rawMessages != null) {
        for (int index = 0; index < rawMessages.length(); index++) {
          String id = rawMessages.getJSONObject(index).optString("id", "");
          if (id.isEmpty()) continue;
          JSONObject message = requestJson("https://gmail.googleapis.com/gmail/v1/users/me/messages/" + id + "?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date", token);
          messages.put(toBrief(message));
        }
      }
      JSONObject payload = new JSONObject();
      payload.put("email", email);
      payload.put("name", profileName);
      payload.put("messages", messages);
      send("mitraGoogleMail", payload.toString());
      sendConnection("connected", messages.length() + " unread Inbox message" + (messages.length() == 1 ? "" : "s") + " checked privately by MITRA.");
    } catch (HttpError error) {
      if (error.status == 401) {
        accessToken = "";
        sendConnection("expired", "Google access expired. Connect again to continue checking mail.");
      } else {
        sendError("Gmail could not be checked (HTTP " + error.status + ").");
      }
    } catch (Exception error) {
      Log.e("MitraMail", "Could not load Gmail", error);
      sendError("Could not check Gmail. Check your internet connection.");
    }
  }

  private JSONObject toBrief(JSONObject message) throws Exception {
    JSONObject brief = new JSONObject();
    String from = "Unknown sender";
    String subject = "(No subject)";
    JSONObject payload = message.optJSONObject("payload");
    JSONArray headers = payload == null ? null : payload.optJSONArray("headers");
    if (headers != null) {
      for (int index = 0; index < headers.length(); index++) {
        JSONObject header = headers.getJSONObject(index);
        String name = header.optString("name", "");
        if ("From".equalsIgnoreCase(name)) from = header.optString("value", from);
        if ("Subject".equalsIgnoreCase(name)) subject = header.optString("value", subject);
      }
    }
    String preview = message.optString("snippet", "").trim();
    brief.put("id", message.optString("id", ""));
    brief.put("from", from);
    brief.put("subject", subject);
    brief.put("preview", preview);
    brief.put("important", isImportant(message, from, subject, preview));
    return brief;
  }

  private boolean isImportant(JSONObject message, String from, String subject, String preview) {
    String all = (from + " " + subject + " " + preview).toLowerCase(Locale.ROOT);
    int score = 0;
    if (all.contains("urgent") || all.contains("action required") || all.contains("security") || all.contains("verify") || all.contains("deadline")) score += 3;
    if (all.contains("interview") || all.contains("application") || all.contains("offer") || all.contains("appointment") || all.contains("payment") || all.contains("invoice")) score += 2;
    JSONArray labels = message.optJSONArray("labelIds");
    if (labels != null) for (int index = 0; index < labels.length(); index++) {
      String label = labels.optString(index, "");
      if ("IMPORTANT".equals(label)) score += 2;
      if ("CATEGORY_PROMOTIONS".equals(label) || "CATEGORY_SOCIAL".equals(label)) score -= 2;
    }
    if (all.contains("unsubscribe") || all.contains("newsletter") || all.contains("sale")) score -= 2;
    return score >= 2;
  }

  private JSONObject requestJson(String address, String token) throws Exception {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(address).openConnection();
      connection.setConnectTimeout(8000);
      connection.setReadTimeout(10000);
      connection.setRequestProperty("Authorization", "Bearer " + token);
      connection.setRequestProperty("Accept", "application/json");
      int status = connection.getResponseCode();
      String response = read(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
      if (status < 200 || status >= 300) throw new HttpError(status);
      return new JSONObject(response);
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private void revokeToken(String token) {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL("https://oauth2.googleapis.com/revoke?token=" + java.net.URLEncoder.encode(token, "UTF-8")).openConnection();
      connection.setConnectTimeout(8000);
      connection.setReadTimeout(10000);
      connection.setRequestMethod("POST");
      connection.getResponseCode();
    } catch (Exception error) {
      Log.w("MitraMail", "Google token revocation could not be confirmed", error);
    } finally {
      if (connection != null) connection.disconnect();
    }
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

  private void sendError(String message) { send("mitraGoogleError", message); }

  private void sendConnection(String state, String message) {
    try {
      JSONObject payload = new JSONObject();
      payload.put("state", state);
      payload.put("message", message);
      payload.put("email", email);
      payload.put("name", profileName);
      send("mitraGoogleConnection", payload.toString());
    } catch (Exception ignored) { }
  }

  private void send(String callback, String value) {
    webView.post(() -> webView.evaluateJavascript("window." + callback + "(" + JSONObject.quote(value) + ");", null));
  }

  void shutdown() { executor.shutdownNow(); }

  private static final class HttpError extends Exception {
    final int status;
    HttpError(int status) { this.status = status; }
  }
}
