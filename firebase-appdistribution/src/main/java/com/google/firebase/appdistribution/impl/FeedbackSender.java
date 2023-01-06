// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.appdistribution.impl;

import android.net.Uri;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.annotations.concurrent.Blocking;
import com.google.firebase.annotations.concurrent.Lightweight;
import java.util.concurrent.Executor;

/** Sends tester feedback to the Tester API. */
class FeedbackSender {
  private final FirebaseAppDistributionTesterApiClient testerApiClient;
  @Blocking private final Executor blockingExecutor;
  @Lightweight private final Executor lightweightExecutor;

  FeedbackSender(
      FirebaseAppDistributionTesterApiClient testerApiClient,
      @Blocking Executor blockingExecutor,
      @Lightweight Executor lightweightExecutor) {
    this.testerApiClient = testerApiClient;
    this.blockingExecutor = blockingExecutor;
    this.lightweightExecutor = lightweightExecutor;
  }

  /** Get an instance of FeedbackSender. */
  static FeedbackSender getInstance() {
    return FirebaseApp.getInstance().get(FeedbackSender.class);
  }

  /** Send feedback text and optionally a screenshot to the Tester API for the given release. */
  Task<Void> sendFeedback(String releaseName, String feedbackText, @Nullable Uri screenshotUri) {
    return testerApiClient
        .createFeedback(releaseName, feedbackText)
        .onSuccessTask(
            lightweightExecutor, feedbackName -> attachScreenshot(feedbackName, screenshotUri))
        .onSuccessTask(lightweightExecutor, testerApiClient::commitFeedback);
  }

  // TODO(kbolay): Remove this hack to make the executor available in FeedbackAction and use a more
  //     sophisticated dependency injection solution.
  Executor getBlockingExecutor() {
    return blockingExecutor;
  }

  private Task<String> attachScreenshot(String feedbackName, @Nullable Uri screenshotUri) {
    if (screenshotUri == null) {
      return Tasks.forResult(feedbackName);
    }
    return testerApiClient.attachScreenshot(feedbackName, screenshotUri);
  }
}
