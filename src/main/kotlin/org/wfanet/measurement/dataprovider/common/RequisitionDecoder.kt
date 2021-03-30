// Copyright 2021 The Cross-Media Measurement Authors
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

package org.wfanet.measurement.dataprovider.common

import org.wfanet.measurement.api.v2alpha.MeasurementSpec
import org.wfanet.measurement.api.v2alpha.Requisition
import org.wfanet.measurement.api.v2alpha.RequisitionSpec

/** Interface for [Requisition] signature validation and decryption. */
interface RequisitionDecoder {
  fun decodeMeasurementSpec(requisition: Requisition): MeasurementSpec
  fun decodeRequisitionSpec(requisition: Requisition): RequisitionSpec
}

/** Indicates an invalid cryptographic signature. */
class InvalidSignatureException(override val message: String) : Exception()

/** Indicates a failure to decrypt. */
class DecryptionException(override val message: String) : Exception()
