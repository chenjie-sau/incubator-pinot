/**
 * Copyright (C) 2014-2016 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.linkedin.pinot.server.realtime;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.methods.multipart.PartSource;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.linkedin.pinot.common.protocols.SegmentCompletionProtocol;


/**
 * A class that handles sending segment completion protocol requests to the controller and getting
 * back responses
 */
public class ServerSegmentCompletionProtocolHandler {
  private static Logger LOGGER = LoggerFactory.getLogger(ServerSegmentCompletionProtocolHandler.class);

  private final String _instance;

  public ServerSegmentCompletionProtocolHandler(String instance) {
    _instance = instance;
  }

  public SegmentCompletionProtocol.Response segmentCommit(long offset, final String segmentName, final File segmentTarFile) throws FileNotFoundException {
    SegmentCompletionProtocol.SegmentCommitRequest request = new SegmentCompletionProtocol.SegmentCommitRequest(segmentName, offset, _instance);
    final InputStream inputStream = new FileInputStream(segmentTarFile);
    Part[] parts = {
        new FilePart(segmentName, new PartSource() {
          @Override
          public long getLength() {
            return segmentTarFile.length();
          }

          @Override
          public String getFileName() {
            return "fileName";
          }

          @Override
          public InputStream createInputStream() throws IOException {
            return new BufferedInputStream(inputStream);
          }
        })
    };
    return doHttp(request, parts);
  }

  public SegmentCompletionProtocol.Response segmentConsumed(String segmentName, long offset) {
    SegmentCompletionProtocol.SegmentConsumedRequest request = new SegmentCompletionProtocol.SegmentConsumedRequest(segmentName, offset, _instance);
    return doHttp(request, null);
  }

  public SegmentCompletionProtocol.Response segmentStoppedConsuming(String segmentName, long offset, String reason) {
    SegmentCompletionProtocol.SegmentStoppedConsuming request = new SegmentCompletionProtocol.SegmentStoppedConsuming(segmentName, offset, _instance, reason);
    return doHttp(request, null);
  }

  private SegmentCompletionProtocol.Response doHttp(SegmentCompletionProtocol.Request request, Part[] parts) {
    SegmentCompletionProtocol.Response response =
        new SegmentCompletionProtocol.Response(SegmentCompletionProtocol.ControllerResponseStatus.NOT_SENT, -1L);
    HttpClient httpClient = new HttpClient();
    ControllerLeaderLocator leaderLocator = ControllerLeaderLocator.getInstance();
    final String leaderAddress = leaderLocator.getControllerLeader();
    if (leaderAddress == null) {
      LOGGER.error("No leader found {}", this.toString());
      return new SegmentCompletionProtocol.Response(SegmentCompletionProtocol.ControllerResponseStatus.NOT_LEADER, -1L);
    }
    final String url = request.getUrl(leaderAddress);
    HttpMethodBase method;
    if (parts != null) {
      PostMethod postMethod = new PostMethod(url);
      postMethod.setRequestEntity(new MultipartRequestEntity(parts, new HttpMethodParams()));
      method = postMethod;
    } else {
      method = new GetMethod(url);
    }
    LOGGER.info("Sending request {} for {}", url, this.toString());
    try {
      int responseCode = httpClient.executeMethod(method);
      if (responseCode >= 300) {
        LOGGER.error("Bad controller response code {} for {}", responseCode, this.toString());
        return response;
      } else {
        response = new SegmentCompletionProtocol.Response(method.getResponseBodyAsString());
        LOGGER.info("Controller response {} for {}", response.toJsonString(), this.toString());
        if (response.getStatus().equals(SegmentCompletionProtocol.ControllerResponseStatus.NOT_LEADER)) {
          leaderLocator.refreshControllerLeader();
        }
        return response;
      }
    } catch (IOException e) {
      LOGGER.error("IOException {}", this.toString(), e);
      leaderLocator.refreshControllerLeader();
      return response;
    }
  }
}
