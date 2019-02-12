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

import static org.openqa.selenium.grid.distributor.local.Host.Status.DOWN;
import static org.openqa.selenium.grid.distributor.local.Host.Status.DRAINING;
import static org.openqa.selenium.grid.distributor.local.Host.Status.UP;
import static org.openqa.selenium.grid.distributor.local.Slot.Status.ACTIVE;
import static org.openqa.selenium.grid.distributor.local.Slot.Status.AVAILABLE;

import com.google.common.collect.ImmutableList;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.grid.component.HealthCheck;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.data.NodeStatus;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.logging.Logger;

class Host {

  private static final Logger LOG = Logger.getLogger("Selenium Distributor");
  private final Node node;
  private final int maxSessionCount;
  private Status status;
  private final Runnable performHealthCheck;

  // Used any time we need to read or modify `available`
  private final ReadWriteLock lock = new ReentrantReadWriteLock(/* fair */ true);
  private final List<Slot> slots;

  public Host(Node node) {
    this.node = Objects.requireNonNull(node);
    NodeStatus status = getStatus();

    ImmutableList.Builder<Slot> slots = ImmutableList.builder();
    status.getAvailable().forEach((caps, count) -> {
      for (int i = 0; i < count; i++) {
        slots.add(new Slot(node, caps, AVAILABLE));
      }
    });
    status.getUsed().forEach((caps, count) -> {
      for (int i = 0; i < count; i++) {
        Slot slot = new Slot(node, caps, ACTIVE);
        slots.add(slot);
      }
    });
    this.slots = slots.build();

    // By definition, we can never have more sessions than we have slots available
    this.maxSessionCount = Math.min(this.slots.size(), status.getMaxSessionCount());

    this.status = Status.DOWN;

    HealthCheck healthCheck = node.getHealthCheck();

    this.performHealthCheck = () -> {
      HealthCheck.Result result = healthCheck.check();
      Host.Status current = result.isAlive() ? UP : DOWN;
      Host.Status previous = setHostStatus(current);
      if (previous == DRAINING) {
        // We want to continue to allow the node to drain.
        setHostStatus(DRAINING);
        return;
      }

      if (current != previous) {
        LOG.info(String.format(
            "Changing status of node %s from %s to %s. Reason: %s",
            node.getId(),
            previous,
            current,
            result.getMessage()));
      }
    };
  }

  public UUID getId() {
    return node.getId();
  }

  public NodeStatus getStatus() {
    return node.getStatus();
  }

  public Status getHostStatus() {
    return status;
  }

  /**
   * @return The previous status of the node.
   */
  private Status setHostStatus(Status status) {
    Status toReturn = this.status;
    this.status = Objects.requireNonNull(status, "Status must be set.");
    return toReturn;
  }

  public boolean hasCapacity(Capabilities caps) {
    Lock read = lock.readLock();
    read.lock();
    try {
      long count = slots.stream()
          .filter(slot -> slot.isSupporting(caps))
          .filter(slot -> slot.getStatus() == AVAILABLE)
          .count();

      return count > 0;
    } finally {
      read.unlock();
    }
  }

  public float getLoad() {
    Lock read = lock.readLock();
    read.lock();
    try {
      float inUse = slots.parallelStream()
          .filter(slot -> slot.getStatus() != AVAILABLE)
          .count();

      return (inUse / (float) maxSessionCount) * 100f;
    } finally {
      read.unlock();
    }
  }

  public long getLastSessionCreated() {
    Lock read = lock.readLock();
    read.lock();
    try {
      return slots.parallelStream()
          .mapToLong(Slot::getLastSessionCreated)
          .max()
          .orElse(0);
    } finally {
      read.unlock();
    }
  }

  public Supplier<Session> reserve(Capabilities caps) {
    Lock write = lock.writeLock();
    write.lock();
    try {
      Slot toReturn = slots.stream()
          .filter(slot -> slot.isSupporting(caps))
          .filter(slot -> slot.getStatus() == AVAILABLE)
          .findFirst()
          .orElseThrow(() -> new SessionNotCreatedException("Unable to reserve an instance"));

      return toReturn.onReserve(caps);
    } finally {
      write.unlock();
    }
  }

  void refresh() {
    performHealthCheck.run();
  }

  @Override
  public int hashCode() {
    return node.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Host)) {
      return false;
    }

    Host that = (Host) obj;
    return this.node.equals(that.node);
  }

  public enum Status {
    UP,
    DRAINING,
    DOWN,
  }
}
