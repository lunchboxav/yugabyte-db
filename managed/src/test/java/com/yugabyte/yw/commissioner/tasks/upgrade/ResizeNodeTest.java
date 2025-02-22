// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks.upgrade;

import static com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType.EITHER;
import static com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType.MASTER;
import static com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType.TSERVER;
import static com.yugabyte.yw.models.TaskInfo.State.Failure;
import static com.yugabyte.yw.models.TaskInfo.State.Success;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase;
import com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.forms.ResizeNodeParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UpgradeTaskParams;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.DeviceInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.TaskType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
@Slf4j
public class ResizeNodeTest extends UpgradeTaskTest {

  private static final String DEFAULT_INSTANCE_TYPE = "c3.medium";
  private static final String NEW_INSTANCE_TYPE = "c4.medium";

  private static final int DEFAULT_VOLUME_SIZE = 100;
  private static final int NEW_VOLUME_SIZE = 200;

  // Tasks for RF1 configuration do not create sub-tasks for
  // leader blacklisting. So create two PLACEHOLDER indexes
  // as well as two separate base task sequences
  private static final int PLACEHOLDER_INDEX = 3;

  private static final int PLACEHOLDER_INDEX_RF1 = 1;

  private static final List<TaskType> TASK_SEQUENCE =
      ImmutableList.of(
          TaskType.SetNodeState,
          TaskType.ModifyBlackList,
          TaskType.WaitForLeaderBlacklistCompletion,
          TaskType.WaitForEncryptionKeyInMemory,
          TaskType.ModifyBlackList,
          TaskType.SetNodeState);

  private static final List<TaskType> TASK_SEQUENCE_RF1 =
      ImmutableList.of(
          TaskType.SetNodeState, TaskType.WaitForEncryptionKeyInMemory, TaskType.SetNodeState);

  private static final List<TaskType> PROCESS_START_SEQ =
      ImmutableList.of(
          TaskType.AnsibleClusterServerCtl, TaskType.WaitForServer, TaskType.WaitForServerReady);

  private static final List<TaskType> RESIZE_VOLUME_SEQ =
      ImmutableList.of(TaskType.InstanceActions);

  private static final List<TaskType> UPDATE_INSTANCE_TYPE_SEQ =
      ImmutableList.of(TaskType.ChangeInstanceType, TaskType.UpdateNodeDetails);

  @InjectMocks private ResizeNode resizeNode;

  @Override
  @Before
  public void setUp() {
    super.setUp();
    resizeNode.setUserTaskUUID(UUID.randomUUID());
    defaultUniverse =
        Universe.saveDetails(
            defaultUniverse.universeUUID,
            universe -> {
              UniverseDefinitionTaskParams.UserIntent userIntent =
                  universe.getUniverseDetails().getPrimaryCluster().userIntent;
              userIntent.deviceInfo = new DeviceInfo();
              userIntent.deviceInfo.numVolumes = 1;
              userIntent.deviceInfo.volumeSize = DEFAULT_VOLUME_SIZE;
              userIntent.instanceType = DEFAULT_INSTANCE_TYPE;
            });

    try {
      when(mockYBClient.getClientWithConfig(any())).thenReturn(mockClient);
    } catch (Exception ignored) {
    }
  }

  @Override
  protected PlacementInfo createPlacementInfo() {
    PlacementInfo placementInfo = new PlacementInfo();
    PlacementInfoUtil.addPlacementZone(az1.uuid, placementInfo, 1, 1, false);
    PlacementInfoUtil.addPlacementZone(az2.uuid, placementInfo, 1, 1, true);
    PlacementInfoUtil.addPlacementZone(az3.uuid, placementInfo, 1, 2, false);
    return placementInfo;
  }

  @Test
  public void testNonRollingUpgradeFails() {
    ResizeNodeParams taskParams = createResizeParams();
    taskParams.upgradeOption = UpgradeTaskParams.UpgradeOption.NON_ROLLING_UPGRADE;
    taskParams.clusters = defaultUniverse.getUniverseDetails().clusters;
    TaskInfo taskInfo = submitTask(taskParams);
    assertEquals(Failure, taskInfo.getTaskState());
    verifyNoMoreInteractions(mockNodeManager);
  }

  @Test
  public void testNonRestartUpgradeFails() {
    ResizeNodeParams taskParams = createResizeParams();
    taskParams.upgradeOption = UpgradeTaskParams.UpgradeOption.NON_RESTART_UPGRADE;
    taskParams.clusters = defaultUniverse.getUniverseDetails().clusters;
    TaskInfo taskInfo = submitTask(taskParams);
    assertEquals(Failure, taskInfo.getTaskState());
    verifyNoMoreInteractions(mockNodeManager);
  }

  @Test
  public void testChangingVolume() {
    ResizeNodeParams taskParams = createResizeParams();
    taskParams.clusters = defaultUniverse.getUniverseDetails().clusters;
    taskParams.getPrimaryCluster().userIntent.deviceInfo.volumeSize = NEW_VOLUME_SIZE;
    TaskInfo taskInfo = submitTask(taskParams);
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    assertTasksSequence(subTasks, true, false);
    assertEquals(Success, taskInfo.getTaskState());
    assertUniverseData(true, false);
  }

  @Test
  public void testChangingBoth() {
    ResizeNodeParams taskParams = createResizeParams();
    taskParams.clusters = defaultUniverse.getUniverseDetails().clusters;
    taskParams.getPrimaryCluster().userIntent.deviceInfo.volumeSize = NEW_VOLUME_SIZE;
    taskParams.getPrimaryCluster().userIntent.instanceType = NEW_INSTANCE_TYPE;
    TaskInfo taskInfo = submitTask(taskParams);
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    assertTasksSequence(subTasks, true, true);
    assertEquals(Success, taskInfo.getTaskState());
    assertUniverseData(true, true);
  }

  @Test
  public void testChangingOnlyInstance() {
    ResizeNodeParams taskParams = createResizeParams();
    taskParams.clusters = defaultUniverse.getUniverseDetails().clusters;
    taskParams.getPrimaryCluster().userIntent.instanceType = NEW_INSTANCE_TYPE;
    TaskInfo taskInfo = submitTask(taskParams);
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    assertTasksSequence(subTasks, false, true);
    assertEquals(Success, taskInfo.getTaskState());
    assertUniverseData(false, true);
  }

  @Test
  public void testNoWaitForMasterLeaderForRF1() {
    defaultUniverse =
        Universe.saveDetails(
            defaultUniverse.universeUUID,
            univ -> {
              univ.getUniverseDetails().getPrimaryCluster().userIntent.replicationFactor = 1;
            });
    ResizeNodeParams taskParams = createResizeParams();
    taskParams.clusters = defaultUniverse.getUniverseDetails().clusters;
    taskParams.getPrimaryCluster().userIntent.deviceInfo.volumeSize = NEW_VOLUME_SIZE;
    TaskInfo taskInfo = submitTask(taskParams);
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    assertTasksSequence(0, subTasks, true, false, false, true);
    assertEquals(Success, taskInfo.getTaskState());
    assertUniverseData(true, false);
  }

  @Test
  public void testChangingInstanceWithReadonlyReplica() {
    UniverseDefinitionTaskParams.UserIntent curIntent =
        defaultUniverse.getUniverseDetails().getPrimaryCluster().userIntent;
    UniverseDefinitionTaskParams.UserIntent userIntent =
        new UniverseDefinitionTaskParams.UserIntent();
    userIntent.numNodes = 3;
    userIntent.ybSoftwareVersion = curIntent.ybSoftwareVersion;
    userIntent.accessKeyCode = curIntent.accessKeyCode;
    userIntent.regionList = ImmutableList.of(region.uuid);
    userIntent.deviceInfo = new DeviceInfo();
    userIntent.deviceInfo.numVolumes = 1;
    userIntent.deviceInfo.volumeSize = DEFAULT_VOLUME_SIZE;
    userIntent.instanceType = DEFAULT_INSTANCE_TYPE;
    PlacementInfo pi = new PlacementInfo();
    PlacementInfoUtil.addPlacementZone(az1.uuid, pi, 1, 1, false);
    PlacementInfoUtil.addPlacementZone(az2.uuid, pi, 1, 1, false);
    PlacementInfoUtil.addPlacementZone(az3.uuid, pi, 1, 1, true);

    defaultUniverse =
        Universe.saveDetails(
            defaultUniverse.universeUUID,
            ApiUtils.mockUniverseUpdaterWithReadReplica(userIntent, pi));

    ResizeNodeParams taskParams = createResizeParams();
    taskParams.clusters =
        Collections.singletonList(defaultUniverse.getUniverseDetails().getPrimaryCluster());
    taskParams.getPrimaryCluster().userIntent.deviceInfo.volumeSize = NEW_VOLUME_SIZE;
    taskParams.getPrimaryCluster().userIntent.instanceType = NEW_INSTANCE_TYPE;
    TaskInfo taskInfo = submitTask(taskParams);
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    // it checks only primary nodes are changed
    assertTasksSequence(subTasks, true, true);
    assertEquals(Success, taskInfo.getTaskState());
    assertUniverseData(true, true, false);
  }

  @Test
  public void testRemountDrives() {
    AtomicReference<String> nodeName = new AtomicReference<>();
    defaultUniverse =
        Universe.saveDetails(
            defaultUniverse.universeUUID,
            univ -> {
              NodeDetails node = univ.getUniverseDetails().nodeDetailsSet.iterator().next();
              node.disksAreMountedByUUID = false;
              nodeName.set(node.getNodeName());
            });
    ResizeNodeParams taskParams = createResizeParams();
    taskParams.clusters = defaultUniverse.getUniverseDetails().clusters;
    taskParams.getPrimaryCluster().userIntent.deviceInfo.volumeSize = NEW_VOLUME_SIZE;
    taskParams.getPrimaryCluster().userIntent.instanceType = NEW_INSTANCE_TYPE;
    TaskInfo taskInfo = submitTask(taskParams);
    List<TaskInfo> subTasks = new ArrayList<>(taskInfo.getSubTasks());
    List<TaskInfo> updateMounts =
        subTasks
            .stream()
            .filter(t -> t.getTaskType() == TaskType.UpdateMountedDisks)
            .collect(Collectors.toList());

    assertEquals(1, updateMounts.size());
    assertEquals(nodeName.get(), updateMounts.get(0).getTaskDetails().get("nodeName").textValue());
    assertEquals(0, updateMounts.get(0).getPosition());
    assertTasksSequence(1, subTasks, true, true, true, false);
    assertEquals(Success, taskInfo.getTaskState());
    assertUniverseData(true, true);
  }

  private void assertUniverseData(boolean increaseVolume, boolean changeInstance) {
    assertUniverseData(increaseVolume, changeInstance, false);
  }

  private void assertUniverseData(
      boolean increaseVolume, boolean changeInstance, boolean readonlyChanged) {
    int volumeSize = increaseVolume ? NEW_VOLUME_SIZE : DEFAULT_VOLUME_SIZE;
    String instanceType = changeInstance ? NEW_INSTANCE_TYPE : DEFAULT_INSTANCE_TYPE;
    Universe universe = Universe.getOrBadRequest(defaultUniverse.universeUUID);
    UniverseDefinitionTaskParams.UserIntent newIntent =
        universe.getUniverseDetails().getPrimaryCluster().userIntent;
    assertEquals(volumeSize, newIntent.deviceInfo.volumeSize.intValue());
    assertEquals(instanceType, newIntent.instanceType);
    if (!universe.getUniverseDetails().getReadOnlyClusters().isEmpty()) {
      UniverseDefinitionTaskParams.UserIntent readonlyIntent =
          universe.getUniverseDetails().getReadOnlyClusters().get(0).userIntent;
      if (readonlyChanged) {
        assertEquals(volumeSize, readonlyIntent.deviceInfo.volumeSize.intValue());
        assertEquals(instanceType, readonlyIntent.instanceType);
      } else {
        assertEquals(DEFAULT_VOLUME_SIZE, readonlyIntent.deviceInfo.volumeSize.intValue());
        assertEquals(DEFAULT_INSTANCE_TYPE, readonlyIntent.instanceType);
      }
    }
  }

  private void assertTasksSequence(
      List<TaskInfo> subTasks, boolean increaseVolume, boolean changeInstance) {
    assertTasksSequence(0, subTasks, increaseVolume, changeInstance, true, false);
  }

  private void assertTasksSequence(
      int startPosition,
      List<TaskInfo> subTasks,
      boolean increaseVolume,
      boolean changeInstance,
      boolean waitForMasterLeader,
      boolean is_rf1) {
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));

    assertEquals(subTasks.size(), subTasksByPosition.size());
    int position = startPosition;
    assertTaskType(subTasksByPosition.get(position++), TaskType.ModifyBlackList);

    position =
        assertTasksSequence(
            subTasksByPosition,
            EITHER,
            position,
            increaseVolume,
            changeInstance,
            waitForMasterLeader,
            is_rf1);
    position =
        assertTasksSequence(
            subTasksByPosition, TSERVER, position, increaseVolume, changeInstance, false, is_rf1);
    assertTaskType(subTasksByPosition.get(position++), TaskType.PersistResizeNode);
    assertTaskType(subTasksByPosition.get(position++), TaskType.UniverseUpdateSucceeded);
    assertEquals(position, subTasks.size() - 1);
  }

  private int assertTasksSequence(
      Map<Integer, List<TaskInfo>> subTasksByPosition,
      ServerType serverType,
      int position,
      boolean increaseVolume,
      boolean changeInstance,
      boolean waitForMasterLeader,
      boolean is_rf1) {
    List<Integer> nodeIndexes =
        serverType == EITHER ? Arrays.asList(1, 3, 2) : Collections.singletonList(4);

    for (Integer nodeIndex : nodeIndexes) {
      String nodeName = String.format("host-n%d", nodeIndex);
      Map<Integer, Map<String, Object>> paramsForTask = new HashMap<>();
      List<TaskType> taskTypesSequence =
          is_rf1 ? new ArrayList<>(TASK_SEQUENCE_RF1) : new ArrayList<>(TASK_SEQUENCE);
      createTasksTypesForNode(
          serverType != EITHER,
          increaseVolume,
          changeInstance,
          taskTypesSequence,
          paramsForTask,
          waitForMasterLeader,
          is_rf1);

      int idx = 0;
      log.debug(nodeName + " :" + taskTypesSequence);
      log.debug(
          "current:"
              + IntStream.range(position, position + taskTypesSequence.size())
                  .mapToObj(p -> subTasksByPosition.get(p).get(0).getTaskType())
                  .collect(Collectors.toList()));

      for (TaskType expectedTaskType : taskTypesSequence) {
        List<TaskInfo> tasks = subTasksByPosition.get(position++);
        TaskType taskType = tasks.get(0).getTaskType();
        assertEquals(1, tasks.size());
        assertEquals(
            String.format("Host %s at %d positon", nodeName, idx), expectedTaskType, taskType);
        if (!NON_NODE_TASKS.contains(taskType)) {
          Map<String, Object> assertValues =
              new HashMap<>(ImmutableMap.of("nodeName", nodeName, "nodeCount", 1));
          assertValues.putAll(paramsForTask.getOrDefault(idx, Collections.emptyMap()));
          log.debug("checking " + tasks.get(0).getTaskType());
          assertNodeSubTask(tasks, assertValues);
        }
        idx++;
      }
    }
    return position;
  }

  private void createTasksTypesForNode(
      boolean onlyTserver,
      boolean increaseVolume,
      boolean changeInstance,
      List<TaskType> taskTypesSequence,
      Map<Integer, Map<String, Object>> paramsForTask,
      boolean waitForMasterLeader,
      boolean is_rf1) {
    List<TaskType> nodeUpgradeTasks = new ArrayList<>();
    if (increaseVolume) {
      nodeUpgradeTasks.addAll(RESIZE_VOLUME_SEQ);
    }
    if (changeInstance) {
      nodeUpgradeTasks.addAll(UPDATE_INSTANCE_TYPE_SEQ);
    }
    List<UniverseDefinitionTaskBase.ServerType> processTypes =
        onlyTserver ? ImmutableList.of(TSERVER) : ImmutableList.of(MASTER, TSERVER);

    int index = is_rf1 ? PLACEHOLDER_INDEX_RF1 : PLACEHOLDER_INDEX;
    for (ServerType processType : processTypes) {
      paramsForTask.put(
          index, ImmutableMap.of("process", processType.name().toLowerCase(), "command", "stop"));
      taskTypesSequence.add(index++, TaskType.AnsibleClusterServerCtl);
      if (processType == MASTER && waitForMasterLeader) {
        taskTypesSequence.add(index++, TaskType.WaitForMasterLeader);
        taskTypesSequence.add(index++, TaskType.ChangeMasterConfig);
        paramsForTask.put(index, ImmutableMap.of("opType", "RemoveMaster"));
      }
    }
    // node upgrade tasks
    taskTypesSequence.addAll(index, nodeUpgradeTasks);
    index += nodeUpgradeTasks.size();

    for (ServerType processType : processTypes) {
      List<TaskType> startSequence = new ArrayList<>(PROCESS_START_SEQ);
      if (processType == MASTER && waitForMasterLeader) {
        startSequence.add(2, TaskType.ChangeMasterConfig);
      }
      for (TaskType taskType : startSequence) {
        if (taskType == TaskType.AnsibleClusterServerCtl) {
          paramsForTask.put(
              index,
              ImmutableMap.of("process", processType.name().toLowerCase(), "command", "start"));
        } else if (taskType == TaskType.ChangeMasterConfig) {
          paramsForTask.put(index, ImmutableMap.of("opType", "AddMaster"));
        } else {
          paramsForTask.put(index, ImmutableMap.of("serverType", processType.name()));
        }
        taskTypesSequence.add(index++, taskType);
      }
    }
    index = is_rf1 ? index + 1 : index + 2;
    for (ServerType processType : processTypes) {
      taskTypesSequence.add(index++, TaskType.WaitForFollowerLag);
    }
  }

  private TaskInfo submitTask(ResizeNodeParams requestParams) {
    return submitTask(requestParams, TaskType.ResizeNode, commissioner, -1);
  }

  private ResizeNodeParams createResizeParams() {
    ResizeNodeParams taskParams =
        new ResizeNodeParams() {
          @Override
          protected boolean isSkipInstanceChecking() {
            return true;
          }
        };
    taskParams.universeUUID = defaultUniverse.universeUUID;
    return taskParams;
  }
}
