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

package org.wfanet.measurement.service.internal.duchy.computation.control

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.reduce
import org.wfanet.measurement.common.identity.DuchyIdentity
import org.wfanet.measurement.common.identity.duchyIdentityFromContext
import org.wfanet.measurement.db.duchy.computation.LiquidLegionsSketchAggregationComputationStorageClients
import org.wfanet.measurement.db.duchy.computation.singleOutputBlobMetadata
import org.wfanet.measurement.internal.LiquidLegionsSketchAggregationStage
import org.wfanet.measurement.internal.duchy.ComputationControlServiceGrpcKt.ComputationControlServiceCoroutineImplBase
import org.wfanet.measurement.internal.duchy.ComputationDetails.RoleInComputation
import org.wfanet.measurement.internal.duchy.ComputationToken
import org.wfanet.measurement.internal.duchy.HandleConcatenatedSketchRequest
import org.wfanet.measurement.internal.duchy.HandleConcatenatedSketchResponse
import org.wfanet.measurement.internal.duchy.HandleEncryptedFlagsAndCountsRequest
import org.wfanet.measurement.internal.duchy.HandleEncryptedFlagsAndCountsResponse
import org.wfanet.measurement.internal.duchy.HandleNoisedSketchRequest
import org.wfanet.measurement.internal.duchy.HandleNoisedSketchResponse
import org.wfanet.measurement.service.internal.duchy.computation.storage.toGetTokenRequest
import java.util.logging.Logger

class LiquidLegionsComputationControlServiceImpl(
  private val clients: LiquidLegionsSketchAggregationComputationStorageClients,
  private val duchyIdentityProvider: () -> DuchyIdentity = ::duchyIdentityFromContext
) : ComputationControlServiceCoroutineImplBase() {

  override suspend fun handleConcatenatedSketch(
    requests: Flow<HandleConcatenatedSketchRequest>
  ): HandleConcatenatedSketchResponse {
    val (id, sketch) =
      requests.map { it.computationId to it.partialSketch.toByteArray() }.appendAllByteArrays()
    logger.info("[id=$id]: Received blind position request.")
    val token = clients.computationStorageClient
      .getComputationToken(id.toGetTokenRequest())
      .token
    require(token.globalComputationId == id) {
      "Received HandleConcatenatedSketchRequest for unknown computation $id"
    }

    logger.info("[id=$id]: Saving concatenated sketch.")
    val tokenAfterWrite = clients.writeSingleOutputBlob(token, sketch)

    // The next stage to be worked depends upon the duchy's role in the computation.
    val nextStage = when (token.role) {
      RoleInComputation.PRIMARY ->
        LiquidLegionsSketchAggregationStage.TO_BLIND_POSITIONS_AND_JOIN_REGISTERS
      RoleInComputation.SECONDARY ->
        LiquidLegionsSketchAggregationStage.TO_BLIND_POSITIONS
      else -> error("Unknown role in computation ${token.role}")
    }
    logger.info("[id=$id]: transitioning to $nextStage")
    clients.transitionComputationToStage(
      computationToken = tokenAfterWrite,
      inputsToNextStage = listOf(tokenAfterWrite.singleOutputBlobMetadata().path),
      stage = nextStage
    )

    logger.info("[id=$id]: Saved sketch and transitioned stage to $nextStage")
    return HandleConcatenatedSketchResponse.getDefaultInstance() // Ack the request
  }

  override suspend fun handleEncryptedFlagsAndCounts(
    requests: Flow<HandleEncryptedFlagsAndCountsRequest>
  ): HandleEncryptedFlagsAndCountsResponse {
    val (id, bytes) =
      requests.map { it.computationId to it.partialData.toByteArray() }.appendAllByteArrays()
    logger.info("[id=$id]: Received decrypt flags and counts request.")
    val token = clients.computationStorageClient
      .getComputationToken(id.toGetTokenRequest())
      .token
    require(token.globalComputationId == id) {
      "Received HandleEncryptedFlagsAndCountsRequest for unknown computation $id"
    }

    logger.info("[id=$id]: Saving encrypted flags and counts.")
    val tokenAfterWrite = clients.writeSingleOutputBlob(token, bytes)

    // The next stage to be worked depends upon the duchy's role in the computation.
    val nextStage = when (token.role) {
      RoleInComputation.PRIMARY ->
        LiquidLegionsSketchAggregationStage.TO_DECRYPT_FLAG_COUNTS_AND_COMPUTE_METRICS
      RoleInComputation.SECONDARY ->
        LiquidLegionsSketchAggregationStage.TO_DECRYPT_FLAG_COUNTS
      else -> error("Unknown role in computation ${token.role}")
    }
    logger.info("[id=$id]: transitioning to $nextStage")
    clients.transitionComputationToStage(
      computationToken = tokenAfterWrite,
      inputsToNextStage = listOf(tokenAfterWrite.singleOutputBlobMetadata().path),
      stage = nextStage
    )

    logger.info("[id=$id]: Saved sketch and transitioned stage to $nextStage")
    return HandleEncryptedFlagsAndCountsResponse.getDefaultInstance() // Ack the request
  }

  override suspend fun handleNoisedSketch(
    requests: Flow<HandleNoisedSketchRequest>
  ): HandleNoisedSketchResponse {
    val (id, bytes) =
      requests.map { it.computationId to it.partialSketch.toByteArray() }.appendAllByteArrays()

    val sender = duchyIdentityProvider().id
    logger.info("[id=$id]: Received noised sketch request from $sender.")
    val token = clients.computationStorageClient
      .getComputationToken(id.toGetTokenRequest())
      .token
    require(token.globalComputationId == id) {
      "Received HandleNoisedSketchRequest for unknown computation $id"
    }
    require(token.role == RoleInComputation.PRIMARY) {
      "Duchy is not the primary server but received a sketch from $sender for $token"
    }

    logger.info("[id=$id]: Saving noised sketch from $sender.")
    val tokenAfterWrite = clients.writeReceivedNoisedSketch(token, bytes, sender)
    enqueueAppendSketchesOperationIfReceivedAllSketches(tokenAfterWrite)

    return HandleNoisedSketchResponse.getDefaultInstance() // Ack the request
  }

  private suspend fun enqueueAppendSketchesOperationIfReceivedAllSketches(
    token: ComputationToken
  ) {
    val id = token.globalComputationId
    val sketchesNotYetReceived = token.blobsList.count { it.path.isEmpty() }

    if (sketchesNotYetReceived == 0) {
      val nextStage = LiquidLegionsSketchAggregationStage.TO_APPEND_SKETCHES_AND_ADD_NOISE
      logger.info("[id=$id]: transitioning to $nextStage")
      clients.transitionComputationToStage(
        computationToken = token,
        inputsToNextStage = token.blobsList.map { it.path }.toList(),
        stage = nextStage
      )
      logger.info("[id=$id]: Saved sketch and transitioned stage to $nextStage.")
    } else {
      logger.info("[id=$id]: Saved sketch but still waiting on $sketchesNotYetReceived more.")
    }
  }

  companion object {
    val logger: Logger = Logger.getLogger(this::class.java.name)
  }
}

/**
 * Reduces flow of pairs of ids and byte arrays into a single flow of id and concatenated byte
 * array. This function requires that all ids in the flow are equal.
 */
private suspend fun Flow<Pair<Long, ByteArray>>.appendAllByteArrays(): Pair<Long, ByteArray> =
  this.reduce { x, y ->
    require(x.first == y.first) { "Stream has multiple computations $x and $y" }
    x.first to (x.second + y.second)
  }
