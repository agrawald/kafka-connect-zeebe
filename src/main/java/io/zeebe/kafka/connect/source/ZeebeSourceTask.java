/*
 * Copyright © 2019 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.kafka.connect.source;

import io.zeebe.client.ZeebeClient;
import io.zeebe.client.api.response.ActivatedJob;
import io.zeebe.client.api.worker.JobClient;
import io.zeebe.client.api.worker.JobWorker;
import io.zeebe.kafka.connect.util.VersionInfo;
import io.zeebe.kafka.connect.util.ZeebeClientConfigDef;
import io.zeebe.protocol.Protocol;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Source task for Zeebe which activates jobs, publishes results, and completes jobs */
public class ZeebeSourceTask extends SourceTask {
  private static final Logger LOGGER = LoggerFactory.getLogger(ZeebeSourceTask.class);

  private final AtomicBoolean running;
  private final Queue<ActivatedJob> jobs;

  private String jobHeaderTopic;
  private List<String> jobVariables;
  private int maxJobsToActivate;
  private Duration jobTimeout;
  private Duration requestTimeout;
  private Duration pollInterval;
  private String workerName;

  private ZeebeClient client;
  private List<JobWorker> workers;

  public ZeebeSourceTask() {
    this.running = new AtomicBoolean(false);
    this.jobs = new ConcurrentLinkedQueue<>();
  }

  @Override
  public void start(final Map<String, String> props) {
    final ZeebeSourceConnectorConfig config = new ZeebeSourceConnectorConfig(props);
    final List<String> jobTypes = config.getList(ZeebeSourceConnectorConfig.JOB_TYPES_CONFIG);

    client = buildClient(config);
    jobHeaderTopic = config.getString(ZeebeSourceConnectorConfig.JOB_HEADER_TOPICS_CONFIG);
    jobVariables = config.getList(ZeebeSourceConnectorConfig.JOB_VARIABLES_CONFIG);
    maxJobsToActivate = config.getInt(ZeebeSourceConnectorConfig.MAX_JOBS_TO_ACTIVATE_CONFIG);
    jobTimeout = Duration.ofMillis(config.getLong(ZeebeSourceConnectorConfig.JOB_TIMEOUT_CONFIG));
    requestTimeout = Duration.ofMillis(config.getLong(ZeebeClientConfigDef.REQUEST_TIMEOUT_CONFIG));
    pollInterval =
        Duration.ofMillis(config.getLong(ZeebeSourceConnectorConfig.POLL_INTERVAL_CONFIG));
    workerName = config.getString(ZeebeSourceConnectorConfig.WORKER_NAME_CONFIG);

    // build workers as last step since this relies on parsed configuration
    workers = jobTypes.stream().map(this::newWorker).collect(Collectors.toList());

    running.set(true);
  }

  @Override
  public List<SourceRecord> poll() throws InterruptedException {
    while (running.get()) {
      final List<SourceRecord> records = new ArrayList<>();
      ActivatedJob job;

      // API specifies to block when no data is available but regularly return control; TBD what
      // this means exactly
      while ((job = jobs.poll()) != null) {
        records.add(transformJob(job));
      }

      // the poll API expects null when no data is available
      if (!records.isEmpty()) {
        LOGGER.trace("Polled {} jobs", records.size());
        return records;
      }

      return null;
    }

    close();
    return null;
  }

  @Override
  public void stop() {
    // all resources are closed at the end of poll once not running anymore
    running.set(false);
  }

  @Override
  public void commitRecord(final SourceRecord record) throws InterruptedException {
    final long key = (Long) record.sourceOffset().get("key");
    try {
      client.newCompleteCommand(key).send().join();
    } catch (final CancellationException e) {
      LOGGER.trace("Complete command cancelled probably because task is stopping", e);
    }
  }

  @Override
  public String version() {
    return VersionInfo.getVersion();
  }

  private void close() {
    workers.forEach(JobWorker::close);
    workers.clear();

    if (client != null) {
      client.close();
      client = null;
    }
  }

  private ZeebeClient buildClient(final ZeebeSourceConnectorConfig config) {
    return ZeebeClient.newClientBuilder()
        .brokerContactPoint(config.getString(ZeebeClientConfigDef.BROKER_CONTACTPOINT_CONFIG))
        .numJobWorkerExecutionThreads(1)
        .build();
  }

  private SourceRecord transformJob(final ActivatedJob job) {
    final String topic = job.getCustomHeaders().get(jobHeaderTopic);
    final Map<String, Integer> sourcePartition =
        Collections.singletonMap("partitionId", Protocol.decodePartitionId(job.getKey()));
    // a better sourceOffset would be the position but we don't have it here unfortunately
    // key is however a monotonically increasing value, so in a sense it can provide a good
    // approximation of an offset
    final Map<String, Long> sourceOffset = Collections.singletonMap("key", job.getKey());
    return new SourceRecord(
        sourcePartition,
        sourceOffset,
        topic,
        Schema.INT64_SCHEMA,
        job.getKey(),
        Schema.STRING_SCHEMA,
        job.toJson());
  }

  private boolean isJobInvalid(final ActivatedJob job) {
    final String topic = job.getCustomHeaders().get(jobHeaderTopic);
    return topic == null || topic.isEmpty();
  }

  // eventually allow this behaviour here to be configurable: whether to ignore, fail, or
  // throw an exception here on invalid jobs
  // should we block until the request is finished?
  private void handleInvalidJob(final JobClient client, final ActivatedJob job) {
    LOGGER.info("No topic defined for job {}", job);
    client
        .newFailCommand(job.getKey())
        .retries(job.getRetries() - 1)
        .errorMessage(
            String.format(
                "Expected a kafka topic to be defined as a custom header with key '%s', but none found",
                jobHeaderTopic))
        .send();
  }

  private JobWorker newWorker(final String type) {
    return client
        .newWorker()
        .jobType(type)
        .handler(this::onJobActivated)
        .name(workerName)
        .maxJobsActive(maxJobsToActivate)
        .requestTimeout(requestTimeout)
        .timeout(jobTimeout)
        .pollInterval(pollInterval)
        .fetchVariables(jobVariables)
        .open();
  }

  private void onJobActivated(final JobClient client, final ActivatedJob job) {
    if (isJobInvalid(job)) {
      handleInvalidJob(client, job);
    } else {
      LOGGER.trace("Activated job {}", job);
      this.jobs.add(job);
    }
  }
}
