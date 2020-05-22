// Copyright (C) 2020 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static com.googlesource.gerrit.plugins.replication.PushResultProcessing.NO_OP;
import static java.util.stream.Collectors.toList;

import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.reviewdb.client.Project;
import com.googlesource.gerrit.plugins.replication.ReplicationTasksStorage.ReplicateRefUpdate;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * The tests in this class aim to ensure events are correctly written and read from storage. They
 * typically do this by setting up replication destinations with long delays, performing actions
 * that are expected to write to storage, reloading the configuration (which should read from
 * storage), and then confirming the actions complete as expected.
 */
@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationStorageIT extends ReplicationDaemon {
  protected static final int TEST_TASK_FINISH_SECONDS = 1;
  protected static final int TEST_REPLICATION_MAX_RETRIES = 1;
  protected static final Duration TEST_TASK_FINISH_TIMEOUT =
      Duration.ofSeconds(TEST_TASK_FINISH_SECONDS);
  private ReplicationTasksStorage tasksStorage;

  @Override
  public void setUpTestPlugin() throws Exception {
    super.setUpTestPlugin();
    tasksStorage = plugin.getSysInjector().getInstance(ReplicationTasksStorage.class);
  }

  @Test
  public void shouldCreateIndividualReplicationTasksForEveryRemoteUrlPair() throws Exception {
    List<String> replicaSuffixes = Arrays.asList("replica1", "replica2");
    createTestProject("projectreplica1");
    createTestProject("projectreplica2");

    setReplicationDestination("foo1", replicaSuffixes, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination("foo2", replicaSuffixes, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    createChange();

    assertThat(listReplicationTasks("refs/changes/\\d*/\\d*/\\d*")).hasSize(4);
  }

  @Test
  public void shouldCreateOneReplicationTaskWhenSchedulingRepoFullSync() throws Exception {
    createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, null, new ReplicationState(NO_OP), false);

    assertThat(listReplicationTasks(".*all.*")).hasSize(1);
  }

  @Test
  public void shouldFirePendingOnlyToIncompleteUri() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    String remote1 = "foo1";
    String remote2 = "foo2";
    setReplicationDestination(remote1, suffix1, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String changeRef = createChange().getPatchSet().getRefName();
    changeReplicationTasksForRemote(changeRef, remote1).forEach(tasksStorage::delete);
    reloadConfig();

    assertThat(changeReplicationTasksForRemote(changeRef, remote2).count()).isEqualTo(1);
    assertThat(changeReplicationTasksForRemote(changeRef, remote1).count()).isEqualTo(0);
  }

  @Test
  public void shouldFireAndCompletePendingOnlyToIncompleteUri() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    Project.NameKey target1 = createTestProject("project" + suffix1);
    Project.NameKey target2 = createTestProject("project" + suffix2);
    String remote1 = "foo1";
    String remote2 = "foo2";
    setReplicationDestination(remote1, suffix1, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String changeRef = createChange().getPatchSet().getRefName();
    changeReplicationTasksForRemote(changeRef, remote1).forEach(tasksStorage::delete);

    setReplicationDestination(remote1, suffix1, ALL_PROJECTS);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS);
    reloadConfig();

    assertThat(isPushCompleted(target2, changeRef, TEST_PUSH_TIMEOUT)).isEqualTo(true);
    assertThat(isPushCompleted(target1, changeRef, TEST_PUSH_TIMEOUT)).isEqualTo(false);
  }

  @Test
  public void shouldFirePendingChangeRefs() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    String remote1 = "foo1";
    String remote2 = "foo2";
    setReplicationDestination(remote1, suffix1, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String changeRef1 = createChange().getPatchSet().getRefName();
    String changeRef2 = createChange().getPatchSet().getRefName();
    reloadConfig();

    assertThat(changeReplicationTasksForRemote(changeRef1, remote1).count()).isEqualTo(1);
    assertThat(changeReplicationTasksForRemote(changeRef1, remote2).count()).isEqualTo(1);
    assertThat(changeReplicationTasksForRemote(changeRef2, remote1).count()).isEqualTo(1);
    assertThat(changeReplicationTasksForRemote(changeRef2, remote2).count()).isEqualTo(1);
  }

  @Test
  public void shouldFireAndCompletePendingChangeRefs() throws Exception {
    String suffix1 = "replica1";
    String suffix2 = "replica2";
    Project.NameKey target1 = createTestProject("project" + suffix1);
    Project.NameKey target2 = createTestProject("project" + suffix2);
    String remote1 = "foo1";
    String remote2 = "foo2";
    setReplicationDestination(remote1, suffix1, ALL_PROJECTS, Integer.MAX_VALUE);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String changeRef1 = createChange().getPatchSet().getRefName();
    String changeRef2 = createChange().getPatchSet().getRefName();
    Map<Project.NameKey, String> refsByProject = new HashMap<>();
    refsByProject.put(target1, changeRef1);
    refsByProject.put(target2, changeRef1);
    refsByProject.put(target1, changeRef2);
    refsByProject.put(target2, changeRef2);

    setReplicationDestination(remote1, suffix1, ALL_PROJECTS);
    setReplicationDestination(remote2, suffix2, ALL_PROJECTS);
    reloadConfig();

    // Wait for completion within the time 2 pushes should take because each remote only has 1
    // thread and needs to push 2 events
    assertThat(isPushCompleted(refsByProject, TEST_PUSH_TIMEOUT.plus(TEST_PUSH_TIMEOUT)))
        .isEqualTo(true);
  }

  @Test
  public void shouldMatchTemplatedUrl() throws Exception {
    createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String urlMatch = gitPath.resolve("${name}" + "replica" + ".git").toString();
    String expectedURI = gitPath.resolve(project + "replica" + ".git").toString();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), false);

    assertThat(tasksStorage.list()).hasSize(1);
    for (ReplicationTasksStorage.ReplicateRefUpdate task : tasksStorage.list()) {
      assertThat(task.uri).isEqualTo(expectedURI);
      assertThat(task.ref).isEqualTo(PushOne.ALL_REFS);
    }
  }

  @Test
  public void shouldMatchRealUrl() throws Exception {
    createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS, Integer.MAX_VALUE);
    reloadConfig();

    String urlMatch = gitPath.resolve(project + "replica" + ".git").toString();
    String expectedURI = urlMatch;

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), false);

    assertThat(tasksStorage.list()).hasSize(1);
    for (ReplicationTasksStorage.ReplicateRefUpdate task : tasksStorage.list()) {
      assertThat(task.uri).isEqualTo(expectedURI);
      assertThat(task.ref).isEqualTo(PushOne.ALL_REFS);
    }
  }

  @Test
  public void shouldCleanupTasksAfterNewProjectReplication() throws Exception {
    setReplicationDestination("task_cleanup_project", "replica", ALL_PROJECTS);
    config.setInt("remote", "task_cleanup_project", "replicationRetry", 0);
    config.save();
    reloadConfig();
    assertThat(tasksStorage.list()).hasSize(0);
    Project.NameKey sourceProject = createTestProject("task_cleanup_project");

    WaitUtil.waitUntil(
        () -> nonEmptyProjectExists(new Project.NameKey(sourceProject + "replica.git")),
        TEST_NEW_PROJECT_TIMEOUT);
    WaitUtil.waitUntil(() -> tasksStorage.list().size() == 0, TEST_TASK_FINISH_TIMEOUT);
  }

  private Stream<ReplicateRefUpdate> changeReplicationTasksForRemote(
      String changeRef, String remote) {
    return tasksStorage.list().stream()
        .filter(task -> changeRef.equals(task.ref))
        .filter(task -> remote.equals(task.remote));
  }

  private List<ReplicateRefUpdate> listReplicationTasks(String refRegex) {
    Pattern refmaskPattern = Pattern.compile(refRegex);
    return tasksStorage.list().stream()
        .filter(task -> refmaskPattern.matcher(task.ref).matches())
        .collect(toList());
  }
}
