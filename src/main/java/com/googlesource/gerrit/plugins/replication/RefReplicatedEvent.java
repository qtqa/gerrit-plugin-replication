// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.replication;

import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.events.RefEvent;

import com.googlesource.gerrit.plugins.replication.ReplicationState.RefPushResult;

import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;

public class RefReplicatedEvent extends RefEvent {
  public final String project;
  public final String ref;
  public final String targetNode;
  public final String status;
  public final Status refStatus;

  public RefReplicatedEvent(String project, String ref, String targetNode,
      RefPushResult status, RemoteRefUpdate.Status refStatus) {
    super("ref-replicated");
    this.project = project;
    this.ref = ref;
    this.targetNode = targetNode;
    this.status = toStatusString(status);
    this.refStatus = refStatus;
  }

  private String toStatusString(RefPushResult status) {
    return status.name().toLowerCase().replace("_", "-");
  }

  @Override
  public Project.NameKey getProjectNameKey() {
    return new Project.NameKey(project);
  }

  @Override
  public String getRefName() {
    return ref;
  }
}