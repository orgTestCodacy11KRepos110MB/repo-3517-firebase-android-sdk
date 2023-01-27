// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
//
// You may obtain a copy of the License at
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.remoteconfig.internal;

import static com.google.firebase.remoteconfig.FirebaseRemoteConfig.TAG;
import static com.google.firebase.remoteconfig.RemoteConfigConstants.REALTIME_REGEX_URL;
import static com.google.firebase.remoteconfig.internal.ConfigFetchHandler.HTTP_TOO_MANY_REQUESTS;
import static java.util.concurrent.TimeUnit.MINUTES;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import com.google.android.gms.common.util.AndroidUtilsLight;
import com.google.android.gms.common.util.Clock;
import com.google.android.gms.common.util.DefaultClock;
import com.google.android.gms.common.util.Hex;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.installations.FirebaseInstallationsApi;
import com.google.firebase.installations.InstallationTokenResult;
import com.google.firebase.remoteconfig.BuildConfig;
import com.google.firebase.remoteconfig.ConfigUpdate;
import com.google.firebase.remoteconfig.ConfigUpdateListener;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigClientException;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigServerException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

public class ConfigRealtimeHttpClient {
  /**
   * The exponential backoff intervals, up to ~4 hours.
   *
   * <p>Every value must be even.
   */
  @VisibleForTesting
  static final int[] BACKOFF_TIME_DURATIONS_IN_MINUTES = {2, 4, 8, 16, 32, 64, 128, 256};

  private static final String API_KEY_HEADER = "X-Goog-Api-Key";
  private static final String X_ANDROID_PACKAGE_HEADER = "X-Android-Package";
  private static final String X_ANDROID_CERT_HEADER = "X-Android-Cert";
  private static final String X_GOOGLE_GFE_CAN_RETRY = "X-Google-GFE-Can-Retry";
  private static final String INSTALLATIONS_AUTH_TOKEN_HEADER =
      "X-Goog-Firebase-Installations-Auth";
  private static final String X_ACCEPT_RESPONSE_STREAMING = "X-Accept-Response-Streaming";

  @GuardedBy("this")
  private final Set<ConfigUpdateListener> listeners;

  @GuardedBy("this")
  private boolean isHttpConnectionRunning;

  @GuardedBy("this")
  private int httpRetriesRemaining;

  @GuardedBy("this")
  private boolean isRealtimeDisabled;

  /** Flag to indicate whether or not the app is in the background or not. */
  private boolean isInBackground;

  private final int ORIGINAL_RETRIES = 8;
  private final ScheduledExecutorService scheduledExecutorService;
  private final ConfigFetchHandler configFetchHandler;
  private final FirebaseApp firebaseApp;
  private final FirebaseInstallationsApi firebaseInstallations;
  ConfigCacheClient activatedCache;
  private final Context context;
  private final String namespace;
  private final Random random;
  private final Clock clock;
  private final ConfigMetadataClient metadataClient;

  public ConfigRealtimeHttpClient(
      FirebaseApp firebaseApp,
      FirebaseInstallationsApi firebaseInstallations,
      ConfigFetchHandler configFetchHandler,
      ConfigCacheClient activatedCache,
      Context context,
      String namespace,
      Set<ConfigUpdateListener> listeners,
      ConfigMetadataClient metadataClient,
      ScheduledExecutorService scheduledExecutorService) {

    this.listeners = listeners;
    this.isHttpConnectionRunning = false;
    this.scheduledExecutorService = scheduledExecutorService;

    // Retry parameters
    this.random = new Random();
    httpRetriesRemaining =
        Math.max(
            ORIGINAL_RETRIES - metadataClient.getRealtimeBackoffMetadata().getNumFailedStreams(),
            1);
    clock = DefaultClock.getInstance();

    this.firebaseApp = firebaseApp;
    this.configFetchHandler = configFetchHandler;
    this.firebaseInstallations = firebaseInstallations;
    this.activatedCache = activatedCache;
    this.context = context;
    this.namespace = namespace;
    this.metadataClient = metadataClient;
    this.isRealtimeDisabled = false;
    this.isInBackground = false;
  }

  /**
   * A regular expression for the GMP App Id format. The first group (index 1) is the project
   * number.
   */
  private static final Pattern GMP_APP_ID_PATTERN =
      Pattern.compile("^[^:]+:([0-9]+):(android|ios|web):([0-9a-f]+)");

  private static String extractProjectNumberFromAppId(String gmpAppId) {
    Matcher matcher = GMP_APP_ID_PATTERN.matcher(gmpAppId);
    return matcher.matches() ? matcher.group(1) : null;
  }

  private void getInstallationAuthToken(HttpURLConnection httpURLConnection) {
    Task<InstallationTokenResult> installationAuthTokenTask = firebaseInstallations.getToken(false);
    installationAuthTokenTask.onSuccessTask(
        scheduledExecutorService,
        unusedToken -> {
          httpURLConnection.setRequestProperty(
              INSTALLATIONS_AUTH_TOKEN_HEADER, unusedToken.getToken());
          return Tasks.forResult(null);
        });
  }

  /** Gets the Android package's SHA-1 fingerprint. */
  private String getFingerprintHashForPackage() {
    byte[] hash;

    try {
      hash =
          AndroidUtilsLight.getPackageCertificateHashBytes(
              this.context, this.context.getPackageName());
      if (hash == null) {
        Log.e(TAG, "Could not get fingerprint hash for package: " + this.context.getPackageName());
        return null;
      } else {
        return Hex.bytesToStringUppercase(hash, /* zeroTerminated= */ false);
      }
    } catch (PackageManager.NameNotFoundException e) {
      Log.i(TAG, "No such package: " + this.context.getPackageName());
      return null;
    }
  }

  private void setCommonRequestHeaders(HttpURLConnection httpURLConnection) {
    // Get Installation Token
    getInstallationAuthToken(httpURLConnection);

    // API Key
    httpURLConnection.setRequestProperty(API_KEY_HEADER, this.firebaseApp.getOptions().getApiKey());

    // Headers required for Android API Key Restrictions.
    httpURLConnection.setRequestProperty(X_ANDROID_PACKAGE_HEADER, context.getPackageName());
    httpURLConnection.setRequestProperty(X_ANDROID_CERT_HEADER, getFingerprintHashForPackage());

    // Header to denote request is retryable on the server.
    httpURLConnection.setRequestProperty(X_GOOGLE_GFE_CAN_RETRY, "yes");

    // Header to tell server that client expects stream response
    httpURLConnection.setRequestProperty(X_ACCEPT_RESPONSE_STREAMING, "true");

    // Headers to denote that the request body is a JSONObject.
    httpURLConnection.setRequestProperty("Content-Type", "application/json");
    httpURLConnection.setRequestProperty("Accept", "application/json");
  }

  private JSONObject createRequestBody() {
    Map<String, String> body = new HashMap<>();
    body.put(
        "project", extractProjectNumberFromAppId(this.firebaseApp.getOptions().getApplicationId()));
    body.put("namespace", this.namespace);
    body.put(
        "lastKnownVersionNumber", Long.toString(configFetchHandler.getTemplateVersionNumber()));
    body.put("appId", firebaseApp.getOptions().getApplicationId());
    body.put("sdkVersion", BuildConfig.VERSION_NAME);

    return new JSONObject(body);
  }

  private void setRequestParams(HttpURLConnection httpURLConnection) throws IOException {
    httpURLConnection.setRequestMethod("POST");
    byte[] body = createRequestBody().toString().getBytes("utf-8");
    OutputStream outputStream = new BufferedOutputStream(httpURLConnection.getOutputStream());
    outputStream.write(body);
    outputStream.flush();
    outputStream.close();
  }

  private synchronized void propagateErrors(FirebaseRemoteConfigException exception) {
    for (ConfigUpdateListener listener : listeners) {
      listener.onError(exception);
    }
  }

  @SuppressLint("VisibleForTests")
  public int getNumberOfFailedStream() {
    return metadataClient.getRealtimeBackoffMetadata().getNumFailedStreams();
  }

  @SuppressLint("VisibleForTests")
  public Date getBackoffDate() {
    return metadataClient.getRealtimeBackoffMetadata().getBackoffEndTime();
  }

  // TODO(issues/265): Make this an atomic operation within the Metadata class to avoid possible
  // concurrency issues.
  /**
   * Increment the number of failed stream attempts, increase the backoff duration, set the backoff
   * end time to "backoff duration" after {@code lastFailedRealtimeStreamTime} and persist the new
   * values to disk-backed metadata.
   */
  private void updateBackoffMetadataWithLastFailedStreamConnectionTime(
      Date lastFailedRealtimeStreamTime) {
    int numFailedStreams = metadataClient.getRealtimeBackoffMetadata().getNumFailedStreams();

    numFailedStreams++;

    long backoffDurationInMillis = getRandomizedBackoffDurationInMillis(numFailedStreams);
    Date backoffEndTime =
        new Date(lastFailedRealtimeStreamTime.getTime() + backoffDurationInMillis);

    metadataClient.setRealtimeBackoffMetadata(numFailedStreams, backoffEndTime);
  }

  /**
   * Returns a random backoff duration from the range {@code timeoutDuration} +/- 50% of {@code
   * timeoutDuration}, where {@code timeoutDuration = }{@link
   * #BACKOFF_TIME_DURATIONS_IN_MINUTES}{@code [numFailedStreams-1]}.
   */
  private long getRandomizedBackoffDurationInMillis(int numFailedStreams) {
    // Index of backoff duration. Use the minimum of numFailedFetch or the max length of backoff
    // duration array.
    int idx = Math.min(numFailedStreams, BACKOFF_TIME_DURATIONS_IN_MINUTES.length);
    // The backoff duration length after numFailedStreams.
    long timeOutDurationInMillis = MINUTES.toMillis(BACKOFF_TIME_DURATIONS_IN_MINUTES[idx - 1]);

    // A random duration that is in the range: timeOutDuration +/- 50% of timeOutDuration.
    return timeOutDurationInMillis / 2 + random.nextInt((int) timeOutDurationInMillis);
  }

  private synchronized void enableBackoff() {
    this.isRealtimeDisabled = true;
  }

  private synchronized boolean canMakeHttpStreamConnection() {
    return !listeners.isEmpty()
        && !isHttpConnectionRunning
        && !isRealtimeDisabled
        && !isInBackground;
  }

  private String getRealtimeURL(String namespace) {
    return String.format(
        REALTIME_REGEX_URL,
        extractProjectNumberFromAppId(firebaseApp.getOptions().getApplicationId()),
        namespace);
  }

  private URL getUrl() {
    URL realtimeURL = null;
    try {
      realtimeURL = new URL(getRealtimeURL(namespace));
    } catch (MalformedURLException ex) {
      Log.e(TAG, "URL is malformed");
    }

    return realtimeURL;
  }

  /** Create HTTP connection and set headers. */
  @SuppressLint("VisibleForTests")
  public HttpURLConnection createRealtimeConnection() throws IOException {
    URL realtimeUrl = getUrl();
    HttpURLConnection httpURLConnection = (HttpURLConnection) realtimeUrl.openConnection();
    setCommonRequestHeaders(httpURLConnection);
    setRequestParams(httpURLConnection);

    return httpURLConnection;
  }

  /** Initial Http stream attempt that makes call without waiting. */
  public void startHttpConnection() {
    makeRealtimeHttpConnection(/*retrySeconds*/ 0);
  }

  /** Retries HTTP stream connection asyncly in random time intervals. */
  @SuppressLint("VisibleForTests")
  public synchronized void retryHttpConnection() {
    Date currentTime = new Date(clock.currentTimeMillis());
    long retrySeconds =
        Math.max(
            0,
            metadataClient.getRealtimeBackoffMetadata().getBackoffEndTime().getTime()
                - currentTime.getTime());
    makeRealtimeHttpConnection(retrySeconds);
  }

  private synchronized void makeRealtimeHttpConnection(long retryMilliseconds) {
    if (!canMakeHttpStreamConnection()) {
      return;
    }

    if (httpRetriesRemaining > 0) {
      httpRetriesRemaining--;
      scheduledExecutorService.schedule(
          new Runnable() {
            @Override
            public void run() {
              beginRealtimeHttpStream();
            }
          },
          retryMilliseconds,
          TimeUnit.MILLISECONDS);
    } else if (!isInBackground) {
      propagateErrors(
          new FirebaseRemoteConfigClientException(
              "Unable to connect to the server. Check your connection and try again.",
              FirebaseRemoteConfigException.Code.CONFIG_UPDATE_STREAM_ERROR));
    }
  }

  void setRealtimeBackgroundState(boolean backgroundState) {
    isInBackground = backgroundState;
  }

  private synchronized void setIsHttpConnectionRunning(boolean connectionRunning) {
    isHttpConnectionRunning = connectionRunning;
  }

  /**
   * Create Autofetch class that listens on HTTP stream for ConfigUpdate messages and calls Fetch
   * accordingly.
   */
  @SuppressLint("VisibleForTests")
  public synchronized ConfigAutoFetch startAutoFetch(HttpURLConnection httpURLConnection) {
    ConfigUpdateListener retryCallback =
        new ConfigUpdateListener() {
          @Override
          public void onUpdate(@NonNull ConfigUpdate configUpdate) {}

          // This method will only be called when a realtimeDisabled message is sent down the
          // stream.
          @Override
          public void onError(@NonNull FirebaseRemoteConfigException error) {
            enableBackoff();
            propagateErrors(error);
          }
        };

    return new ConfigAutoFetch(
        httpURLConnection,
        configFetchHandler,
        activatedCache,
        listeners,
        retryCallback,
        scheduledExecutorService);
  }

  // HTTP status code that the Realtime client should retry on.
  private boolean isStatusCodeRetryable(int statusCode) {
    return statusCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT
        || statusCode == HTTP_TOO_MANY_REQUESTS
        || statusCode == HttpURLConnection.HTTP_BAD_GATEWAY
        || statusCode == HttpURLConnection.HTTP_UNAVAILABLE
        || statusCode == HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
  }

  /**
   * Open the realtime connection, begin listening for updates, and auto-fetch when an update is
   * received.
   *
   * <p>If the connection is successful, this method will block on its thread while it reads the
   * chunk-encoded HTTP body. When the connection closes, it attempts to reestablish the stream.
   */
  @SuppressLint({"VisibleForTests", "DefaultLocale"})
  public void beginRealtimeHttpStream() {
    if (!canMakeHttpStreamConnection()) {
      return;
    }

    ConfigMetadataClient.RealtimeBackoffMetadata backoffMetadata =
        metadataClient.getRealtimeBackoffMetadata();
    Date currentTime = new Date(clock.currentTimeMillis());
    if (currentTime.before(backoffMetadata.getBackoffEndTime())) {
      retryHttpConnection();
      return;
    }

    Integer responseCode = null;
    HttpURLConnection httpURLConnection = null;
    setIsHttpConnectionRunning(true);
    try {
      // Create the open the connection.
      httpURLConnection = createRealtimeConnection();
      responseCode = httpURLConnection.getResponseCode();

      // If the connection returned a 200 response code, start listening for messages.
      if (responseCode == HttpURLConnection.HTTP_OK) {
        // Reset the retries remaining if we opened the connection without an exception.
        httpRetriesRemaining = ORIGINAL_RETRIES;
        metadataClient.resetRealtimeBackoff();

        // Start listening for realtime notifications.
        ConfigAutoFetch configAutoFetch = startAutoFetch(httpURLConnection);
        configAutoFetch.listenForNotifications();
      }
    } catch (IOException e) {
      Log.d(TAG, "Exception connecting to realtime stream. Retrying the connection...");
      propagateErrors(
          new FirebaseRemoteConfigServerException(
              "Unable to connect to the server. Try again in a few minutes.",
              e.getCause(),
              FirebaseRemoteConfigException.Code.CONFIG_UPDATE_STREAM_ERROR));
    } finally {
      closeRealtimeHttpStream(httpURLConnection);
      setIsHttpConnectionRunning(false);

      boolean connectionFailed = responseCode == null || isStatusCodeRetryable(responseCode);
      if (connectionFailed) {
        updateBackoffMetadataWithLastFailedStreamConnectionTime(
            new Date(clock.currentTimeMillis()));
      }

      // If responseCode is null then no connection was made to server and the SDK should still
      // retry.
      if (connectionFailed || responseCode == HttpURLConnection.HTTP_OK) {
        retryHttpConnection();
      } else {
        propagateErrors(
            new FirebaseRemoteConfigServerException(
                responseCode,
                String.format(
                    "Unable to connect to the server. Try again in a few minutes. Http Status code: %d",
                    responseCode),
                FirebaseRemoteConfigException.Code.CONFIG_UPDATE_STREAM_ERROR));
      }
    }
  }

  // Pauses Http stream listening
  public void closeRealtimeHttpStream(HttpURLConnection httpURLConnection) {
    if (httpURLConnection != null) {
      httpURLConnection.disconnect();

      // Explicitly close the input stream due to a bug in the Android okhttp implementation.
      // See github.com/firebase/firebase-android-sdk/pull/808.
      try {
        httpURLConnection.getInputStream().close();
        if (httpURLConnection.getErrorStream() != null) {
          httpURLConnection.getErrorStream().close();
        }
      } catch (IOException e) {
      }
    }
  }
}
