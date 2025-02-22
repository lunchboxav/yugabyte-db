/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0
 * .txt
 */

package com.yugabyte.yw.common;

import static scala.compat.java8.JFunction.func;

import akka.stream.javadsl.FileIO;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.controllers.HAAuthenticator;
import com.yugabyte.yw.controllers.ReverseInternalHAController;
import com.yugabyte.yw.models.HighAvailabilityConfig;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Call;
import play.mvc.Http;
import v1.RoutesPrefix;

public class PlatformInstanceClient {

  private static final Logger LOG = LoggerFactory.getLogger(PlatformInstanceClient.class);

  private final ApiHelper apiHelper;

  private final String remoteAddress;

  private final Map<String, String> requestHeader;

  private final ReverseInternalHAController controller;

  public PlatformInstanceClient(ApiHelper apiHelper, String clusterKey, String remoteAddress) {
    this.apiHelper = apiHelper;
    this.remoteAddress = remoteAddress;
    this.requestHeader = ImmutableMap.of(HAAuthenticator.HA_CLUSTER_KEY_TOKEN_HEADER, clusterKey);
    this.controller = new ReverseInternalHAController(func(this::getPrefix));
  }

  private String getPrefix() {
    return String.format("%s%s", this.remoteAddress, RoutesPrefix.prefix());
  }

  // Map a Call object to a request.
  private JsonNode makeRequest(Call call, JsonNode payload) {
    JsonNode response;
    switch (call.method()) {
      case "GET":
        response = this.apiHelper.getRequest(call.url(), this.requestHeader);
        break;
      case "PUT":
        response = this.apiHelper.putRequest(call.url(), payload, this.requestHeader);
        break;
      case "POST":
        response = this.apiHelper.postRequest(call.url(), payload, this.requestHeader);
        break;
      default:
        throw new RuntimeException("Unsupported operation: " + call.method());
    }

    if (response == null || response.get("error") != null) {
      LOG.error("Error received from remote instance {}: {}", this.remoteAddress, response);

      throw new RuntimeException("Error received from remote instance " + this.remoteAddress);
    }

    return response;
  }

  /**
   * calls {@link com.yugabyte.yw.controllers.InternalHAController#getHAConfigByClusterKey()} on
   * remote platform instance
   *
   * @return a HighAvailabilityConfig model representing the remote platform instance's HA config
   */
  public HighAvailabilityConfig getRemoteConfig() {
    JsonNode response = this.makeRequest(this.controller.getHAConfigByClusterKey(), null);

    return Json.fromJson(response, HighAvailabilityConfig.class);
  }

  /**
   * calls {@link com.yugabyte.yw.controllers.InternalHAController#syncInstances(long timestamp)} on
   * remote platform instance
   *
   * @param payload the JSON platform instance data
   */
  public void syncInstances(long timestamp, JsonNode payload) {
    this.makeRequest(this.controller.syncInstances(timestamp), payload);
  }

  /**
   * calls {@link com.yugabyte.yw.controllers.InternalHAController#demoteLocalLeader(long
   * timestamp)} on remote platform instance
   */
  public void demoteInstance(String localAddr, long timestamp) {
    ObjectNode formData = Json.newObject().put("leader_address", localAddr);
    this.makeRequest(this.controller.demoteLocalLeader(timestamp), formData);
  }

  public boolean syncBackups(String leaderAddr, String senderAddr, File backupFile) {
    JsonNode response =
        this.apiHelper.multipartRequest(
            this.controller.syncBackups().url(),
            this.requestHeader,
            buildPartsList(
                backupFile, ImmutableMap.of("leader", leaderAddr, "sender", senderAddr)));
    if (response == null || response.get("error") != null) {
      LOG.error("Error received from remote instance {}. Got {}", this.remoteAddress, response);
      return false;
    } else {
      return true;
    }
  }

  public static List<Http.MultipartFormData.Part<Source<ByteString, ?>>> buildPartsList(
      File file, ImmutableMap<String, String> dataParts) {
    Http.MultipartFormData.FilePart<Source<ByteString, ?>> filePart =
        new Http.MultipartFormData.FilePart<>(
            "backup", file.getName(), "application/octet-stream", FileIO.fromFile(file, 1024));

    List<Http.MultipartFormData.Part<Source<ByteString, ?>>> ret =
        dataParts
            .entrySet()
            .stream()
            .map(kv -> new Http.MultipartFormData.DataPart(kv.getKey(), kv.getValue()))
            .collect(Collectors.toList());

    ret.add(filePart);
    return ret;
  }
}
