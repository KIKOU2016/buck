/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.io;

import com.facebook.buck.bser.BserDeserializer;
import com.facebook.buck.io.unixsocket.UnixDomainSocket;
import com.facebook.buck.log.Logger;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class Watchman implements AutoCloseable {

  public enum Capability {
    DIRNAME,
    SUPPORTS_PROJECT_WATCH,
    WILDMATCH_GLOB,
    WILDMATCH_MULTISLASH,
    GLOB_GENERATOR,
    CLOCK_SYNC_TIMEOUT
  }

  public static final String NULL_CLOCK = "c:0:0";

  private static final int WATCHMAN_CLOCK_SYNC_TIMEOUT = 100;
  private static final ImmutableSet<String> REQUIRED_CAPABILITIES =
      ImmutableSet.of(
          "cmd-watch-project"
      );

  private static final ImmutableMap<String, Capability> ALL_CAPABILITIES =
      ImmutableMap.<String, Capability>builder()
          .put("term-dirname", Capability.DIRNAME)
          .put("cmd-watch-project", Capability.SUPPORTS_PROJECT_WATCH)
          .put("wildmatch", Capability.WILDMATCH_GLOB)
          .put("wildmatch_multislash", Capability.WILDMATCH_MULTISLASH)
          .put("glob_generator", Capability.GLOB_GENERATOR)
          .put("clock-sync-timeout", Capability.CLOCK_SYNC_TIMEOUT)
          .build();

  private static final Logger LOG = Logger.get(Watchman.class);

  private static final long POLL_TIME_NANOS = TimeUnit.SECONDS.toNanos(1);
  // Crawling a large repo in `watch-project` might take a long time on a slow disk.
  private static final long DEFAULT_COMMAND_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(45);

  private static final Path WATCHMAN = Paths.get("watchman");
  public static final Watchman NULL_WATCHMAN = new Watchman(
      ProjectWatch.of("", Optional.<String>empty()),
      ImmutableSet.of(),
      Optional.empty(),
      Optional.empty(),
      Optional.empty());

  private final ProjectWatch projectWatch;
  private final ImmutableSet<Capability> capabilities;
  private final Optional<Path> socketPath;
  private final Optional<WatchmanClient> watchmanClient;
  private final Optional<String> initialClockId;

  public static Watchman build(
      Path rootPath,
      ImmutableMap<String, String> env,
      Console console,
      Clock clock,
      Optional<Long> commandTimeoutMillis)
      throws InterruptedException {
    return build(
        new ListeningProcessExecutor(),
        localSocketWatchmanConnector(
            console,
            clock),
        rootPath,
        env,
        new ExecutableFinder(),
        console,
        clock,
        commandTimeoutMillis);
  }

  @VisibleForTesting
  @SuppressWarnings("PMD.PrematureDeclaration")
  static Watchman build(
      ListeningProcessExecutor executor,
      Function<Path, Optional<WatchmanClient>> watchmanConnector,
      Path rootPath,
      ImmutableMap<String, String> env,
      ExecutableFinder exeFinder,
      Console console,
      Clock clock,
      Optional<Long> commandTimeoutMillis) throws InterruptedException {
    LOG.info("Creating for: " + rootPath);
    Optional<WatchmanClient> watchmanClient = Optional.empty();
    try {
      Path watchmanPath = exeFinder.getExecutable(WATCHMAN, env).toAbsolutePath();
      Optional<? extends Map<String, ? extends Object>> result;

      long timeoutMillis = commandTimeoutMillis.orElse(DEFAULT_COMMAND_TIMEOUT_MILLIS);
      long remainingTimeNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      long startTimeNanos = clock.nanoTime();
      result = execute(
          executor,
          console,
          clock,
          timeoutMillis,
          remainingTimeNanos,
          watchmanPath,
          "get-sockname");

      if (!result.isPresent()) {
        return NULL_WATCHMAN;
      }

      String rawSockname = (String) result.get().get("sockname");
      if (rawSockname == null) {
        return NULL_WATCHMAN;
      }
      Path socketPath = Paths.get(rawSockname);

      LOG.info("Connecting to Watchman version %s at %s", result.get().get("version"), socketPath);
      watchmanClient = watchmanConnector.apply(socketPath);
      if (!watchmanClient.isPresent()) {
        LOG.warn("Could not connect to Watchman, disabling.");
        return NULL_WATCHMAN;
      }
      LOG.debug("Connected to Watchman");

      long versionQueryStartTimeNanos = clock.nanoTime();
      remainingTimeNanos -= versionQueryStartTimeNanos - startTimeNanos;

      result = watchmanClient.get().queryWithTimeout(
          remainingTimeNanos,
          "version",
          ImmutableMap.of(
              "required",
              REQUIRED_CAPABILITIES,
              "optional",
              ALL_CAPABILITIES.keySet()));

      LOG.info(
          "Took %d ms to query capabilities %s",
          TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - versionQueryStartTimeNanos),
          ALL_CAPABILITIES);

      if (!result.isPresent()) {
        LOG.warn("Could not get version response from Watchman, disabling Watchman");
        watchmanClient.get().close();
        return NULL_WATCHMAN;
      }

      ImmutableSet.Builder<Capability> capabilitiesBuilder = ImmutableSet.builder();
      if (!extractCapabilities(result.get(), capabilitiesBuilder)) {
        LOG.warn("Could not extract capabilities, disabling Watchman");
        watchmanClient.get().close();
        return NULL_WATCHMAN;
      }
      ImmutableSet<Capability> capabilities = capabilitiesBuilder.build();
      LOG.debug("Got Watchman capabilities: %s", capabilities);

      Path absoluteRootPath = rootPath.toAbsolutePath();
      LOG.info("Adding watchman root: %s", absoluteRootPath);

      long projectWatchTimeNanos = clock.nanoTime();
      remainingTimeNanos -= (projectWatchTimeNanos - versionQueryStartTimeNanos);
      watchmanClient.get().queryWithTimeout(
          remainingTimeNanos,
          "watch-project",
          absoluteRootPath.toString());

      // TODO(mzlee): There is a bug in watchman (that will be fixed
      // in a later watchman release) where watch-project returns
      // before the crawl is finished which causes the next
      // interaction to block. Calling watch-project a second time
      // properly attributes where we are spending time.
      long secondProjectWatchTimeNanos = clock.nanoTime();
      remainingTimeNanos -= (secondProjectWatchTimeNanos - projectWatchTimeNanos);
      result = watchmanClient.get().queryWithTimeout(
          remainingTimeNanos,
          "watch-project",
          absoluteRootPath.toString());
      LOG.info(
          "Took %d ms to add root %s",
          TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - projectWatchTimeNanos),
          absoluteRootPath);

      if (!result.isPresent()) {
        watchmanClient.get().close();
        return NULL_WATCHMAN;
      }

      Map<String, ? extends Object> map = result.get();

      if (map.containsKey("error")) {
        LOG.warn("Error in watchman output: %s", map.get("error"));
        watchmanClient.get().close();
        return NULL_WATCHMAN;
      }

      if (map.containsKey("warning")) {
        LOG.warn("Warning in watchman output: %s", map.get("warning"));
        // Warnings are not fatal. Don't panic.
      }

      if (!map.containsKey("watch")) {
        watchmanClient.get().close();
        return NULL_WATCHMAN;
      }

      Optional<String> initialClock = Optional.empty();
      String watchRoot = (String) map.get("watch");
      Optional<String> watchPrefix = Optional.ofNullable((String) map.get("relative_path"));

      if (capabilities.contains(Capability.CLOCK_SYNC_TIMEOUT)) {
        long clockStartTimeNanos = clock.nanoTime();
        remainingTimeNanos -= (clockStartTimeNanos - secondProjectWatchTimeNanos);
        result = watchmanClient.get().queryWithTimeout(
            remainingTimeNanos,
            ImmutableList.of(
                "clock",
                Optional.ofNullable((String) map.get("watch")).orElse(absoluteRootPath.toString()),
                ImmutableMap.of("sync_timeout", WATCHMAN_CLOCK_SYNC_TIMEOUT)).toArray());
        if (result.isPresent()) {
          Map<String, ? extends Object> clockResult = result.get();
          initialClock = Optional.ofNullable((String) clockResult.get("clock"));
          LOG.info(
              "Took %d ms to query for initial clock id %s",
              TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - clockStartTimeNanos),
              initialClock);
        } else {
          LOG.warn(
              "Took %d ms but could not get an initial clock id. Falling back to a named cursor",
              TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - clockStartTimeNanos));
        }
      }

      return new Watchman(
          ProjectWatch.of(watchRoot, watchPrefix),
          capabilities,
          initialClock,
          Optional.of(socketPath),
          watchmanClient);
    } catch (ClassCastException | HumanReadableException | IOException e) {
      LOG.warn(e, "Unable to determine the version of watchman. Going without.");
      if (watchmanClient.isPresent()) {
        try {
          watchmanClient.get().close();
        } catch (IOException ioe) {
          LOG.warn(ioe, "Could not close watchman query client");
        }
      }
      return NULL_WATCHMAN;
    }
  }

  @SuppressWarnings("unchecked")
  private static boolean extractCapabilities(
      Map<String, ? extends Object> versionResponse,
      ImmutableSet.Builder<Capability> capabilitiesBuilder) {
    if (versionResponse.containsKey("error")) {
      LOG.warn("Error in watchman output: %s", versionResponse.get("error"));
      return false;
    }

    if (versionResponse.containsKey("warning")) {
      LOG.warn("Warning in watchman output: %s", versionResponse.get("warning"));
      // Warnings are not fatal. Don't panic.
    }

    Object capabilitiesResponse = versionResponse.get("capabilities");
    if (!(capabilitiesResponse instanceof Map<?, ?>)) {
      LOG.warn("capabilities response is not map, got %s", capabilitiesResponse);
      return false;
    }

    LOG.debug("Got capabilities response: %s", capabilitiesResponse);

    Map<String, Boolean> capabilities = (Map<String, Boolean>) capabilitiesResponse;
    for (Map.Entry<String, Boolean> capabilityEntry : capabilities.entrySet()) {
      Capability capability = ALL_CAPABILITIES.get(capabilityEntry.getKey());
      if (capability == null) {
        LOG.warn("Unexpected capability in response: %s", capabilityEntry.getKey());
        return false;
      }
      if (capabilityEntry.getValue()) {
        capabilitiesBuilder.add(capability);
      }
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private static Optional<Map<String, Object>> execute(
      ListeningProcessExecutor executor,
      Console console,
      Clock clock,
      long commandTimeoutMillis,
      long timeoutNanos,
      Path watchmanPath,
      String... args)
    throws InterruptedException, IOException {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    ForwardingProcessListener listener = new ForwardingProcessListener(
        Channels.newChannel(stdout), Channels.newChannel(stderr));
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        ProcessExecutorParams.builder()
            .addCommand(watchmanPath.toString(), "--output-encoding=bser")
            .addCommand(args)
            .build(),
        listener);

    long startTimeNanos = clock.nanoTime();
    int exitCode = executor.waitForProcess(
        process,
        Math.min(timeoutNanos, POLL_TIME_NANOS),
        TimeUnit.NANOSECONDS);
    if (exitCode == Integer.MIN_VALUE) {
      // Let the user know we're still here waiting for Watchman, then wait the
      // rest of the timeout period.
      long remainingNanos = timeoutNanos - (clock.nanoTime() - startTimeNanos);
      if (remainingNanos > 0) {
        console.getStdErr().getRawStream().format(
            "Waiting for Watchman command [%s]...\n",
            Joiner.on(" ").join(args));
        exitCode = executor.waitForProcess(
            process,
            remainingNanos,
            TimeUnit.NANOSECONDS);
      }
    }
    LOG.debug(
        "Waited %d ms for Watchman command %s, exit code %d",
        TimeUnit.NANOSECONDS.toMillis(clock.nanoTime() - startTimeNanos),
        Joiner.on(" ").join(args),
        exitCode);
    if (exitCode == Integer.MIN_VALUE) {
      LOG.warn(
          "Watchman did not respond within %d ms, disabling.",
          commandTimeoutMillis);
      console.getStdErr().getRawStream().format(
          "Timed out after %d ms waiting for Watchman command [%s]. Disabling Watchman.\n",
          commandTimeoutMillis,
          Joiner.on(" ").join(args));
      return Optional.empty();
    }
    if (exitCode != 0) {
      LOG.debug("Watchman's stderr: %s", new String(stderr.toByteArray(), Charsets.UTF_8));
      LOG.error("Error %d executing %s", exitCode, Joiner.on(" ").join(args));
      return Optional.empty();
    }

    Object response = new BserDeserializer(BserDeserializer.KeyOrdering.UNSORTED)
        .deserializeBserValue(new ByteArrayInputStream(stdout.toByteArray()));
    LOG.debug("stdout of command: " + response);
    if (!(response instanceof Map<?, ?>)) {
      LOG.error("Unexpected response from Watchman: %s", response);
      return Optional.empty();
    }
    return Optional.of((Map<String, Object>) response);
  }

  private static Function<Path, Optional<WatchmanClient>> localSocketWatchmanConnector(
      final Console console,
      final Clock clock) {
    return new Function<Path, Optional<WatchmanClient>>() {
      @Override
      public Optional<WatchmanClient> apply(Path socketPath) {
        try {
          return Optional.of(
              new WatchmanSocketClient(
                  console,
                  clock,
                  createLocalWatchmanSocket(socketPath)));
        } catch (IOException e) {
          LOG.warn(e, "Could not connect to Watchman at path %s", socketPath);
          return Optional.empty();
        }
      }

      private Socket createLocalWatchmanSocket(Path socketPath) throws IOException {
        // TODO(bhamiltoncx): Support Windows named pipes here.
        return UnixDomainSocket.createSocketWithPath(socketPath);
      }
    };
  }

  // TODO(bhamiltoncx): Split the metadata out into an immutable value type and pass
  // the WatchmanClient separately.
  @VisibleForTesting
  public Watchman(
      ProjectWatch projectWatch,
      ImmutableSet<Capability> capabilities,
      Optional<String> initialClockId,
      Optional<Path> socketPath,
      Optional<WatchmanClient> watchmanClient) {
    this.projectWatch = projectWatch;
    this.capabilities = capabilities;
    this.initialClockId = initialClockId;
    this.socketPath = socketPath;
    this.watchmanClient = watchmanClient;
  }

  public ProjectWatch getProjectWatch() {
    return projectWatch;
  }

  public ImmutableSet<Capability> getCapabilities() {
    return capabilities;
  }

  public Optional<String> getClockId() {
    return initialClockId;
  }

  public boolean hasWildmatchGlob() {
    return capabilities.contains(Capability.WILDMATCH_GLOB);
  }

  public Optional<Path> getSocketPath() {
    return socketPath;
  }

  public Optional<WatchmanClient> getWatchmanClient() {
    return watchmanClient;
  }

  @Override
  public void close() throws IOException {
    if (watchmanClient.isPresent()) {
      watchmanClient.get().close();
    }
  }
}
