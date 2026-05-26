/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
/**
 * Runtime classes for the Atmosphere Quarkus gRPC extension. Lifecycle-manages
 * the standalone {@link org.atmosphere.grpc.AtmosphereGrpcServer Netty gRPC server}
 * on top of the {@code AtmosphereFramework} owned by the core
 * {@code atmosphere-quarkus-extension}, delivering wire-identical behavior to
 * the Spring Boot starter.
 */
package org.atmosphere.quarkus.grpc.runtime;
