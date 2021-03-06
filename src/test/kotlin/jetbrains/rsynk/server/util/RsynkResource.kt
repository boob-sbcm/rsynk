/**
 * Copyright 2016 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.rsynk.server.util

import jetbrains.rsynk.server.application.Rsynk
import org.junit.rules.ExternalResource
import java.util.concurrent.TimeUnit

class RsynkResource : ExternalResource() {
    val port: Int = IntegrationTools.findFreePort()

    val rsynk = Rsynk.builder
            .setPort(port)
            .setNumberOfWorkerThreads(1)
            .setRSAKey(IntegrationTools.getPrivateServerKey(), IntegrationTools.getPublicServerKey())
            .setIdleConnectionTimeout(IntegrationTools.getIdleConnectionTimeout(), TimeUnit.MILLISECONDS)
            .setNumberOfNioWorkers(1)
            .build()

    override fun after() {
        rsynk.close()
    }
}
