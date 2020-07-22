// Copyright 2020 The Measurement System Authors
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

package org.wfanet.measurement.kingdom

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.wfanet.measurement.common.AdaptiveThrottler
import org.wfanet.measurement.common.Flags
import org.wfanet.measurement.common.doubleFlag
import org.wfanet.measurement.common.durationFlag
import org.wfanet.measurement.common.intFlag
import org.wfanet.measurement.common.stringFlag
import org.wfanet.measurement.internal.kingdom.ReportConfigScheduleStorageGrpcKt.ReportConfigScheduleStorageCoroutineStub
import org.wfanet.measurement.internal.kingdom.ReportConfigStorageGrpcKt.ReportConfigStorageCoroutineStub
import org.wfanet.measurement.internal.kingdom.ReportStorageGrpcKt.ReportStorageCoroutineStub
import org.wfanet.measurement.internal.kingdom.RequisitionStorageGrpcKt.RequisitionStorageCoroutineStub

fun main(args: Array<String>) = runBlocking<Unit> {
  val maxParallelism = intFlag("max-parallelism", 32)
  val internalServicesTarget = stringFlag("internal-services-target", "")
  val overloadFactor = doubleFlag("throttler-overload-factor", 1.2)
  val timeHorizon = durationFlag("throttler-time-horizon", Duration.ofMinutes(2))
  val pollDelay = durationFlag("throttler-poll-delay", Duration.ofMillis(1))

  Flags.parse(args.asIterable())

  val channel: ManagedChannel =
    ManagedChannelBuilder
      .forTarget(internalServicesTarget.value)
      .usePlaintext()
      .build()

  val throttler = AdaptiveThrottler(
    overloadFactor.value,
    Clock.systemUTC(),
    timeHorizon.value,
    pollDelay.value
  )

  val reportStarterClient = ReportStarterClientImpl(
    ReportConfigStorageCoroutineStub(channel),
    ReportConfigScheduleStorageCoroutineStub(channel),
    ReportStorageCoroutineStub(channel),
    RequisitionStorageCoroutineStub(channel)
  )

  val reportStarter = ReportStarter(
    throttler,
    maxParallelism.value,
    reportStarterClient
  )

  // We just launch each of the tasks that we want to do in parallel. They each run indefinitely.
  // TODO: move to separate binaries.
  launch { reportStarter.createReports() }
  launch { reportStarter.createRequisitions() }
  launch { reportStarter.startReports() }
}
