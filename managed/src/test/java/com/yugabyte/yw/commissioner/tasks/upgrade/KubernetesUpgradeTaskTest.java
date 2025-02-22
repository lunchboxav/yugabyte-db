// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks.upgrade;

import static com.yugabyte.yw.common.ApiUtils.getTestUserIntent;
import static com.yugabyte.yw.common.AssertHelper.assertJsonEqual;
import static com.yugabyte.yw.common.ModelFactory.createUniverse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Before;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.tasks.CommissionerBaseTest;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.common.TestUtils;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.forms.UpgradeTaskParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.InstanceType;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.TaskType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.yb.client.IsServerReadyResponse;
import org.yb.client.ChangeMasterClusterConfigResponse;
import org.yb.client.GetLoadMovePercentResponse;
import org.yb.client.GetMasterClusterConfigResponse;
import org.yb.client.YBClient;
import org.yb.master.CatalogEntityInfo;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodStatus;

public abstract class KubernetesUpgradeTaskTest extends CommissionerBaseTest {

  protected Universe defaultUniverse;
  protected static final String NODE_PREFIX = "demo-universe";
  protected static final String YB_SOFTWARE_VERSION_OLD = "old-version";
  protected static final String YB_SOFTWARE_VERSION_NEW = "new-version";
  protected Map<String, String> config = new HashMap<>();

  protected void setupUniverse(
      boolean setMasters,
      UserIntent userIntent,
      PlacementInfo placementInfo,
      boolean mockGetLeaderMaster) {
    userIntent.replicationFactor = 3;
    userIntent.masterGFlags = new HashMap<>();
    userIntent.tserverGFlags = new HashMap<>();
    userIntent.universeName = "demo-universe";
    userIntent.ybSoftwareVersion = YB_SOFTWARE_VERSION_OLD;
    defaultUniverse = createUniverse(defaultCustomer.getCustomerId());
    config.put("KUBECONFIG", "test");
    defaultProvider.setConfig(config);
    defaultProvider.save();
    Universe.saveDetails(
        defaultUniverse.universeUUID,
        ApiUtils.mockUniverseUpdater(userIntent, NODE_PREFIX, setMasters, false, placementInfo));
    defaultUniverse = Universe.getOrBadRequest(defaultUniverse.universeUUID);
    defaultUniverse.updateConfig(
        ImmutableMap.of(Universe.HELM2_LEGACY, Universe.HelmLegacy.V3.toString()));

    CatalogEntityInfo.SysClusterConfigEntryPB.Builder configBuilder =
        CatalogEntityInfo.SysClusterConfigEntryPB.newBuilder().setVersion(1);

    GetMasterClusterConfigResponse mockConfigResponse =
        new GetMasterClusterConfigResponse(0, "", configBuilder.build(), null);
    ChangeMasterClusterConfigResponse mockMasterChangeConfigResponse =
        new ChangeMasterClusterConfigResponse(0, "", null);
    GetLoadMovePercentResponse mockGetLoadMovePercentResponse =
        new GetLoadMovePercentResponse(0, "", 100.0, 0, 0, null);

    try {
      String statusString = "{ \"phase\": \"Running\", \"conditions\": [{\"status\": \"True\"}]}";
      PodStatus status = TestUtils.deserialize(statusString, PodStatus.class);
      when(mockKubernetesManager.getPodStatus(any(), any(), any())).thenReturn(status);
      YBClient mockClient = mock(YBClient.class);
      when(mockClient.waitForServer(any(), anyLong())).thenReturn(true);

      String masterLeaderName = "yb-master-0.yb-masters.demo-universe.svc.cluster.local";
      if (placementInfo.cloudList.get(0).regionList.get(0).azList.size() > 1) {
        masterLeaderName = "yb-master-0.yb-masters.demo-universe-az-2.svc.cluster.local";
      }
      if (mockGetLeaderMaster) {
        when(mockClient.getLeaderMasterHostAndPort())
            .thenReturn(HostAndPort.fromString(masterLeaderName).withDefaultPort(11));
      }

      IsServerReadyResponse okReadyResp = new IsServerReadyResponse(0, "", null, 0, 0);
      when(mockClient.isServerReady(any(), anyBoolean())).thenReturn(okReadyResp);
      when(mockYBClient.getClient(any(), any())).thenReturn(mockClient);
      when(mockYBClient.getClient(any(), any())).thenReturn(mockClient);
      when(mockClient.getMasterClusterConfig()).thenReturn(mockConfigResponse);
      when(mockClient.changeMasterClusterConfig(any())).thenReturn(mockMasterChangeConfigResponse);
      when(mockClient.getLeaderBlacklistCompletion()).thenReturn(mockGetLoadMovePercentResponse);
    } catch (Exception ignored) {
    }
  }

  protected void setupUniverseSingleAZ(boolean setMasters, boolean mockGetLeaderMaster) {
    Region r = Region.create(defaultProvider, "region-1", "PlacementRegion-1", "default-image");
    AvailabilityZone az1 = AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ-1", "subnet-1");
    InstanceType i =
        InstanceType.upsert(
            defaultProvider.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());
    UserIntent userIntent = getTestUserIntent(r, defaultProvider, i, 3);
    PlacementInfo placementInfo = new PlacementInfo();
    PlacementInfoUtil.addPlacementZone(az1.uuid, placementInfo, 3, 3, true);
    setupUniverse(setMasters, userIntent, placementInfo, mockGetLeaderMaster);
    String podsString =
        "{\"items\": [{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", "
            + "\"podIP\": \"1.2.3.1\"}, \"spec\": {\"hostname\": \"yb-master-0\"},"
            + " \"metadata\": {\"namespace\": \""
            + NODE_PREFIX
            + "\"}},"
            + "{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", "
            + "\"podIP\": \"1.2.3.2\"}, \"spec\": {\"hostname\": \"yb-tserver-0\"},"
            + " \"metadata\": {\"namespace\": \""
            + NODE_PREFIX
            + "\"}},"
            + "{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", "
            + "\"podIP\": \"1.2.3.3\"}, \"spec\": {\"hostname\": \"yb-master-1\"},"
            + " \"metadata\": {\"namespace\": \""
            + NODE_PREFIX
            + "\"}},"
            + "{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", "
            + "\"podIP\": \"1.2.3.4\"}, \"spec\": {\"hostname\": \"yb-tserver-1\"},"
            + " \"metadata\": {\"namespace\": \""
            + NODE_PREFIX
            + "\"}},"
            + "{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", "
            + "\"podIP\": \"1.2.3.5\"}, \"spec\": {\"hostname\": \"yb-master-2\"},"
            + " \"metadata\": {\"namespace\": \""
            + NODE_PREFIX
            + "\"}},"
            + "{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", "
            + "\"podIP\": \"1.2.3.6\"}, \"spec\": {\"hostname\": \"yb-tserver-2\"},"
            + " \"metadata\": {\"namespace\": \""
            + NODE_PREFIX
            + "\"}}]}";
    List<Pod> pods = TestUtils.deserialize(podsString, PodList.class).getItems();
    when(mockKubernetesManager.getPodInfos(any(), any(), any())).thenReturn(pods);
  }

  protected void setupUniverseMultiAZ(boolean setMasters, boolean mockGetLeaderMaster) {
    Region r = Region.create(defaultProvider, "region-1", "PlacementRegion-1", "default-image");
    AvailabilityZone az1 = AvailabilityZone.createOrThrow(r, "az-1", "PlacementAZ-1", "subnet-1");
    AvailabilityZone az2 = AvailabilityZone.createOrThrow(r, "az-2", "PlacementAZ-2", "subnet-2");
    AvailabilityZone az3 = AvailabilityZone.createOrThrow(r, "az-3", "PlacementAZ-3", "subnet-3");
    InstanceType i =
        InstanceType.upsert(
            defaultProvider.uuid, "c3.xlarge", 10, 5.5, new InstanceType.InstanceTypeDetails());
    UserIntent userIntent = getTestUserIntent(r, defaultProvider, i, 3);
    PlacementInfo placementInfo = new PlacementInfo();
    PlacementInfoUtil.addPlacementZone(az1.uuid, placementInfo, 1, 1, false);
    PlacementInfoUtil.addPlacementZone(az2.uuid, placementInfo, 1, 1, true);
    PlacementInfoUtil.addPlacementZone(az3.uuid, placementInfo, 1, 1, false);
    setupUniverse(setMasters, userIntent, placementInfo, mockGetLeaderMaster);

    String nodePrefix1 = String.format("%s-%s", NODE_PREFIX, az1.code);
    String nodePrefix2 = String.format("%s-%s", NODE_PREFIX, az2.code);
    String nodePrefix3 = String.format("%s-%s", NODE_PREFIX, az3.code);

    String podInfosMessage =
        "{\"items\": [{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", "
            + "\"podIP\": \"1.2.3.1\"}, \"spec\": {\"hostname\": \"yb-master-0\"},"
            + " \"metadata\": {\"namespace\": \"%1$s\"}},"
            + "{\"status\": {\"startTime\": \"1234\", \"phase\": \"Running\", "
            + "\"podIP\": \"1.2.3.2\"}, \"spec\": {\"hostname\": \"yb-tserver-0\"},"
            + " \"metadata\": {\"namespace\": \"%1$s\"}}]}";
    List<Pod> pods1 =
        TestUtils.deserialize(String.format(podInfosMessage, nodePrefix1), PodList.class)
            .getItems();
    when(mockKubernetesManager.getPodInfos(any(), eq(nodePrefix1), eq(nodePrefix1)))
        .thenReturn(pods1);
    List<Pod> pods2 =
        TestUtils.deserialize(String.format(podInfosMessage, nodePrefix2), PodList.class)
            .getItems();
    when(mockKubernetesManager.getPodInfos(any(), eq(nodePrefix2), eq(nodePrefix2)))
        .thenReturn(pods2);
    List<Pod> pods3 =
        TestUtils.deserialize(String.format(podInfosMessage, nodePrefix3), PodList.class)
            .getItems();
    when(mockKubernetesManager.getPodInfos(any(), eq(nodePrefix3), eq(nodePrefix3)))
        .thenReturn(pods3);
  }

  protected void assertTaskSequence(
      Map<Integer, List<TaskInfo>> subTasksByPosition,
      List<TaskType> expectedTaskSequence,
      List<JsonNode> expectedResultsList) {
    int position = 0;
    for (TaskType task : expectedTaskSequence) {
      List<TaskInfo> tasks = subTasksByPosition.get(position);
      // Leader blacklisting adds the ModifyBlackList task at taskInfo[0].
      int numTasksToAssert = position == 0 ? 2 : 1;
      assertEquals(numTasksToAssert, tasks.size());
      assertEquals(task, tasks.get(0).getTaskType());
      JsonNode expectedResults = expectedResultsList.get(position);
      List<JsonNode> taskDetails =
          tasks.stream().map(TaskInfo::getTaskDetails).collect(Collectors.toList());
      assertJsonEqual(expectedResults, taskDetails.get(0));
      position++;
    }
  }

  protected TaskInfo submitTask(
      UpgradeTaskParams taskParams, TaskType taskType, Commissioner commissioner) {
    taskParams.universeUUID = defaultUniverse.universeUUID;
    taskParams.clusters = defaultUniverse.getUniverseDetails().clusters;
    taskParams.nodePrefix = NODE_PREFIX;
    taskParams.expectedUniverseVersion = 2;
    // Need not sleep for default 3min in tests.
    taskParams.sleepAfterMasterRestartMillis = 5;
    taskParams.sleepAfterTServerRestartMillis = 5;

    try {
      UUID taskUUID = commissioner.submit(taskType, taskParams);
      return waitForTask(taskUUID);
    } catch (InterruptedException e) {
      assertNull(e.getMessage());
    }
    return null;
  }
}
