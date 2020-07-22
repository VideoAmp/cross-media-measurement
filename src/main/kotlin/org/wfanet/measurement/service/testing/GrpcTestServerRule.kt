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

package org.wfanet.measurement.service.testing

import io.grpc.BindableService
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.testing.GrpcCleanupRule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.wfanet.measurement.common.GrpcExceptionLogger

class GrpcTestServerRule(
  private val servicesFactory: (ManagedChannel) -> List<BindableService>
) : TestRule {
  private val grpcCleanupRule: GrpcCleanupRule = GrpcCleanupRule()
  private val serverName = InProcessServerBuilder.generateName()

  val channel: ManagedChannel =
    grpcCleanupRule.register(
      InProcessChannelBuilder
        .forName(serverName)
        .directExecutor()
        .build()
    )

  override fun apply(base: Statement?, description: Description?): Statement {
    val serverBuilder =
      InProcessServerBuilder.forName(serverName)
        .intercept(GrpcExceptionLogger())
        .directExecutor()

    servicesFactory(channel).forEach { serverBuilder.addService(it) }

    grpcCleanupRule.register(serverBuilder.build().start())

    return grpcCleanupRule.apply(base, description)
  }
}
