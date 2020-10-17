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

package org.wfanet.measurement.service.v1alpha.publisherdata

import io.grpc.Status
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.wfanet.measurement.api.v1alpha.CombinedPublicKey
import org.wfanet.measurement.api.v1alpha.CreateCampaignRequest
import org.wfanet.measurement.api.v1alpha.DataProviderRegistrationGrpcKt.DataProviderRegistrationCoroutineStub
import org.wfanet.measurement.api.v1alpha.ElGamalPublicKey
import org.wfanet.measurement.api.v1alpha.FulfillMetricRequisitionRequest
import org.wfanet.measurement.api.v1alpha.GetCombinedPublicKeyRequest
import org.wfanet.measurement.api.v1alpha.ListMetricRequisitionsRequest
import org.wfanet.measurement.api.v1alpha.MetricRequisition
import org.wfanet.measurement.api.v1alpha.PublisherDataGrpcKt.PublisherDataCoroutineImplBase as PublisherDataCoroutineService
import org.wfanet.measurement.api.v1alpha.RequisitionGrpcKt.RequisitionCoroutineStub
import org.wfanet.measurement.api.v1alpha.UploadMetricValueRequest
import org.wfanet.measurement.api.v1alpha.UploadMetricValueResponse
import org.wfanet.measurement.common.grpc.grpcRequire
import org.wfanet.measurement.duchy.DuchyPublicKeys
import org.wfanet.measurement.internal.duchy.MetricValue.ResourceKey
import org.wfanet.measurement.internal.duchy.MetricValue.ResourceKeyOrBuilder
import org.wfanet.measurement.internal.duchy.MetricValuesGrpcKt.MetricValuesCoroutineStub
import org.wfanet.measurement.internal.duchy.StoreMetricValueRequest

/**
 * Implementation of `wfa.measurement.api.v1alpha.PublisherData` service.
 *
 * @param metricValuesClient client stub for internal MetricValues service.
 * @param requisitionClient client stub for Requisition service.
 * @param registrationClient client stub for DataProviderRegistration service.
 */
class PublisherDataService(
  private val metricValuesClient: MetricValuesCoroutineStub,
  private val requisitionClient: RequisitionCoroutineStub,
  private val registrationClient: DataProviderRegistrationCoroutineStub,
  private val duchyPublicKeys: DuchyPublicKeys
) : PublisherDataCoroutineService() {

  /**
   * Latest [CombinedPublicKey] resource.
   *
   * This is eagerly cached as most [GetCombinedPublicKeyRequest]s will be for
   * this value.
   */
  private val latestCombinedPublicKey: CombinedPublicKey =
    getCombinedPublicKey(duchyPublicKeys.latest.combinedPublicKeyId)!!

  override suspend fun listMetricRequisitions(request: ListMetricRequisitionsRequest) =
    requisitionClient.listMetricRequisitions(request)

  override suspend fun createCampaign(request: CreateCampaignRequest) =
    registrationClient.createCampaign(request)

  override suspend fun uploadMetricValue(
    requests: Flow<UploadMetricValueRequest>
  ): UploadMetricValueResponse {
    val internalMetricValue = metricValuesClient.storeMetricValue(
      requests.map { requestMessage ->
        StoreMetricValueRequest.newBuilder().apply {
          if (requestMessage.hasHeader()) {
            val key = requestMessage.header.key
            headerBuilder.resourceKey = key.toResourceKey()
          } else {
            chunkBuilder.data = requestMessage.chunk.data
          }
        }.build()
      }
    )

    val requisition = requisitionClient.fulfillMetricRequisition(
      FulfillMetricRequisitionRequest.newBuilder().apply {
        key = internalMetricValue.resourceKey.toRequisitionKey()
      }.build()
    )

    return UploadMetricValueResponse.newBuilder().apply {
      state = requisition.state
    }.build()
  }

  override suspend fun getCombinedPublicKey(
    request: GetCombinedPublicKeyRequest
  ): CombinedPublicKey {
    val combinedPublicKeyId: String = request.key.combinedPublicKeyId
    grpcRequire(combinedPublicKeyId.isNotEmpty()) { "CombinedPublicKey ID missing" }

    if (combinedPublicKeyId == latestCombinedPublicKey.key.combinedPublicKeyId) {
      return latestCombinedPublicKey
    }
    return getCombinedPublicKey(combinedPublicKeyId) ?: throw Status.NOT_FOUND.asRuntimeException()
  }

  private fun getCombinedPublicKey(combinedPublicKeyId: String): CombinedPublicKey? {
    val entry = duchyPublicKeys.get(combinedPublicKeyId) ?: return null

    return CombinedPublicKey.newBuilder().apply {
      keyBuilder.combinedPublicKeyId = combinedPublicKeyId
      version = entry.combinedPublicKeyVersion
      encryptionKey = entry.combinedPublicKey.toApiMessage()
    }.build()
  }
}

private fun ResourceKeyOrBuilder.toRequisitionKey(): MetricRequisition.Key {
  return MetricRequisition.Key.newBuilder().apply {
    dataProviderId = dataProviderResourceId
    campaignId = campaignResourceId
    metricRequisitionId = metricRequisitionResourceId
  }.build()
}

fun MetricRequisition.KeyOrBuilder.toResourceKey(): ResourceKey {
  return ResourceKey.newBuilder().apply {
    dataProviderResourceId = dataProviderId
    campaignResourceId = campaignId
    metricRequisitionResourceId = metricRequisitionId
  }.build()
}

private fun org.wfanet.measurement.common.crypto.ElGamalPublicKey.toApiMessage(): ElGamalPublicKey {
  return ElGamalPublicKey.newBuilder().also {
    it.ellipticCurveId = ellipticCurveId
    it.generator = generator
    it.element = element
  }.build()
}
