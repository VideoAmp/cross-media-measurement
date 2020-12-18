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

package org.wfanet.measurement.duchy.daemon.mill.liquidlegionsv2

import java.nio.file.Paths
import java.time.Clock
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.flattenConcat
import org.wfanet.measurement.common.crypto.CompleteFilteringPhaseAtAggregatorRequest
import org.wfanet.measurement.common.crypto.CompleteFilteringPhaseAtAggregatorResponse
import org.wfanet.measurement.common.crypto.CompleteFilteringPhaseRequest
import org.wfanet.measurement.common.crypto.CompleteFilteringPhaseResponse
import org.wfanet.measurement.common.crypto.CompleteFrequencyEstimationPhaseAtAggregatorRequest
import org.wfanet.measurement.common.crypto.CompleteFrequencyEstimationPhaseAtAggregatorResponse
import org.wfanet.measurement.common.crypto.CompleteFrequencyEstimationPhaseRequest
import org.wfanet.measurement.common.crypto.CompleteFrequencyEstimationPhaseResponse
import org.wfanet.measurement.common.crypto.CompleteReachEstimationPhaseAtAggregatorRequest
import org.wfanet.measurement.common.crypto.CompleteReachEstimationPhaseAtAggregatorResponse
import org.wfanet.measurement.common.crypto.CompleteReachEstimationPhaseRequest
import org.wfanet.measurement.common.crypto.CompleteReachEstimationPhaseResponse
import org.wfanet.measurement.common.crypto.CompleteSetupPhaseRequest
import org.wfanet.measurement.common.crypto.CompleteSetupPhaseResponse
import org.wfanet.measurement.common.crypto.liquidlegionsv2.LiquidLegionsV2Encryption
import org.wfanet.measurement.common.flatten
import org.wfanet.measurement.common.loadLibrary
import org.wfanet.measurement.common.throttler.MinimumIntervalThrottler
import org.wfanet.measurement.duchy.daemon.mill.CRYPTO_LIB_CPU_DURATION
import org.wfanet.measurement.duchy.daemon.mill.CryptoKeySet
import org.wfanet.measurement.duchy.daemon.mill.LiquidLegionsConfig
import org.wfanet.measurement.duchy.daemon.mill.MillBase
import org.wfanet.measurement.duchy.daemon.mill.PermanentComputationError
import org.wfanet.measurement.duchy.daemon.mill.toMetricRequisitionKey
import org.wfanet.measurement.duchy.db.computation.ComputationDataClients
import org.wfanet.measurement.duchy.service.internal.computation.outputPathList
import org.wfanet.measurement.duchy.service.system.v1alpha.advanceComputationHeader
import org.wfanet.measurement.duchy.toProtocolStage
import org.wfanet.measurement.internal.duchy.ComputationDetails.CompletedReason
import org.wfanet.measurement.internal.duchy.ComputationStage
import org.wfanet.measurement.internal.duchy.ComputationStatsGrpcKt.ComputationStatsCoroutineStub
import org.wfanet.measurement.internal.duchy.ComputationToken
import org.wfanet.measurement.internal.duchy.ComputationTypeEnum.ComputationType
import org.wfanet.measurement.internal.duchy.MetricValuesGrpcKt.MetricValuesCoroutineStub
import org.wfanet.measurement.protocol.LiquidLegionsSketchAggregationV2.ComputationDetails.RoleInComputation.AGGREGATOR
import org.wfanet.measurement.protocol.LiquidLegionsSketchAggregationV2.ComputationDetails.RoleInComputation.NON_AGGREGATOR
import org.wfanet.measurement.protocol.LiquidLegionsSketchAggregationV2.Stage
import org.wfanet.measurement.system.v1alpha.ComputationControlGrpcKt.ComputationControlCoroutineStub
import org.wfanet.measurement.system.v1alpha.ConfirmGlobalComputationRequest
import org.wfanet.measurement.system.v1alpha.FinishGlobalComputationRequest
import org.wfanet.measurement.system.v1alpha.GlobalComputationsGrpcKt.GlobalComputationsCoroutineStub
import org.wfanet.measurement.system.v1alpha.LiquidLegionsV2

/**
 * Mill works on computations using the LiquidLegionSketchAggregationProtocol.
 *
 * @param millId The identifier of this mill, used to claim a work.
 * @param dataClients clients that have access to local computation storage, i.e., spanner
 *    table and blob store.
 * @param metricValuesClient client of the own duchy's MetricValuesService.
 * @param globalComputationsClient client of the kingdom's GlobalComputationsService.
 * @param computationStatsClient client of the duchy's ComputationStatsService.
 * @param throttler A throttler used to rate limit the frequency of the mill polling from the
 *    computation table.
 * @param requestChunkSizeBytes The size of data chunk when sending result to other duchies.
 * @param clock A clock
 * @param workerStubs A map from other duchies' Ids to their corresponding
 *    computationControlClients, used for passing computation to other duchies.
 * @param cryptoKeySet The set of crypto keys used in the computation.
 * @param cryptoWorker The cryptoWorker that performs the actual computation.
 * @param liquidLegionsConfig The configuration of the LiquidLegions sketch.
 */
class LiquidLegionsV2Mill(
  millId: String,
  dataClients: ComputationDataClients,
  metricValuesClient: MetricValuesCoroutineStub,
  globalComputationsClient: GlobalComputationsCoroutineStub,
  computationStatsClient: ComputationStatsCoroutineStub,
  throttler: MinimumIntervalThrottler,
  requestChunkSizeBytes: Int = 1024 * 32, // 32 KiB
  clock: Clock = Clock.systemUTC(),
  private val workerStubs: Map<String, ComputationControlCoroutineStub>,
  private val cryptoKeySet: CryptoKeySet,
  private val cryptoWorker: LiquidLegionsV2Encryption,
  private val liquidLegionsConfig: LiquidLegionsConfig = LiquidLegionsConfig(
    12.0,
    10_000_000L,
    10
  )
) : MillBase(
  millId,
  dataClients,
  globalComputationsClient,
  metricValuesClient,
  computationStatsClient,
  throttler,
  ComputationType.LIQUID_LEGIONS_SKETCH_AGGREGATION_V2,
  requestChunkSizeBytes,
  clock
) {
  override val endingStage: ComputationStage = ComputationStage.newBuilder().apply {
    liquidLegionsSketchAggregationV2 = Stage.COMPLETE
  }.build()

  private val actions = mapOf(
    Pair(Stage.CONFIRM_REQUISITIONS_PHASE, AGGREGATOR)
      to ::confirmRequisitions,
    Pair(Stage.CONFIRM_REQUISITIONS_PHASE, NON_AGGREGATOR)
      to ::confirmRequisitions,
    Pair(Stage.SETUP_PHASE, AGGREGATOR)
      to ::completeSetupPhaseAtAggregator,
    Pair(Stage.SETUP_PHASE, NON_AGGREGATOR)
      to ::completeSetupPhaseAtNonAggregator,
    Pair(Stage.REACH_ESTIMATION_PHASE, AGGREGATOR)
      to ::completeReachEstimationPhaseAtAggregator,
    Pair(Stage.REACH_ESTIMATION_PHASE, NON_AGGREGATOR)
      to ::completeReachEstimationPhaseAtNonAggregator,
    Pair(Stage.FILTERING_PHASE, AGGREGATOR)
      to ::completeFilteringPhaseAtAggregator,
    Pair(Stage.FILTERING_PHASE, NON_AGGREGATOR)
      to ::completeFilteringPhaseAtNonAggregator,
    Pair(Stage.FREQUENCY_ESTIMATION_PHASE, AGGREGATOR)
      to ::completeFrequencyEstimationPhaseAtAggregator,
    Pair(Stage.FREQUENCY_ESTIMATION_PHASE, NON_AGGREGATOR)
      to ::completeFrequencyEstimationPhaseAtNonAggregator
  )

  override suspend fun processComputationImpl(token: ComputationToken) {
    require(token.computationDetails.hasLiquidLegionsV2()) {
      "Only Liquid Legions V2 computation is supported in this mill."
    }
    val stage = token.computationStage.liquidLegionsSketchAggregationV2
    val role = token.computationDetails.liquidLegionsV2.role
    val action = actions[Pair(stage, role)] ?: error("Unexpected stage or role: ($stage, $role)")
    action(token)
  }

  /** Process computation in the confirm requisitions phase*/
  @OptIn(FlowPreview::class) // For `flattenConcat`.
  private suspend fun confirmRequisitions(token: ComputationToken): ComputationToken {
    val requisitionsToConfirm =
      token.stageSpecificDetails.liquidLegionsV2.toConfirmRequisitionsStageDetails.keysList
    val availableRequisitions = requisitionsToConfirm.filter { metricValueExists(it) }

    globalComputationsClient.confirmGlobalComputation(
      ConfirmGlobalComputationRequest.newBuilder().apply {
        addAllReadyRequisitions(availableRequisitions.map { it.toMetricRequisitionKey() })
        keyBuilder.globalComputationId = token.globalComputationId.toString()
      }.build()
    )

    if (availableRequisitions.size != requisitionsToConfirm.size) {
      val errorMessage =
        """
        @Mill $millId:
          Computation ${token.globalComputationId} failed due to missing requisitions.
        Expected:
          $requisitionsToConfirm
        Actual:
          $availableRequisitions
        """.trimIndent()
      throw PermanentComputationError(Exception(errorMessage))
    }

    // cache the combined local requisitions to blob store.
    val concatenatedContents = streamMetricValueContents(availableRequisitions).flattenConcat()
    val nextToken = dataClients.writeSingleOutputBlob(token, concatenatedContents)
    return dataClients.transitionComputationToStage(
      nextToken,
      passThroughBlobs = nextToken.outputPathList(),
      stage = when (checkNotNull(nextToken.computationDetails.liquidLegionsV2.role)) {
        AGGREGATOR -> Stage.WAIT_SETUP_PHASE_INPUTS.toProtocolStage()
        NON_AGGREGATOR -> Stage.WAIT_TO_START.toProtocolStage()
        else ->
          error("Unknown role: ${nextToken.computationDetails.liquidLegionsV2.role}")
      }
    )
  }

  private suspend fun completeSetupPhaseAtAggregator(token: ComputationToken): ComputationToken {
    require(AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val inputCount = workerStubs.size + 1
      val cryptoResult: CompleteSetupPhaseResponse =
        cryptoWorker.completeSetupPhase(
          // TODO: set other parameters when we start to add noise.
          //  Now it just shuffles the registers.
          CompleteSetupPhaseRequest.newBuilder()
            .setCombinedRegisterVector(readAndCombineAllInputBlobs(token, inputCount))
            .build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.combinedRegisterVector
    }

    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.REACH_ESTIMATION_PHASE_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = nextDuchyStub(token)
    )

    return dataClients.transitionComputationToStage(
      nextToken,
      inputsToNextStage = nextToken.outputPathList(),
      stage = Stage.WAIT_REACH_ESTIMATION_PHASE_INPUTS.toProtocolStage()
    )
  }

  private suspend fun completeSetupPhaseAtNonAggregator(token: ComputationToken): ComputationToken {
    require(NON_AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val inputCount = 1
      val cryptoResult: CompleteSetupPhaseResponse =
        cryptoWorker.completeSetupPhase(
          // TODO: set other parameters when we start to add noise.
          //  Now it just shuffles the registers.
          CompleteSetupPhaseRequest.newBuilder()
            .setCombinedRegisterVector(readAndCombineAllInputBlobs(token, inputCount))
            .build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.combinedRegisterVector
    }

    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.SETUP_PHASE_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = aggregatorDuchyStub(token)
    )

    return dataClients.transitionComputationToStage(
      nextToken,
      inputsToNextStage = nextToken.outputPathList(),
      stage = Stage.WAIT_REACH_ESTIMATION_PHASE_INPUTS.toProtocolStage()
    )
  }

  private suspend fun completeReachEstimationPhaseAtAggregator(token: ComputationToken):
    ComputationToken {
      require(AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
        "invalid role for this function."
      }
      val (bytes, nextToken) = existingOutputOr(token) {
        val cryptoResult: CompleteReachEstimationPhaseAtAggregatorResponse =
          cryptoWorker.completeReachEstimationPhaseAtAggregator(
            CompleteReachEstimationPhaseAtAggregatorRequest.newBuilder().apply {
              localElGamalKeyPair = cryptoKeySet.ownPublicAndPrivateKeys
              compositeElGamalPublicKey = cryptoKeySet.clientPublicKey
              curveId = cryptoKeySet.curveId.toLong()
              combinedRegisterVector = readAndCombineAllInputBlobs(token, 1)
              liquidLegionsParametersBuilder.apply {
                decayRate = liquidLegionsConfig.decayRate
                size = liquidLegionsConfig.size
              }
            }.build()
          )
        logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
        cryptoResult.flagCountTuples
      }

      // Passes the computation to the next duchy.
      sendAdvanceComputationRequest(
        header = advanceComputationHeader(
          LiquidLegionsV2.Description.FILTERING_PHASE_INPUT,
          token.globalComputationId
        ),
        content = addLoggingHook(token, bytes),
        stub = nextDuchyStub(token)
      )

      return dataClients.transitionComputationToStage(
        nextToken,
        inputsToNextStage = nextToken.outputPathList(),
        stage = Stage.WAIT_FILTERING_PHASE_INPUTS.toProtocolStage()
      )
    }

  private suspend fun completeReachEstimationPhaseAtNonAggregator(
    token: ComputationToken
  ): ComputationToken {
    require(NON_AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val cryptoResult: CompleteReachEstimationPhaseResponse =
        cryptoWorker.completeReachEstimationPhase(
          CompleteReachEstimationPhaseRequest.newBuilder().apply {
            localElGamalKeyPair = cryptoKeySet.ownPublicAndPrivateKeys
            compositeElGamalPublicKey = cryptoKeySet.clientPublicKey
            curveId = cryptoKeySet.curveId.toLong()
            combinedRegisterVector = readAndCombineAllInputBlobs(token, 1)
          }.build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.combinedRegisterVector
    }

    // Passes the computation to the next duchy.
    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.REACH_ESTIMATION_PHASE_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = nextDuchyStub(token)
    )

    return dataClients.transitionComputationToStage(
      nextToken,
      inputsToNextStage = nextToken.outputPathList(),
      stage = Stage.WAIT_FILTERING_PHASE_INPUTS.toProtocolStage()
    )
  }

  private suspend fun completeFilteringPhaseAtAggregator(
    token: ComputationToken
  ): ComputationToken {
    require(AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val cryptoResult: CompleteFilteringPhaseAtAggregatorResponse =
        cryptoWorker.completeFilteringPhaseAtAggregator(
          CompleteFilteringPhaseAtAggregatorRequest.newBuilder().apply {
            localElGamalKeyPair = cryptoKeySet.ownPublicAndPrivateKeys
            compositeElGamalPublicKey = cryptoKeySet.clientPublicKey
            curveId = cryptoKeySet.curveId.toLong()
            flagCountTuples = readAndCombineAllInputBlobs(token, 1)
            maximumFrequency = liquidLegionsConfig.maxFrequency
          }.build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.sameKeyAggregatorMatrix
    }

    // Passes the computation to the next duchy.
    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.FREQUENCY_ESTIMATION_PHASE_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = nextDuchyStub(token)
    )

    return dataClients.transitionComputationToStage(
      nextToken,
      inputsToNextStage = nextToken.outputPathList(),
      stage = Stage.WAIT_FREQUENCY_ESTIMATION_PHASE_INPUTS.toProtocolStage()
    )
  }

  private suspend fun completeFilteringPhaseAtNonAggregator(
    token: ComputationToken
  ): ComputationToken {
    require(NON_AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val cryptoResult: CompleteFilteringPhaseResponse =
        cryptoWorker.completeFilteringPhase(
          CompleteFilteringPhaseRequest.newBuilder().apply {
            localElGamalKeyPair = cryptoKeySet.ownPublicAndPrivateKeys
            compositeElGamalPublicKey = cryptoKeySet.clientPublicKey
            curveId = cryptoKeySet.curveId.toLong()
            flagCountTuples = readAndCombineAllInputBlobs(token, 1)
          }.build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.flagCountTuples
    }

    // Passes the computation to the next duchy.
    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.FILTERING_PHASE_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = nextDuchyStub(token)
    )

    return dataClients.transitionComputationToStage(
      nextToken,
      inputsToNextStage = nextToken.outputPathList(),
      stage = Stage.WAIT_FREQUENCY_ESTIMATION_PHASE_INPUTS.toProtocolStage()
    )
  }

  private suspend fun completeFrequencyEstimationPhaseAtAggregator(
    token: ComputationToken
  ): ComputationToken {
    require(AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val cryptoResult: CompleteFrequencyEstimationPhaseAtAggregatorResponse =
        cryptoWorker.completeFrequencyEstimationPhaseAtAggregator(
          CompleteFrequencyEstimationPhaseAtAggregatorRequest.newBuilder().apply {
            localElGamalKeyPair = cryptoKeySet.ownPublicAndPrivateKeys
            curveId = cryptoKeySet.curveId.toLong()
            sameKeyAggregatorMatrix = readAndCombineAllInputBlobs(token, 1)
            maximumFrequency = liquidLegionsConfig.maxFrequency
          }.build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.toByteString()
    }

    val frequencyDistributionMap =
      CompleteFrequencyEstimationPhaseAtAggregatorResponse.parseFrom(bytes.flatten())
        .frequencyDistributionMap
    globalComputationsClient.finishGlobalComputation(
      FinishGlobalComputationRequest.newBuilder().apply {
        keyBuilder.globalComputationId = token.globalComputationId.toString()
        resultBuilder.apply {
          // TODO: send reach in the reach estimation phase.
          putAllFrequency(frequencyDistributionMap)
        }
      }.build()
    )

    return completeComputation(nextToken, CompletedReason.SUCCEEDED)
  }

  private suspend fun completeFrequencyEstimationPhaseAtNonAggregator(
    token: ComputationToken
  ): ComputationToken {
    require(NON_AGGREGATOR == token.computationDetails.liquidLegionsV2.role) {
      "invalid role for this function."
    }
    val (bytes, nextToken) = existingOutputOr(token) {
      val cryptoResult: CompleteFrequencyEstimationPhaseResponse =
        cryptoWorker.completeFrequencyEstimationPhase(
          CompleteFrequencyEstimationPhaseRequest.newBuilder().apply {
            localElGamalKeyPair = cryptoKeySet.ownPublicAndPrivateKeys
            curveId = cryptoKeySet.curveId.toLong()
            sameKeyAggregatorMatrix = readAndCombineAllInputBlobs(token, 1)
          }.build()
        )
      logStageDurationMetric(token, CRYPTO_LIB_CPU_DURATION, cryptoResult.elapsedCpuTimeMillis)
      cryptoResult.sameKeyAggregatorMatrix
    }

    // Passes the computation to the next duchy.
    sendAdvanceComputationRequest(
      header = advanceComputationHeader(
        LiquidLegionsV2.Description.FREQUENCY_ESTIMATION_PHASE_INPUT,
        token.globalComputationId
      ),
      content = addLoggingHook(token, bytes),
      stub = nextDuchyStub(token)
    )

    // This duchy's responsibility for the computation is done. Mark it COMPLETED locally.
    return completeComputation(nextToken, CompletedReason.SUCCEEDED)
  }

  private fun nextDuchyStub(token: ComputationToken): ComputationControlCoroutineStub {
    val nextDuchy = token.computationDetails.liquidLegionsV2.outgoingNodeId
    return workerStubs[nextDuchy]
      ?: throw PermanentComputationError(
        IllegalArgumentException("No ComputationControlService stub for next duchy '$nextDuchy'")
      )
  }

  private fun aggregatorDuchyStub(token: ComputationToken): ComputationControlCoroutineStub {
    val aggregatorDuchy = token.computationDetails.liquidLegionsV2.aggregatorNodeId
    return workerStubs[aggregatorDuchy]
      ?: throw PermanentComputationError(
        IllegalArgumentException(
          "No ComputationControlService stub for primary duchy '$aggregatorDuchy'"
        )
      )
  }

  companion object {
    init {
      loadLibrary(
        name = "estimators",
        directoryPath = Paths.get("any_sketch_java/src/main/java/org/wfanet/estimation")
      )
    }
  }
}
