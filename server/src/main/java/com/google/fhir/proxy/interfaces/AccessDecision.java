/*
 * Copyright 2021-2022 Google LLC
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
package com.google.fhir.proxy.interfaces;

import java.io.IOException;

import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.apache.http.HttpResponse;

public interface AccessDecision {

  /**
   * @return true iff access was granted.
   */
  boolean canAccess();

  void preProcess(ServletRequestDetails servletRequestDetails);

  /**
   * Depending on the outcome of the FHIR operations, this does any post-processing operations that
   * are related to access policies. This is expected to be called only if the actual FHIR operation
   * is finished successfully.
   *
   * @param response the response returned from the FHIR store
   * @return the response entity content (with any post-processing modifications needed) if this
   *     reads the response; otherwise null. Note that we should try to avoid reading the whole
   *     content in memory whenever it is not needed for post-processing.
   */
  String postProcess(HttpResponse response) throws IOException;
}
