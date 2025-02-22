// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// The following only applies to changes made to this file as part of YugaByte development.
//
// Portions Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
#ifndef YB_CLIENT_CLIENT_INTERNAL_H
#define YB_CLIENT_CLIENT_INTERNAL_H

#include <functional>
#include <set>
#include <string>
#include <unordered_set>
#include <vector>

#include "yb/client/client.h"

#include "yb/common/common_net.pb.h"
#include "yb/common/entity_ids.h"
#include "yb/common/index.h"
#include "yb/common/wire_protocol.h"

#include "yb/master/master_fwd.h"
#include "yb/master/master_admin.fwd.h"

#include "yb/rpc/rpc_fwd.h"
#include "yb/rpc/rpc.h"

#include "yb/server/server_base_options.h"

#include "yb/util/atomic.h"
#include "yb/util/locks.h"
#include "yb/util/monotime.h"
#include "yb/util/net/net_util.h"
#include "yb/util/threadpool.h"

namespace yb {

class HostPort;

namespace client {

YB_STRONGLY_TYPED_BOOL(Retry);

class YBClient::Data {
 public:
  Data();
  ~Data();

  // Selects a TS replica from the given RemoteTablet subject
  // to liveness and the provided selection criteria and blacklist.
  //
  // If no appropriate replica can be found, a non-OK status is returned and 'ts' is untouched.
  //
  // The 'candidates' return parameter indicates tservers that are live and meet the selection
  // criteria, but are possibly filtered by the blacklist. This is useful for implementing
  // retry logic.
  CHECKED_STATUS GetTabletServer(YBClient* client,
                                 const scoped_refptr<internal::RemoteTablet>& rt,
                                 ReplicaSelection selection,
                                 const std::set<std::string>& blacklist,
                                 std::vector<internal::RemoteTabletServer*>* candidates,
                                 internal::RemoteTabletServer** ts);

  CHECKED_STATUS AlterNamespace(YBClient* client,
                                const master::AlterNamespaceRequestPB& req,
                                CoarseTimePoint deadline);

  CHECKED_STATUS IsCreateNamespaceInProgress(YBClient* client,
                                const std::string& namespace_name,
                                const boost::optional<YQLDatabase>& database_type,
                                const std::string& namespace_id,
                                CoarseTimePoint deadline,
                                bool *create_in_progress);

  CHECKED_STATUS WaitForCreateNamespaceToFinish(YBClient* client,
                                const std::string& namespace_name,
                                const boost::optional<YQLDatabase>& database_type,
                                const std::string& namespace_id,
                                CoarseTimePoint deadline);

  CHECKED_STATUS IsDeleteNamespaceInProgress(YBClient* client,
                                             const std::string& namespace_name,
                                             const boost::optional<YQLDatabase>& database_type,
                                             const std::string& namespace_id,
                                             CoarseTimePoint deadline,
                                             bool *delete_in_progress);

  CHECKED_STATUS WaitForDeleteNamespaceToFinish(YBClient* client,
                                                const std::string& namespace_name,
                                                const boost::optional<YQLDatabase>& database_type,
                                                const std::string& namespace_id,
                                                CoarseTimePoint deadline);

  CHECKED_STATUS CreateTable(YBClient* client,
                             const master::CreateTableRequestPB& req,
                             const YBSchema& schema,
                             CoarseTimePoint deadline,
                             std::string* table_id);

  // Take one of table id or name.
  CHECKED_STATUS IsCreateTableInProgress(YBClient* client,
                                         const YBTableName& table_name,
                                         const std::string& table_id,
                                         CoarseTimePoint deadline,
                                         bool *create_in_progress);

  // Take one of table id or name.
  CHECKED_STATUS WaitForCreateTableToFinish(YBClient* client,
                                            const YBTableName& table_name,
                                            const std::string& table_id,
                                            CoarseTimePoint deadline);

  // Take one of table id or name.
  CHECKED_STATUS DeleteTable(YBClient* client,
                             const YBTableName& table_name,
                             const std::string& table_id,
                             bool is_index_table,
                             CoarseTimePoint deadline,
                             YBTableName* indexed_table_name,
                             bool wait = true);

  CHECKED_STATUS IsDeleteTableInProgress(YBClient* client,
                                         const std::string& table_id,
                                         CoarseTimePoint deadline,
                                         bool *delete_in_progress);

  CHECKED_STATUS WaitForDeleteTableToFinish(YBClient* client,
                                            const std::string& table_id,
                                            CoarseTimePoint deadline);

  CHECKED_STATUS TruncateTables(YBClient* client,
                                const std::vector<std::string>& table_ids,
                                CoarseTimePoint deadline,
                                bool wait = true);

  CHECKED_STATUS IsTruncateTableInProgress(YBClient* client,
                                           const std::string& table_id,
                                           CoarseTimePoint deadline,
                                           bool *truncate_in_progress);

  CHECKED_STATUS WaitForTruncateTableToFinish(YBClient* client,
                                              const std::string& table_id,
                                              CoarseTimePoint deadline);

  CHECKED_STATUS BackfillIndex(YBClient* client,
                               const YBTableName& table_name,
                               const TableId& table_id,
                               CoarseTimePoint deadline,
                               bool wait = true);
  CHECKED_STATUS IsBackfillIndexInProgress(YBClient* client,
                                           const TableId& table_id,
                                           const TableId& index_id,
                                           CoarseTimePoint deadline,
                                           bool* backfill_in_progress);
  CHECKED_STATUS WaitForBackfillIndexToFinish(YBClient* client,
                                              const TableId& table_id,
                                              const TableId& index_id,
                                              CoarseTimePoint deadline);

  CHECKED_STATUS AlterTable(YBClient* client,
                            const master::AlterTableRequestPB& req,
                            CoarseTimePoint deadline);

  // Take one of table id or name.
  CHECKED_STATUS IsAlterTableInProgress(YBClient* client,
                                        const YBTableName& table_name,
                                        string table_id,
                                        CoarseTimePoint deadline,
                                        bool *alter_in_progress);

  CHECKED_STATUS WaitForAlterTableToFinish(YBClient* client,
                                           const YBTableName& alter_name,
                                           string table_id,
                                           CoarseTimePoint deadline);

  CHECKED_STATUS FlushTables(YBClient* client,
                             const vector<YBTableName>& table_names,
                             bool add_indexes,
                             const CoarseTimePoint deadline,
                             const bool is_compaction);

  CHECKED_STATUS FlushTables(YBClient* client,
                             const vector<TableId>& table_ids,
                             bool add_indexes,
                             const CoarseTimePoint deadline,
                             const bool is_compaction);

  CHECKED_STATUS IsFlushTableInProgress(YBClient* client,
                                        const FlushRequestId& flush_id,
                                        const CoarseTimePoint deadline,
                                        bool *flush_in_progress);

  CHECKED_STATUS WaitForFlushTableToFinish(YBClient* client,
                                           const FlushRequestId& flush_id,
                                           const CoarseTimePoint deadline);

  CHECKED_STATUS GetTableSchema(YBClient* client,
                                const YBTableName& table_name,
                                CoarseTimePoint deadline,
                                YBTableInfo* info);
  CHECKED_STATUS GetTableSchema(YBClient* client,
                                const TableId& table_id,
                                CoarseTimePoint deadline,
                                YBTableInfo* info,
                                master::GetTableSchemaResponsePB* resp = nullptr);
  CHECKED_STATUS GetTableSchemaById(YBClient* client,
                                    const TableId& table_id,
                                    CoarseTimePoint deadline,
                                    std::shared_ptr<YBTableInfo> info,
                                    StatusCallback callback);
  CHECKED_STATUS GetTablegroupSchemaById(YBClient* client,
                                         const TablegroupId& parent_tablegroup_table_id,
                                         CoarseTimePoint deadline,
                                         std::shared_ptr<std::vector<YBTableInfo>> info,
                                         StatusCallback callback);
  CHECKED_STATUS GetColocatedTabletSchemaById(YBClient* client,
                                              const TableId& parent_colocated_table_id,
                                              CoarseTimePoint deadline,
                                              std::shared_ptr<std::vector<YBTableInfo>> info,
                                              StatusCallback callback);

  Result<IndexPermissions> GetIndexPermissions(
      YBClient* client,
      const TableId& table_id,
      const TableId& index_id,
      const CoarseTimePoint deadline);
  Result<IndexPermissions> GetIndexPermissions(
      YBClient* client,
      const YBTableName& table_name,
      const TableId& index_id,
      const CoarseTimePoint deadline);
  Result<IndexPermissions> WaitUntilIndexPermissionsAtLeast(
      YBClient* client,
      const TableId& table_id,
      const TableId& index_id,
      const IndexPermissions& target_index_permissions,
      const CoarseTimePoint deadline,
      const CoarseDuration max_wait = std::chrono::seconds(2));
  Result<IndexPermissions> WaitUntilIndexPermissionsAtLeast(
      YBClient* client,
      const YBTableName& table_name,
      const YBTableName& index_name,
      const IndexPermissions& target_index_permissions,
      const CoarseTimePoint deadline,
      const CoarseDuration max_wait = std::chrono::seconds(2));

  void CreateCDCStream(YBClient* client,
                       const TableId& table_id,
                       const std::unordered_map<std::string, std::string>& options,
                       CoarseTimePoint deadline,
                       CreateCDCStreamCallback callback);

  void DeleteCDCStream(YBClient* client,
                       const CDCStreamId& stream_id,
                       CoarseTimePoint deadline,
                       StatusCallback callback);

  void GetCDCDBStreamInfo(YBClient *client,
    const std::string &db_stream_id,
    std::shared_ptr<std::vector<pair<std::string, std::string>>> db_stream_info,
    CoarseTimePoint deadline,
    StdStatusCallback callback);

  void GetCDCStream(YBClient* client,
                    const CDCStreamId& stream_id,
                    std::shared_ptr<TableId> table_id,
                    std::shared_ptr<std::unordered_map<std::string, std::string>> options,
                    CoarseTimePoint deadline,
                    StdStatusCallback callback);

  void DeleteNotServingTablet(
      YBClient* client, const TabletId& tablet_id, CoarseTimePoint deadline,
      StdStatusCallback callback);

  void GetTableLocations(
      YBClient* client, const TableId& table_id, int32_t max_tablets,
      RequireTabletsRunning require_tablets_running, CoarseTimePoint deadline,
      GetTableLocationsCallback callback);

  CHECKED_STATUS InitLocalHostNames();

  bool IsLocalHostPort(const HostPort& hp) const;

  bool IsTabletServerLocal(const internal::RemoteTabletServer& rts) const;

  // Returns a non-failed replica of the specified tablet based on the provided selection criteria
  // and tablet server blacklist.
  //
  // In case a local tablet server was marked as failed because the tablet was not in the RUNNING
  // state, we will update the internal state of the local tablet server if the tablet is in the
  // RUNNING state.
  //
  // Returns NULL if there are no valid tablet servers.
  internal::RemoteTabletServer* SelectTServer(
      internal::RemoteTablet* rt,
      const ReplicaSelection selection,
      const std::set<std::string>& blacklist,
      std::vector<internal::RemoteTabletServer*>* candidates);

  // Sets 'master_proxy_' from the address specified by
  // 'leader_master_hostport_'.  Called by
  // GetLeaderMasterRpc::Finished() upon successful completion.
  //
  // See also: SetMasterServerProxyAsync.
  void LeaderMasterDetermined(const Status& status,
                              const HostPort& host_port);

  // Asynchronously sets 'master_proxy_' to the leader master by
  // cycling through servers listed in 'master_server_addrs_' until
  // one responds with a Raft configuration that contains the leader
  // master or 'deadline' expires.
  //
  // Invokes 'cb' with the appropriate status when finished.
  //
  // Works with both a distributed and non-distributed configuration.
  void SetMasterServerProxyAsync(CoarseTimePoint deadline,
                                 bool skip_resolution,
                                 bool wait_for_leader_election,
                                 const StdStatusCallback& cb);

  // Synchronous version of SetMasterServerProxyAsync method above.
  //
  // NOTE: since this uses a Synchronizer, this may not be invoked by
  // a method that's on a reactor thread.
  //
  // TODO (KUDU-492): Get rid of this method and re-factor the client
  // to lazily initialize 'master_proxy_'.
  CHECKED_STATUS SetMasterServerProxy(CoarseTimePoint deadline,
                                      bool skip_resolution = false,
                                      bool wait_for_leader_election = true);

  std::shared_ptr<master::MasterAdminProxy> master_admin_proxy() const;
  std::shared_ptr<master::MasterClientProxy> master_client_proxy() const;
  std::shared_ptr<master::MasterClusterProxy> master_cluster_proxy() const;
  std::shared_ptr<master::MasterDclProxy> master_dcl_proxy() const;
  std::shared_ptr<master::MasterDdlProxy> master_ddl_proxy() const;
  std::shared_ptr<master::MasterReplicationProxy> master_replication_proxy() const;

  HostPort leader_master_hostport() const;

  uint64_t GetLatestObservedHybridTime() const;

  void UpdateLatestObservedHybridTime(uint64_t hybrid_time);

  // API's to add/remove/set the master address list in the client
  CHECKED_STATUS SetMasterAddresses(const std::string& addresses);
  CHECKED_STATUS RemoveMasterAddress(const HostPort& addr);
  CHECKED_STATUS AddMasterAddress(const HostPort& addr);
  // This method reads the master address from the remote endpoint or a file depending on which is
  // specified, and re-initializes the 'master_server_addrs_' variable.
  CHECKED_STATUS ReinitializeMasterAddresses();

  // Set replication info for the cluster data. Last argument defaults to nullptr to auto-wrap in a
  // retry. It is otherwise used in a RetryFunc to indicate if to keep retrying or not, if we get a
  // version mismatch on setting the config.
  CHECKED_STATUS SetReplicationInfo(
      YBClient* client, const master::ReplicationInfoPB& replication_info, CoarseTimePoint deadline,
      bool* retry = nullptr);

  // Validate replication info as satisfiable for the cluster data.
  CHECKED_STATUS ValidateReplicationInfo(
        const master::ReplicationInfoPB& replication_info, CoarseTimePoint deadline);

  template <class ProxyClass, class ReqClass, class RespClass>
  using SyncLeaderMasterFunc = void (ProxyClass::*)(
      const ReqClass &req, RespClass *response, rpc::RpcController *controller,
      rpc::ResponseCallback callback) const;

  // Retry 'func' until either:
  //
  // 1) Methods succeeds on a leader master.
  // 2) Method fails for a reason that is not related to network
  //    errors, timeouts, or leadership issues.
  // 3) 'deadline' (if initialized) elapses.
  //
  // If 'num_attempts' is not NULL, it will be incremented on every
  // attempt (successful or not) to call 'func'.
  //
  // NOTE: 'rpc_timeout' is a per-call timeout, while 'deadline' is a
  // per operation deadline. If 'deadline' is not initialized, 'func' is
  // retried forever. If 'deadline' expires, 'func_name' is included in
  // the resulting Status.
  template <class ProxyClass, class ReqClass, class RespClass>
  CHECKED_STATUS SyncLeaderMasterRpc(
      CoarseTimePoint deadline, const ReqClass& req, RespClass* resp, const char* func_name,
      const SyncLeaderMasterFunc<ProxyClass, ReqClass, RespClass>& func, int* attempts = nullptr);

  template <class T, class... Args>
  rpc::RpcCommandPtr StartRpc(Args&&... args);

  bool IsMultiMaster();

  void StartShutdown();

  void CompleteShutdown();

  void DoSetMasterServerProxy(
      CoarseTimePoint deadline, bool skip_resolution, bool wait_for_leader_election);
  Result<server::MasterAddresses> ParseMasterAddresses(const Status& reinit_status);

  rpc::Messenger* messenger_ = nullptr;
  std::unique_ptr<rpc::Messenger> messenger_holder_;
  std::unique_ptr<rpc::ProxyCache> proxy_cache_;
  scoped_refptr<internal::MetaCache> meta_cache_;
  scoped_refptr<MetricEntity> metric_entity_;

  // Set of hostnames and IPs on the local host.
  // This is initialized at client startup.
  std::unordered_set<std::string> local_host_names_;

  // Flag name to fetch master addresses from flagfile.
  std::string master_address_flag_name_;
  // This vector holds the list of master server addresses. Note that each entry in this vector
  // can either be a single 'host:port' or a comma separated list of 'host1:port1,host2:port2,...'.
  std::vector<MasterAddressSource> master_address_sources_;
  // User specified master server addresses.
  std::vector<std::string> master_server_addrs_;
  // master_server_addrs_ + addresses from master_address_sources_.
  std::vector<std::string> full_master_server_addrs_;
  mutable simple_spinlock master_server_addrs_lock_;

  bool skip_master_flagfile_ = false;

  // If all masters are available but no leader is present on client init,
  // this flag determines if the client returns failure right away
  // or waits for a leader to be elected.
  bool wait_for_leader_election_on_init_ = true;

  MonoDelta default_admin_operation_timeout_;
  MonoDelta default_rpc_timeout_;

  // The host port of the leader master. This is set in
  // LeaderMasterDetermined, which is invoked as a callback by
  // SetMasterServerProxyAsync.
  HostPort leader_master_hostport_;

  // Proxy to the leader master.
  std::shared_ptr<master::MasterAdminProxy> master_admin_proxy_;
  std::shared_ptr<master::MasterClientProxy> master_client_proxy_;
  std::shared_ptr<master::MasterClusterProxy> master_cluster_proxy_;
  std::shared_ptr<master::MasterDclProxy> master_dcl_proxy_;
  std::shared_ptr<master::MasterDdlProxy> master_ddl_proxy_;
  std::shared_ptr<master::MasterReplicationProxy> master_replication_proxy_;

  // Ref-counted RPC instance: since 'SetMasterServerProxyAsync' call
  // is asynchronous, we need to hold a reference in this class
  // itself, as to avoid a "use-after-free" scenario.
  rpc::Rpcs rpcs_;
  rpc::Rpcs::Handle leader_master_rpc_;
  std::vector<StdStatusCallback> leader_master_callbacks_;

  // Protects 'leader_master_rpc_', 'leader_master_hostport_',
  // and master_proxy_
  //
  // See: YBClient::Data::SetMasterServerProxyAsync for a more
  // in-depth explanation of why this is needed and how it works.
  mutable simple_spinlock leader_master_lock_;

  AtomicInt<uint64_t> latest_observed_hybrid_time_;

  std::atomic<bool> closing_{false};

  std::atomic<int> running_sync_requests_{0};

  // Cloud info indicating placement information of client.
  CloudInfoPB cloud_info_pb_;

  // When the client is part of a CQL proxy, this denotes the uuid for the associated tserver to
  // aid in detecting local tservers.
  TabletServerId uuid_;

  bool use_threadpool_for_callbacks_;
  std::unique_ptr<ThreadPool> threadpool_;

  const ClientId id_;

  // Used to track requests that were sent to a particular tablet, so it could track different
  // RPCs related to the same write operation and reject duplicates.
  struct TabletRequests {
    RetryableRequestId request_id_seq = 0;
    std::set<RetryableRequestId> running_requests;
  };

  simple_spinlock tablet_requests_mutex_;
  std::unordered_map<TabletId, TabletRequests> tablet_requests_;

  std::array<std::atomic<int>, 2> tserver_count_cached_;

  // The proxy for the node local tablet server.
  std::shared_ptr<tserver::TabletServerForwardServiceProxy> node_local_forward_proxy_;

  // The host port of the node local tserver.
  HostPort node_local_tserver_host_port_;

 private:
  CHECKED_STATUS FlushTablesHelper(YBClient* client,
                                   const CoarseTimePoint deadline,
                                   const master::FlushTablesRequestPB& req);

  DISALLOW_COPY_AND_ASSIGN(Data);
};

// Retry helper, takes a function like:
//     CHECKED_STATUS funcName(const MonoTime& deadline, bool *retry, ...)
// The function should set the retry flag (default true) if the function should
// be retried again. On retry == false the return status of the function will be
// returned to the caller, otherwise a Status::Timeout() will be returned.
// If the deadline is already expired, no attempt will be made.
Status RetryFunc(
    CoarseTimePoint deadline,
    const std::string& retry_msg,
    const std::string& timeout_msg,
    const std::function<Status(CoarseTimePoint, bool*)>& func,
    const CoarseDuration max_wait = std::chrono::seconds(2));

// TODO(PgClient) Remove after removing YBTable from postgres.
CHECKED_STATUS CreateTableInfoFromTableSchemaResp(
    const master::GetTableSchemaResponsePB& resp, YBTableInfo* info);

} // namespace client
} // namespace yb

#endif  // YB_CLIENT_CLIENT_INTERNAL_H
