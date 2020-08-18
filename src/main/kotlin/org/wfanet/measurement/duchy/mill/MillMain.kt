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

package org.wfanet.measurement.duchy.mill

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.runBlocking
import org.wfanet.measurement.common.AdaptiveThrottler
import org.wfanet.measurement.common.addChannelShutdownHooks
import org.wfanet.measurement.common.commandLineMain
import org.wfanet.measurement.db.duchy.computation.gcp.newLiquidLegionsSketchAggregationGcpComputationStorageClients
import org.wfanet.measurement.db.gcp.GoogleCloudStorageFromFlags
import org.wfanet.measurement.internal.duchy.ComputationControlServiceGrpcKt
import picocli.CommandLine

@CommandLine.Command(
  name = "mill_main ",
  mixinStandardHelpOptions = true,
  showDefaultValues = true
)
private fun run(
  @CommandLine.Mixin millFlags: MillFlags,
  @CommandLine.Mixin throttlerFlags: AdaptiveThrottler.Flags,
  @CommandLine.Mixin cloudStorageFlags: GoogleCloudStorageFromFlags.Flags
) {
  // TODO: Expand flags and configuration to work on other cloud environments when available.
  val cloudStorageFromFlags = GoogleCloudStorageFromFlags(cloudStorageFlags)

  val channel: ManagedChannel =
    ManagedChannelBuilder
      .forTarget(millFlags.computationStorageServiceTarget)
      .usePlaintext()
      .build()
  val storageClients = newLiquidLegionsSketchAggregationGcpComputationStorageClients(
    duchyName = millFlags.nameOfDuchy,
    // TODO: Pass public keys of all duchies to the computation manager
    duchyPublicKeys = mapOf(),
    googleCloudStorageOptions = cloudStorageFromFlags.cloudStorageOptions,
    storageBucket = cloudStorageFromFlags.bucket,
    computationStorageServiceChannel = channel
  )

  val channelOne =
    ManagedChannelBuilder.forTarget(millFlags.otherDuchyComputationControlServiceOne)
      .usePlaintext()
      .build()
  val channelTwo =
    ManagedChannelBuilder.forTarget(millFlags.otherDuchyComputationControlServiceTwo)
      .usePlaintext()
      .build()
  addChannelShutdownHooks(Runtime.getRuntime(), millFlags.channelShutdownTimeout, channelOne)
  addChannelShutdownHooks(Runtime.getRuntime(), millFlags.channelShutdownTimeout, channelTwo)

  val clientOne = ComputationControlServiceGrpcKt.ComputationControlServiceCoroutineStub(channelOne)
  val clientTwo = ComputationControlServiceGrpcKt.ComputationControlServiceCoroutineStub(channelTwo)
  val clientMap =
    mapOf(millFlags.otherDuchyNameOne to clientOne, millFlags.otherDuchyNameTwo to clientTwo)

  val mill = LiquidLegionsMill(
    millFlags.millId, storageClients, clientMap, AdaptiveThrottler(throttlerFlags)
  )

  runBlocking { mill.processComputationQueue() }
}

fun main(args: Array<String>) = commandLineMain(::run, args)