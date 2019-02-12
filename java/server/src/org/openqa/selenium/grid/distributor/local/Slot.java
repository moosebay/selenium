// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.grid.distributor.local;

import static org.openqa.selenium.grid.distributor.local.Slot.Status.ACTIVE;
import static org.openqa.selenium.grid.distributor.local.Slot.Status.AVAILABLE;
import static org.openqa.selenium.grid.distributor.local.Slot.Status.RESERVED;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.node.Node;

import java.util.Objects;
import java.util.function.Supplier;

public class Slot {

  private final Node node;
  private final Capabilities registeredCapabilities;
  private Status currentStatus;
  private long lastStartedNanos;
  private Capabilities currentCapabilities;

  public Slot(Node node, Capabilities capabilities, Status status) {
    this.node = Objects.requireNonNull(node);
    this.registeredCapabilities = Objects.requireNonNull(capabilities);
    this.currentStatus = Objects.requireNonNull(status);
  }

  public Status getStatus() {
    return currentStatus;
  }

  public long getLastSessionCreated() {
    return lastStartedNanos;
  }

  public boolean isSupporting(Capabilities caps) {
    // Simple implementation --- only checks current values
    return registeredCapabilities.getCapabilityNames().stream()
        .map(name -> Objects.equals(
            registeredCapabilities.getCapability(name),
            caps.getCapability(name)))
        .reduce(Boolean::logicalAnd)
        .orElse(false);
  }

  public Supplier<Session> onReserve(Capabilities caps) {
    if (getStatus() != AVAILABLE) {
      throw new IllegalStateException("Node is not available");
    }

    currentStatus = RESERVED;
    return () -> {
      try {
        Session session = node.newSession(caps)
            .orElseThrow(
                () -> new SessionNotCreatedException("Unable to create session for " + caps));
        onStart(caps);
        return session;
      } catch (Throwable t) {
        onEnd();
        throw t;
      }
    };
  }

  public void onStart(Capabilities capabilities) {
    if (getStatus() != RESERVED) {
      throw new IllegalStateException("Slot is not reserved");
    }

    this.lastStartedNanos = System.nanoTime();
    this.currentStatus = ACTIVE;
    this.currentCapabilities = capabilities;
  }

  public void onEnd() {
    this.currentStatus = AVAILABLE;
    this.currentCapabilities = null;
  }

  public enum Status {
    AVAILABLE,
    RESERVED,
    ACTIVE,
  }

}
