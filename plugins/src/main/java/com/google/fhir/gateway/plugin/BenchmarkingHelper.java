/*
 * Copyright 2021-2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.fhir.gateway.plugin;

import org.slf4j.Logger;

public class BenchmarkingHelper {
  public static void printCompletedInDuration(long startTime, String methodDetails, Logger logger) {
    long nanoSecondsTaken = System.nanoTime() - startTime;
    long millSecondsTaken = nanoSecondsTaken / 1000000;
    logger.error(
        String.format(
            "########## %s completed in %s : Metric in seconds - %d : Metric in nanoseconds - %d",
            methodDetails,
            getHumanDuration(millSecondsTaken),
            millSecondsTaken / 1000,
            nanoSecondsTaken));
  }

  public static String getHumanDuration(long milliseconds) {

    long minutes = (milliseconds / 1000) / 60;
    long seconds = (milliseconds / 1000) % 60;
    String secondsStr = Long.toString(seconds);
    String secs;
    if (secondsStr.length() >= 2) {
      secs = secondsStr.substring(0, 2);
    } else {
      secs = "0" + secondsStr;
    }

    return minutes == 0 && seconds == 0
        ? "less than a second"
        : minutes + " mins " + secs + " secs";
  }

  public static long startBenchmarking() {
    return System.nanoTime();
  }
}
