/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.thrift.CoordinatorService;
import com.facebook.buck.distributed.thrift.GetWorkRequest;
import com.facebook.buck.distributed.thrift.GetWorkResponse;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.log.Logger;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Handles Coordinator requests while the build is actively running. */
public class ActiveCoordinatorService implements CoordinatorService.Iface {

  private static final Logger LOG = Logger.get(ActiveCoordinatorService.class);

  private final MinionWorkloadAllocator allocator;
  private final CompletableFuture<Integer> exitCodeFuture;
  private final DistBuildTraceTracker chromeTraceTracker;
  private final BuildRuleFinishedPublisher buildRuleFinishedPublisher;

  public ActiveCoordinatorService(
      MinionWorkloadAllocator allocator,
      CompletableFuture<Integer> exitCodeFuture,
      DistBuildTraceTracker chromeTraceTracker,
      BuildRuleFinishedPublisher buildRuleFinishedPublisher) {
    this.allocator = allocator;
    this.exitCodeFuture = exitCodeFuture;
    this.chromeTraceTracker = chromeTraceTracker;
    this.buildRuleFinishedPublisher = buildRuleFinishedPublisher;
  }

  @Override
  public GetWorkResponse getWork(GetWorkRequest request) {
    // Create the response with some defaults
    GetWorkResponse response = new GetWorkResponse();
    response.setContinueBuilding(true);
    response.setWorkUnits(new ArrayList<>());

    buildRuleFinishedPublisher.createBuildRuleCompletionEvents(
        ImmutableList.copyOf(request.getFinishedTargets()));

    if (exitCodeFuture.isDone()) {
      // Tell any remaining minions that the build is finished and that they should shutdown.
      // Note: we cannot assume that when exitCodeFuture was set the first time the
      // coordinator server will shutdown immediately.
      response.setContinueBuilding(false);
      return response;
    }

    // If the minion died, then kill the whole build.
    if (request.getLastExitCode() != 0) {
      LOG.error(
          String.format(
              "Got non zero exit code in GetWorkRequest from minion [%s]. Exit code [%s]",
              request.getMinionId(), request.getLastExitCode()));
      exitCodeFuture.complete(request.getLastExitCode());
      response.setContinueBuilding(false);
      return response;
    }

    List<WorkUnit> newWorkUnitsForMinion =
        allocator.dequeueZeroDependencyNodes(
            request.getMinionId(), request.getFinishedTargets(), request.getMaxWorkUnitsToFetch());

    chromeTraceTracker.updateWork(
        request.getMinionId(), request.getFinishedTargets(), newWorkUnitsForMinion);

    // If the build is already finished (or just finished with this update, then signal this to
    // the minion.
    if (allocator.isBuildFinished()) {
      exitCodeFuture.complete(0);
      LOG.info(
          String.format(
              "Minion [%s] is being told to exit because the build has finished.",
              request.minionId));
      response.setContinueBuilding(false);
    } else {
      response.setWorkUnits(newWorkUnitsForMinion);
    }

    return response;
  }
}
