// Copyright 2020 The Cross-Media Measurement Authors
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

package org.wfanet.measurement.kingdom.deploy.gcloud.spanner.testing

import java.nio.file.Paths
import org.wfanet.measurement.common.getRuntimePath
import org.wfanet.measurement.gcloud.spanner.testing.SpannerSchema

private val SCHEMA_DIR =
  Paths.get(
    "wfa_measurement_system",
    "src",
    "main",
    "kotlin",
    "org",
    "wfanet",
    "measurement",
    "kingdom",
    "deploy",
    "gcloud",
    "spanner"
  )
private val KINGDOM_SCHEMA_FILE =
  checkNotNull(getRuntimePath(SCHEMA_DIR.resolve("kingdom_legacy.sdl"))).toFile()

val KINGDOM_SCHEMA = SpannerSchema(KINGDOM_SCHEMA_FILE)
