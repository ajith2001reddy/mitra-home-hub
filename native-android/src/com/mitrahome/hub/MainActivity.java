package com.mitrahome.hub;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;

public final class MainActivity extends Activity {
  private static final int CAMERA_PERMISSION_REQUEST = 41;
  private static final int MICROPHONE_PERMISSION_REQUEST = 42;
  private HomeAssistantBridge homeAssistantBridge;
  private GmailBridge gmailBridge;
  private MitraBrainBridge mitraBrainBridge;
  private VoiceBridge voiceBridge;
  private PhotoLibraryBridge photoLibraryBridge;
  private PermissionRequest pendingCameraRequest;
  private WebView dashboard;
  private boolean activityDestroyed;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private int rendererExitCount;
  private long lastRendererExitAt;

  @SuppressLint("SetJavaScriptEnabled")
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    WebView.setWebContentsDebuggingEnabled(false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.rgb(5, 11, 20));
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
      View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
      View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
      View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
      View.SYSTEM_UI_FLAG_FULLSCREEN |
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    );

    createDashboard();
  }

  @SuppressLint("SetJavaScriptEnabled")
  private void createDashboard() {
    dashboard = new WebView(this);
    WebSettings settings = dashboard.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setUserAgentString(settings.getUserAgentString() + " JarvisHomeNative");
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setAllowFileAccessFromFileURLs(false);
    settings.setAllowUniversalAccessFromFileURLs(false);
    settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
    settings.setOffscreenPreRaster(false);
    dashboard.setBackgroundColor(Color.rgb(5, 11, 20));
    // The dedicated dashboard is the active tablet task. Keep its renderer at
    // important priority while visible so ARC memory pressure is less likely
    // to kill it instead of an unused background process.
    dashboard.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
    homeAssistantBridge = new HomeAssistantBridge(this, dashboard);
    gmailBridge = new GmailBridge(this, dashboard);
    mitraBrainBridge = new MitraBrainBridge(this, dashboard, homeAssistantBridge);
    voiceBridge = new VoiceBridge(this, dashboard);
    photoLibraryBridge = new PhotoLibraryBridge(this, dashboard);
    // ChromeOS on this Duet has a reproducible Vulkan driver abort in the
    // WebView render thread. MITRA deliberately uses the software path so it
    // can remain on screen continuously without invoking that driver.
    dashboard.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    dashboard.addJavascriptInterface(homeAssistantBridge, "JarvisNative");
    dashboard.addJavascriptInterface(gmailBridge, "MitraGoogle");
    // The phone is a Home Assistant controller only. MITRA Brain, its local
    // history, and its API key are exposed solely on the Duet-sized hub screen.
    if (isLenovoHubScreen()) {
      dashboard.addJavascriptInterface(mitraBrainBridge, "MitraBrain");
      dashboard.addJavascriptInterface(voiceBridge, "MitraVoice");
      dashboard.addJavascriptInterface(photoLibraryBridge, "MitraPhotos");
    }
    dashboard.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onPermissionRequest(final PermissionRequest request) {
        runOnUiThread(() -> {
          if (!"appassets.androidplatform.net".equals(request.getOrigin().getHost()) || !requestsCamera(request)) {
            request.deny();
            return;
          }
          if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE });
            return;
          }
          pendingCameraRequest = request;
          requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA_PERMISSION_REQUEST);
        });
      }

      @Override
      public void onPermissionRequestCanceled(PermissionRequest request) {
        if (pendingCameraRequest == request) pendingCameraRequest = null;
      }

      @Override
      public boolean onConsoleMessage(ConsoleMessage message) {
        Log.e("JarvisWeb", message.message() + " @ " + message.sourceId() + ":" + message.lineNumber());
        return true;
      }
    });
    dashboard.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        // Keep trusted MITRA assets in this WebView. Any external web-search
        // citation opens in the device browser, where native interfaces do not exist.
        if ("appassets.androidplatform.net".equals(url.getHost())) return false;
        try {
          startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (Exception error) {
          Log.w("JarvisWeb", "Could not open external link", error);
        }
        return true;
      }

      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        if (!"appassets.androidplatform.net".equals(url.getHost())) {
          return super.shouldInterceptRequest(view, request);
        }
        String path = url.getPath();
        if (path != null && path.startsWith("/personal-photos/")) {
          WebResourceResponse photo = photoLibraryBridge.openPersonalPhoto(path.substring("/personal-photos/".length()));
          return photo != null ? photo : super.shouldInterceptRequest(view, request);
        }
        if (path == null || !path.startsWith("/assets/")) {
          return super.shouldInterceptRequest(view, request);
        }
        String assetPath = path.substring("/assets/".length());
        try {
          InputStream stream = getAssets().open(assetPath);
          return new WebResourceResponse(mimeType(assetPath), "UTF-8", stream);
        } catch (IOException error) {
          Log.e("JarvisWeb", "Missing bundled asset: " + assetPath, error);
          return super.shouldInterceptRequest(view, request);
        }
      }

      @Override
      public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        Log.e("JarvisWeb", "Load error " + errorCode + ": " + description + " @ " + failingUrl);
      }

      @Override
      public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        Log.w("JarvisWeb", "WebView renderer exited. didCrash=" + detail.didCrash());
        runOnUiThread(MainActivity.this::recoverFromRendererExit);
        return true;
      }
    });
    dashboard.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
    setContentView(dashboard);
  }

  private void recreateDashboard() {
    if (activityDestroyed || isFinishing()) return;
    pendingCameraRequest = null;
    releaseDashboard();
    createDashboard();
  }

  private void recoverFromRendererExit() {
    if (activityDestroyed || isFinishing()) return;
    long now = SystemClock.elapsedRealtime();
    if (now - lastRendererExitAt > 60_000L) rendererExitCount = 0;
    lastRendererExitAt = now;
    rendererExitCount++;
    pendingCameraRequest = null;
    releaseDashboard();
    // A low-memory kill used to create a rapid WebView recreate loop, making
    // the Duet look frozen and causing more pressure. Back off, then leave a
    // light native recovery screen after repeated exits instead.
    if (rendererExitCount >= 3) {
      showRecoveryScreen(true);
      return;
    }
    showRecoveryScreen(false);
    mainHandler.postDelayed(() -> {
      if (!activityDestroyed && !isFinishing()) createDashboard();
    }, rendererExitCount == 1 ? 2_000L : 5_000L);
  }

  private void releaseDashboard() {
    disposeBridges();
    if (dashboard != null) {
      dashboard.destroy();
      dashboard = null;
    }
  }

  private void showRecoveryScreen(boolean canRetry) {
    LinearLayout screen = new LinearLayout(this);
    screen.setOrientation(LinearLayout.VERTICAL);
    screen.setGravity(Gravity.CENTER);
    screen.setPadding(56, 56, 56, 56);
    screen.setBackgroundColor(Color.rgb(5, 11, 20));

    TextView title = new TextView(this);
    title.setText("MITRA is protecting tablet memory");
    title.setTextColor(Color.WHITE);
    title.setTextSize(26);
    screen.addView(title);

    TextView detail = new TextView(this);
    detail.setText(canRetry ? "Close unused Chrome or Android apps, then tap Retry MITRA." : "MITRA will recover in a moment.");
    detail.setTextColor(Color.rgb(202, 215, 235));
    detail.setTextSize(16);
    detail.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    detailParams.topMargin = 18;
    screen.addView(detail, detailParams);

    if (canRetry) {
      Button retry = new Button(this);
      retry.setText("Retry MITRA");
      retry.setOnClickListener(view -> {
        rendererExitCount = 0;
        createDashboard();
      });
      LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      buttonParams.topMargin = 28;
      screen.addView(retry, buttonParams);
    }
    setContentView(screen);
  }

  private void disposeBridges() {
    if (homeAssistantBridge != null) homeAssistantBridge.shutdown();
    if (gmailBridge != null) gmailBridge.shutdown();
    if (mitraBrainBridge != null) mitraBrainBridge.shutdown();
    if (voiceBridge != null) voiceBridge.shutdown();
    if (photoLibraryBridge != null) photoLibraryBridge.shutdown();
    homeAssistantBridge = null;
    gmailBridge = null;
    mitraBrainBridge = null;
    voiceBridge = null;
    photoLibraryBridge = null;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == MICROPHONE_PERMISSION_REQUEST) {
      boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
      if (voiceBridge != null) voiceBridge.onMicrophonePermissionResult(granted);
      return;
    }
    if (requestCode != CAMERA_PERMISSION_REQUEST || pendingCameraRequest == null) return;
    PermissionRequest cameraRequest = pendingCameraRequest;
    pendingCameraRequest = null;
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      cameraRequest.grant(new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE });
    } else {
      cameraRequest.deny();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (photoLibraryBridge != null && photoLibraryBridge.handleActivityResult(requestCode, resultCode, data)) return;
    if (gmailBridge != null && gmailBridge.handleActivityResult(requestCode, data)) return;
  }

  private boolean requestsCamera(PermissionRequest request) {
    for (String resource : request.getResources()) {
      if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) return true;
    }
    return false;
  }

  private boolean isLenovoHubScreen() {
    Configuration configuration = getResources().getConfiguration();
    return configuration.smallestScreenWidthDp >= 600;
  }

  void requestVoiceMicrophonePermission() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      if (voiceBridge != null) voiceBridge.beginListening();
      return;
    }
    requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, MICROPHONE_PERMISSION_REQUEST);
  }

  @Override
  protected void onDestroy() {
    activityDestroyed = true;
    pendingCameraRequest = null;
    mainHandler.removeCallbacksAndMessages(null);
    releaseDashboard();
    super.onDestroy();
  }

  private String mimeType(String path) {
    if (path.endsWith(".html")) return "text/html";
    if (path.endsWith(".js")) return "application/javascript";
    if (path.endsWith(".css")) return "text/css";
    if (path.endsWith(".svg")) return "image/svg+xml";
    if (path.endsWith(".webmanifest")) return "application/manifest+json";
    return "application/octet-stream";
  }

  @Override
  public void onBackPressed() {
    if (dashboard != null && dashboard.canGoBack()) {
      dashboard.goBack();
    } else {
      super.onBackPressed();
    }
  }
}
