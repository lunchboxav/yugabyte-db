// Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
// This source code is licensed under the BSD-style license found in the
// LICENSE file in the root directory of this source tree. An additional grant
// of patent rights can be found in the PATENTS file in the same directory.
// Copyright (c) 2013 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file. See the AUTHORS file for names of contributors.
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

#ifndef YB_ROCKSDB_COMPACTION_FILTER_H
#define YB_ROCKSDB_COMPACTION_FILTER_H

#include <memory>
#include <string>
#include <vector>

#include "yb/util/slice.h"
#include "yb/rocksdb/metadata.h"
#include "yb/rocksdb/db/version_edit.h"

namespace rocksdb {

class SliceTransform;

// Context information of a compaction run
struct CompactionFilterContext {
  // Does this compaction run include all data files
  bool is_full_compaction;
  // Is this compaction requested by the client (true),
  // or is it occurring as an automatic compaction process
  bool is_manual_compaction;
};

// CompactionFilter allows an application to modify/delete a key-value at
// the time of compaction.

YB_DEFINE_ENUM(FilterDecision, (kKeep)(kDiscard));

class CompactionFilter {
 public:
  // Context information of a compaction run
  struct Context {
    // Does this compaction run include all data files
    bool is_full_compaction;
    // Is this compaction requested by the client (true),
    // or is it occurring as an automatic compaction process
    bool is_manual_compaction;
    // Which column family this compaction is for.
    uint32_t column_family_id;
  };

  virtual ~CompactionFilter() {}

  // The compaction process invokes this
  // method for kv that is being compacted. A return value
  // of false indicates that the kv should be preserved in the
  // output of this compaction run and a return value of true
  // indicates that this key-value should be removed from the
  // output of the compaction.  The application can inspect
  // the existing value of the key and make decision based on it.
  //
  // Key-Values that are results of merge operation during compaction are not
  // passed into this function. Currently, when you have a mix of Put()s and
  // Merge()s on a same key, we only guarantee to process the merge operands
  // through the compaction filters. Put()s might be processed, or might not.
  //
  // When the value is to be preserved, the application has the option
  // to modify the existing_value and pass it back through new_value.
  // value_changed needs to be set to true in this case.
  //
  // If you use snapshot feature of RocksDB (i.e. call GetSnapshot() API on a
  // DB* object), CompactionFilter might not be very useful for you. Due to
  // guarantees we need to maintain, compaction process will not call Filter()
  // on any keys that were written before the latest snapshot. In other words,
  // compaction will only call Filter() on keys written after your most recent
  // call to GetSnapshot(). In most cases, Filter() will not be called very
  // often. This is something we're fixing. See the discussion at:
  // https://www.facebook.com/groups/mysqlonrocksdb/permalink/999723240091865/
  //
  // If multithreaded compaction is being used *and* a single CompactionFilter
  // instance was supplied via Options::compaction_filter, this method may be
  // called from different threads concurrently.  The application must ensure
  // that the call is thread-safe.
  //
  // If the CompactionFilter was created by a factory, then it will only ever
  // be used by a single thread that is doing the compaction run, and this
  // call does not need to be thread-safe.  However, multiple filters may be
  // in existence and operating concurrently.
  //
  // The last paragraph is not true if you set max_subcompactions to more than
  // 1. In that case, subcompaction from multiple threads may call a single
  // CompactionFilter concurrently.
  virtual FilterDecision Filter(int level,
                                const Slice& key,
                                const Slice& existing_value,
                                std::string* new_value,
                                bool* value_changed) = 0;

  // The compaction process invokes this method on every merge operand. If this
  // method returns true, the merge operand will be ignored and not written out
  // in the compaction output
  //
  // Note: If you are using a TransactionDB, it is not recommended to implement
  // FilterMergeOperand().  If a Merge operation is filtered out, TransactionDB
  // may not realize there is a write conflict and may allow a Transaction to
  // Commit that should have failed.  Instead, it is better to implement any
  // Merge filtering inside the MergeOperator.
  virtual bool FilterMergeOperand(int level, const Slice& key,
                                  const Slice& operand) const {
    return false;
  }

  virtual void CompactionFinished() {
  }

  // By default, compaction will only call Filter() on keys written after the
  // most recent call to GetSnapshot(). However, if the compaction filter
  // overrides IgnoreSnapshots to make it return false, the compaction filter
  // will be called even if the keys were written before the last snapshot.
  // This behavior is to be used only when we want to delete a set of keys
  // irrespective of snapshots. In particular, care should be taken
  // to understand that the values of thesekeys will change even if we are
  // using a snapshot.
  virtual bool IgnoreSnapshots() const { return false; }

  // Gives the compaction filter an opportunity to return a "user frontier" that will be used to
  // update the frontier stored in the version edit metadata when the compaction result is
  // installed.
  //
  // As a concrete use case, we use this to pass the history cutoff timestamp from the DocDB
  // compaction filter into the version edit metadata. See DocDBCompactionFilter.
  virtual UserFrontierPtr GetLargestUserFrontier() const { return nullptr; }

  // Returns a name that identifies this compaction filter.
  // The name will be printed to LOG file on start up for diagnosis.
  virtual const char* Name() const = 0;

  // Returns a list of the ranges which should be considered "live" on this tablet. Returns an empty
  // list if the whole key range of the tablet should be considered live. Returned ranges are
  // represented as pairs of Slices denoting the beginning and end of the range in user space.
  virtual std::vector<std::pair<Slice, Slice>> GetLiveRanges() const { return {}; }
};

// Each compaction will create a new CompactionFilter allowing the
// application to know about different compactions
class CompactionFilterFactory {
 public:
  virtual ~CompactionFilterFactory() { }

  virtual std::unique_ptr<CompactionFilter> CreateCompactionFilter(
      const CompactionFilter::Context& context) = 0;

  // Returns a name that identifies this compaction filter factory.
  virtual const char* Name() const = 0;
};

// Makes a decision about whether or not to exclude a file in a compaction based on whether
// or not the file has expired.  If expired, file will be removed at the end of compaction.
class CompactionFileFilter {
 public:
  virtual ~CompactionFileFilter() = default;

  // Determines whether to keep or discard a file based on the file's metadata.
  virtual FilterDecision Filter(const FileMetaData* file) = 0;

  // Returns a name that identifies this compaction filter.
  // The name will be printed to LOG file on start up for diagnosis.
  virtual const char* Name() const = 0;
};

// Each compaction will create a new CompactionFileFilter allowing each
// filter to have unique state when making expiration decisions.
class CompactionFileFilterFactory {
 public:
  virtual ~CompactionFileFilterFactory() { }

  // Creates a unique pointer to a new CompactionFileFilter.
  virtual std::unique_ptr<CompactionFileFilter> CreateCompactionFileFilter(
      const std::vector<FileMetaData*>& input_files) = 0;

  // Returns a name that identifies this compaction filter factory.
  virtual const char* Name() const = 0;
};

}  // namespace rocksdb

#endif // YB_ROCKSDB_COMPACTION_FILTER_H
