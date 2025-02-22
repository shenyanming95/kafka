/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.log

import java.io._
import java.nio.ByteBuffer
import java.nio.file.{Files, Paths}
import java.util.concurrent.{Callable, Executors}
import java.util.regex.Pattern
import java.util.{Collections, Optional, Properties}
import kafka.api.{ApiVersion, KAFKA_0_11_0_IV0}
import kafka.common.{OffsetsOutOfOrderException, RecordValidationException, UnexpectedAppendOffsetException}
import kafka.log.Log.DeleteDirSuffix
import kafka.metrics.KafkaYammerMetrics
import kafka.server.checkpoints.LeaderEpochCheckpointFile
import kafka.server.epoch.{EpochEntry, LeaderEpochFileCache}
import kafka.server.metadata.CachedConfigRepository
import kafka.server.{BrokerTopicStats, FetchDataInfo, FetchHighWatermark, FetchIsolation, FetchLogEnd, FetchTxnCommitted, KafkaConfig, LogDirFailureChannel, LogOffsetMetadata, PartitionMetadataFile}
import kafka.utils._
import org.apache.kafka.common.{InvalidRecordException, KafkaException, TopicPartition, Uuid}
import org.apache.kafka.common.errors._
import org.apache.kafka.common.record.FileRecords.TimestampAndOffset
import org.apache.kafka.common.record.MemoryRecords.RecordFilter
import org.apache.kafka.common.record.MemoryRecords.RecordFilter.BatchRetention
import org.apache.kafka.common.record._
import org.apache.kafka.common.requests.FetchResponse.AbortedTransaction
import org.apache.kafka.common.requests.{ListOffsetsRequest, ListOffsetsResponse}
import org.apache.kafka.common.utils.{BufferSupplier, Time, Utils}
import org.easymock.EasyMock
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{AfterEach, BeforeEach, Test}

import scala.collection.{Iterable, Map, mutable}
import scala.jdk.CollectionConverters._
import scala.collection.mutable.ListBuffer

class LogTest {
  var config: KafkaConfig = null
  val brokerTopicStats = new BrokerTopicStats
  val tmpDir = TestUtils.tempDir()
  val logDir = TestUtils.randomPartitionLogDir(tmpDir)
  val mockTime = new MockTime()
  def metricsKeySet = KafkaYammerMetrics.defaultRegistry.allMetrics.keySet.asScala

  @BeforeEach
  def setUp(): Unit = {
    val props = TestUtils.createBrokerConfig(0, "127.0.0.1:1", port = -1)
    config = KafkaConfig.fromProps(props)
  }

  @AfterEach
  def tearDown(): Unit = {
    brokerTopicStats.close()
    Utils.delete(tmpDir)
  }

  def createEmptyLogs(dir: File, offsets: Int*): Unit = {
    for(offset <- offsets) {
      Log.logFile(dir, offset).createNewFile()
      Log.offsetIndexFile(dir, offset).createNewFile()
    }
  }

  @Test
  def testLogRecoveryIsCalledUponBrokerCrash(): Unit = {
    // LogManager must realize correctly if the last shutdown was not clean and the logs need
    // to run recovery while loading upon subsequent broker boot up.
    val logDir: File = TestUtils.tempDir()
    val logProps = new Properties()
    val logConfig = LogConfig(logProps)
    val logDirs = Seq(logDir)
    val topicPartition = new TopicPartition("foo", 0)
    var log: Log = null
    val time = new MockTime()
    var cleanShutdownInterceptedValue = false
    var simulateError = false

    // Create a LogManager with some overridden methods to facilitate interception of clean shutdown
    // flag and to inject a runtime error
    def interceptedLogManager(logConfig: LogConfig, logDirs: Seq[File]): LogManager = {
      new LogManager(logDirs = logDirs.map(_.getAbsoluteFile), initialOfflineDirs = Array.empty[File], new CachedConfigRepository(),
        initialDefaultConfig = logConfig, cleanerConfig = CleanerConfig(enableCleaner = false), recoveryThreadsPerDataDir = 4,
        flushCheckMs = 1000L, flushRecoveryOffsetCheckpointMs = 10000L, flushStartOffsetCheckpointMs = 10000L,
        retentionCheckMs = 1000L, maxPidExpirationMs = 60 * 60 * 1000, scheduler = time.scheduler, time = time,
        brokerTopicStats = new BrokerTopicStats, logDirFailureChannel = new LogDirFailureChannel(logDirs.size), keepPartitionMetadataFile = config.usesTopicId) {

         override def loadLog(logDir: File, hadCleanShutdown: Boolean, recoveryPoints: Map[TopicPartition, Long],
                     logStartOffsets: Map[TopicPartition, Long], topicConfigs: Map[String, LogConfig]): Log = {

          val topicPartition = Log.parseTopicPartitionName(logDir)
          val config = topicConfigs.getOrElse(topicPartition.topic, currentDefaultConfig)
          val logRecoveryPoint = recoveryPoints.getOrElse(topicPartition, 0L)
          val logStartOffset = logStartOffsets.getOrElse(topicPartition, 0L)
          val logDirFailureChannel: LogDirFailureChannel = new LogDirFailureChannel(1)

          val producerStateManager = new ProducerStateManager(topicPartition, logDir, maxPidExpirationMs)
          val log = new Log(logDir, config, logStartOffset, logRecoveryPoint, time.scheduler, brokerTopicStats, time, maxPidExpirationMs,
            LogManager.ProducerIdExpirationCheckIntervalMs, topicPartition, producerStateManager, logDirFailureChannel, hadCleanShutdown) {
            override def recoverLog(): Long = {
              if (simulateError)
                throw new RuntimeException
              cleanShutdownInterceptedValue = hadCleanShutdown
              super.recoverLog()
            }
          }
          log

         }

      }
    }

    val cleanShutdownFile = new File(logDir, Log.CleanShutdownFile)
    val logManager: LogManager = interceptedLogManager(logConfig, logDirs)
    log = logManager.getOrCreateLog(topicPartition, isNew = true)

    // Load logs after a clean shutdown
    Files.createFile(cleanShutdownFile.toPath)
    cleanShutdownInterceptedValue = false
    logManager.loadLogs(logManager.fetchTopicConfigOverrides(Set.empty))
    assertTrue(cleanShutdownInterceptedValue, "Unexpected value intercepted for clean shutdown flag")
    assertFalse(cleanShutdownFile.exists(), "Clean shutdown file must not exist after loadLogs has completed")
    // Load logs without clean shutdown file
    cleanShutdownInterceptedValue = true
    logManager.loadLogs(logManager.fetchTopicConfigOverrides(Set.empty))
    assertFalse(cleanShutdownInterceptedValue, "Unexpected value intercepted for clean shutdown flag")
    assertFalse(cleanShutdownFile.exists(), "Clean shutdown file must not exist after loadLogs has completed")
    // Create clean shutdown file and then simulate error while loading logs such that log loading does not complete.
    Files.createFile(cleanShutdownFile.toPath)
    simulateError = true
    assertThrows(classOf[RuntimeException], () => logManager.loadLogs(logManager.fetchTopicConfigOverrides(Set.empty)))
    assertFalse(cleanShutdownFile.exists(), "Clean shutdown file must not have existed")
    // Do not simulate error on next call to LogManager#loadLogs. LogManager must understand that log had unclean shutdown the last time.
    simulateError = false
    cleanShutdownInterceptedValue = true
    logManager.loadLogs(logManager.fetchTopicConfigOverrides(Set.empty))
    assertFalse(cleanShutdownInterceptedValue, "Unexpected value for clean shutdown flag")
  }

  @Test
  def testHighWatermarkMetadataUpdatedAfterSegmentRoll(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024)
    val log = createLog(logDir, logConfig)

    def assertFetchSizeAndOffsets(fetchOffset: Long,
                                  expectedSize: Int,
                                  expectedOffsets: Seq[Long]): Unit = {
      val readInfo = log.read(
        startOffset = fetchOffset,
        maxLength = 2048,
        isolation = FetchHighWatermark,
        minOneMessage = false)
      assertEquals(expectedSize, readInfo.records.sizeInBytes)
      assertEquals(expectedOffsets, readInfo.records.records.asScala.map(_.offset))
    }

    val records = TestUtils.records(List(
      new SimpleRecord(mockTime.milliseconds, "a".getBytes, "value".getBytes),
      new SimpleRecord(mockTime.milliseconds, "b".getBytes, "value".getBytes),
      new SimpleRecord(mockTime.milliseconds, "c".getBytes, "value".getBytes)
    ))

    log.appendAsLeader(records, leaderEpoch = 0)
    assertFetchSizeAndOffsets(fetchOffset = 0L, 0, Seq())

    log.maybeIncrementHighWatermark(log.logEndOffsetMetadata)
    assertFetchSizeAndOffsets(fetchOffset = 0L, records.sizeInBytes, Seq(0, 1, 2))

    log.roll()
    assertFetchSizeAndOffsets(fetchOffset = 0L, records.sizeInBytes, Seq(0, 1, 2))

    log.appendAsLeader(records, leaderEpoch = 0)
    assertFetchSizeAndOffsets(fetchOffset = 3L, 0, Seq())
  }

  @Test
  def testAppendAsLeaderWithRaftLeader(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024)
    val log = createLog(logDir, logConfig)
    val leaderEpoch = 0

    def records(offset: Long): MemoryRecords = TestUtils.records(List(
      new SimpleRecord(mockTime.milliseconds, "a".getBytes, "value".getBytes),
      new SimpleRecord(mockTime.milliseconds, "b".getBytes, "value".getBytes),
      new SimpleRecord(mockTime.milliseconds, "c".getBytes, "value".getBytes)
    ), baseOffset = offset, partitionLeaderEpoch = leaderEpoch)

    log.appendAsLeader(records(0), leaderEpoch, AppendOrigin.RaftLeader)
    assertEquals(0, log.logStartOffset)
    assertEquals(3L, log.logEndOffset)

    // Since raft leader is responsible for assigning offsets, and the LogValidator is bypassed from the performance perspective,
    // so the first offset of the MemoryRecords to be append should equal to the next offset in the log
    assertThrows(classOf[UnexpectedAppendOffsetException], () => (log.appendAsLeader(records(1), leaderEpoch, AppendOrigin.RaftLeader)))

    // When the first offset of the MemoryRecords to be append equals to the next offset in the log, append will succeed
    log.appendAsLeader(records(3), leaderEpoch, AppendOrigin.RaftLeader)
    assertEquals(6, log.logEndOffset)
  }

  @Test
  def testAppendInfoFirstOffset(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024)
    val log = createLog(logDir, logConfig)

    val simpleRecords = List(
      new SimpleRecord(mockTime.milliseconds, "a".getBytes, "value".getBytes),
      new SimpleRecord(mockTime.milliseconds, "b".getBytes, "value".getBytes),
      new SimpleRecord(mockTime.milliseconds, "c".getBytes, "value".getBytes)
    )

    val records = TestUtils.records(simpleRecords)

    val firstAppendInfo = log.appendAsLeader(records, leaderEpoch = 0)
    assertEquals(LogOffsetMetadata(0, 0, 0), firstAppendInfo.firstOffset.get)

    val secondAppendInfo = log.appendAsLeader(
      TestUtils.records(simpleRecords),
      leaderEpoch = 0
    )
    assertEquals(LogOffsetMetadata(simpleRecords.size, 0, records.sizeInBytes), secondAppendInfo.firstOffset.get)

    log.roll()
    val afterRollAppendInfo =  log.appendAsLeader(TestUtils.records(simpleRecords), leaderEpoch = 0)
    assertEquals(LogOffsetMetadata(simpleRecords.size * 2, simpleRecords.size * 2, 0), afterRollAppendInfo.firstOffset.get)
  }

  @Test
  def testTruncateBelowFirstUnstableOffset(): Unit = {
    testTruncateBelowFirstUnstableOffset(_.truncateTo)
  }

  @Test
  def testTruncateFullyAndStartBelowFirstUnstableOffset(): Unit = {
    testTruncateBelowFirstUnstableOffset(_.truncateFullyAndStartAt)
  }

  private def testTruncateBelowFirstUnstableOffset(
    truncateFunc: Log => (Long => Unit)
  ): Unit = {
    // Verify that truncation below the first unstable offset correctly
    // resets the producer state. Specifically we are testing the case when
    // the segment position of the first unstable offset is unknown.

    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024)
    val log = createLog(logDir, logConfig)

    val producerId = 17L
    val producerEpoch: Short = 10
    val sequence = 0

    log.appendAsLeader(TestUtils.records(List(
      new SimpleRecord("0".getBytes),
      new SimpleRecord("1".getBytes),
      new SimpleRecord("2".getBytes)
    )), leaderEpoch = 0)

    log.appendAsLeader(MemoryRecords.withTransactionalRecords(
      CompressionType.NONE,
      producerId,
      producerEpoch,
      sequence,
      new SimpleRecord("3".getBytes),
      new SimpleRecord("4".getBytes)
    ), leaderEpoch = 0)

    assertEquals(Some(3L), log.firstUnstableOffset)

    // We close and reopen the log to ensure that the first unstable offset segment
    // position will be undefined when we truncate the log.
    log.close()

    val reopened = createLog(logDir, logConfig)
    assertEquals(Some(LogOffsetMetadata(3L)), reopened.producerStateManager.firstUnstableOffset)

    truncateFunc(reopened)(0L)
    assertEquals(None, reopened.firstUnstableOffset)
    assertEquals(Map.empty, reopened.producerStateManager.activeProducers)
  }

  @Test
  def testHighWatermarkMaintenance(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024)
    val log = createLog(logDir, logConfig)
    val leaderEpoch = 0

    def records(offset: Long): MemoryRecords = TestUtils.records(List(
      new SimpleRecord(mockTime.milliseconds, "a".getBytes, "value".getBytes),
      new SimpleRecord(mockTime.milliseconds, "b".getBytes, "value".getBytes),
      new SimpleRecord(mockTime.milliseconds, "c".getBytes, "value".getBytes)
    ), baseOffset = offset, partitionLeaderEpoch= leaderEpoch)

    def assertHighWatermark(offset: Long): Unit = {
      assertEquals(offset, log.highWatermark)
      assertValidLogOffsetMetadata(log, log.fetchOffsetSnapshot.highWatermark)
    }

    // High watermark initialized to 0
    assertHighWatermark(0L)

    // High watermark not changed by append
    log.appendAsLeader(records(0), leaderEpoch)
    assertHighWatermark(0L)

    // Update high watermark as leader
    log.maybeIncrementHighWatermark(LogOffsetMetadata(1L))
    assertHighWatermark(1L)

    // Cannot update past the log end offset
    log.updateHighWatermark(5L)
    assertHighWatermark(3L)

    // Update high watermark as follower
    log.appendAsFollower(records(3L))
    log.updateHighWatermark(6L)
    assertHighWatermark(6L)

    // High watermark should be adjusted by truncation
    log.truncateTo(3L)
    assertHighWatermark(3L)

    log.appendAsLeader(records(0L), leaderEpoch = 0)
    assertHighWatermark(3L)
    assertEquals(6L, log.logEndOffset)
    assertEquals(0L, log.logStartOffset)

    // Full truncation should also reset high watermark
    log.truncateFullyAndStartAt(4L)
    assertEquals(4L, log.logEndOffset)
    assertEquals(4L, log.logStartOffset)
    assertHighWatermark(4L)
  }

  private def assertNonEmptyFetch(log: Log, offset: Long, isolation: FetchIsolation): Unit = {
    val readInfo = log.read(startOffset = offset,
      maxLength = Int.MaxValue,
      isolation = isolation,
      minOneMessage = true)

    assertFalse(readInfo.firstEntryIncomplete)
    assertTrue(readInfo.records.sizeInBytes > 0)

    val upperBoundOffset = isolation match {
      case FetchLogEnd => log.logEndOffset
      case FetchHighWatermark => log.highWatermark
      case FetchTxnCommitted => log.lastStableOffset
    }

    for (record <- readInfo.records.records.asScala)
      assertTrue(record.offset < upperBoundOffset)

    assertEquals(offset, readInfo.fetchOffsetMetadata.messageOffset)
    assertValidLogOffsetMetadata(log, readInfo.fetchOffsetMetadata)
  }

  private def assertEmptyFetch(log: Log, offset: Long, isolation: FetchIsolation): Unit = {
    val readInfo = log.read(startOffset = offset,
      maxLength = Int.MaxValue,
      isolation = isolation,
      minOneMessage = true)
    assertFalse(readInfo.firstEntryIncomplete)
    assertEquals(0, readInfo.records.sizeInBytes)
    assertEquals(offset, readInfo.fetchOffsetMetadata.messageOffset)
    assertValidLogOffsetMetadata(log, readInfo.fetchOffsetMetadata)
  }

  @Test
  def testFetchUpToLogEndOffset(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024)
    val log = createLog(logDir, logConfig)

    log.appendAsLeader(TestUtils.records(List(
      new SimpleRecord("0".getBytes),
      new SimpleRecord("1".getBytes),
      new SimpleRecord("2".getBytes)
    )), leaderEpoch = 0)
    log.appendAsLeader(TestUtils.records(List(
      new SimpleRecord("3".getBytes),
      new SimpleRecord("4".getBytes)
    )), leaderEpoch = 0)

    (log.logStartOffset until log.logEndOffset).foreach { offset =>
      assertNonEmptyFetch(log, offset, FetchLogEnd)
    }
  }

  @Test
  def testFetchUpToHighWatermark(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024)
    val log = createLog(logDir, logConfig)

    log.appendAsLeader(TestUtils.records(List(
      new SimpleRecord("0".getBytes),
      new SimpleRecord("1".getBytes),
      new SimpleRecord("2".getBytes)
    )), leaderEpoch = 0)
    log.appendAsLeader(TestUtils.records(List(
      new SimpleRecord("3".getBytes),
      new SimpleRecord("4".getBytes)
    )), leaderEpoch = 0)

    def assertHighWatermarkBoundedFetches(): Unit = {
      (log.logStartOffset until log.highWatermark).foreach { offset =>
        assertNonEmptyFetch(log, offset, FetchHighWatermark)
      }

      (log.highWatermark to log.logEndOffset).foreach { offset =>
        assertEmptyFetch(log, offset, FetchHighWatermark)
      }
    }

    assertHighWatermarkBoundedFetches()

    log.updateHighWatermark(3L)
    assertHighWatermarkBoundedFetches()

    log.updateHighWatermark(5L)
    assertHighWatermarkBoundedFetches()
  }

  @Test
  def testActiveProducers(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024)
    val log = createLog(logDir, logConfig)

    def assertProducerState(
      producerId: Long,
      producerEpoch: Short,
      lastSequence: Int,
      currentTxnStartOffset: Option[Long],
      coordinatorEpoch: Option[Int]
    ): Unit = {
      val producerStateOpt = log.activeProducers.find(_.producerId == producerId)
      assertTrue(producerStateOpt.isDefined)

      val producerState = producerStateOpt.get
      assertEquals(producerEpoch, producerState.producerEpoch)
      assertEquals(lastSequence, producerState.lastSequence)
      assertEquals(currentTxnStartOffset.getOrElse(-1L), producerState.currentTxnStartOffset)
      assertEquals(coordinatorEpoch.getOrElse(-1), producerState.coordinatorEpoch)
    }

    // Test transactional producer state (open transaction)
    val producer1Epoch = 5.toShort
    val producerId1 = 1L
    appendTransactionalAsLeader(log, producerId1, producer1Epoch)(5)
    assertProducerState(
      producerId1,
      producer1Epoch,
      lastSequence = 4,
      currentTxnStartOffset = Some(0L),
      coordinatorEpoch = None
    )

    // Test transactional producer state (closed transaction)
    val coordinatorEpoch = 15
    appendEndTxnMarkerAsLeader(log, producerId1, producer1Epoch, ControlRecordType.COMMIT, coordinatorEpoch)
    assertProducerState(
      producerId1,
      producer1Epoch,
      lastSequence = 4,
      currentTxnStartOffset = None,
      coordinatorEpoch = Some(coordinatorEpoch)
    )

    // Test idempotent producer state
    val producer2Epoch = 5.toShort
    val producerId2 = 2L
    appendIdempotentAsLeader(log, producerId2, producer2Epoch)(3)
    assertProducerState(
      producerId2,
      producer2Epoch,
      lastSequence = 2,
      currentTxnStartOffset = None,
      coordinatorEpoch = None
    )
  }

  @Test
  def testFetchUpToLastStableOffset(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024)
    val log = createLog(logDir, logConfig)
    val epoch = 0.toShort

    val producerId1 = 1L
    val producerId2 = 2L

    val appendProducer1 = appendTransactionalAsLeader(log, producerId1, epoch)
    val appendProducer2 = appendTransactionalAsLeader(log, producerId2, epoch)

    appendProducer1(5)
    appendNonTransactionalAsLeader(log, 3)
    appendProducer2(2)
    appendProducer1(4)
    appendNonTransactionalAsLeader(log, 2)
    appendProducer1(10)

    def assertLsoBoundedFetches(): Unit = {
      (log.logStartOffset until log.lastStableOffset).foreach { offset =>
        assertNonEmptyFetch(log, offset, FetchTxnCommitted)
      }

      (log.lastStableOffset to log.logEndOffset).foreach { offset =>
        assertEmptyFetch(log, offset, FetchTxnCommitted)
      }
    }

    assertLsoBoundedFetches()

    log.updateHighWatermark(log.logEndOffset)
    assertLsoBoundedFetches()

    appendEndTxnMarkerAsLeader(log, producerId1, epoch, ControlRecordType.COMMIT)
    assertEquals(0L, log.lastStableOffset)

    log.updateHighWatermark(log.logEndOffset)
    assertEquals(8L, log.lastStableOffset)
    assertLsoBoundedFetches()

    appendEndTxnMarkerAsLeader(log, producerId2, epoch, ControlRecordType.ABORT)
    assertEquals(8L, log.lastStableOffset)

    log.updateHighWatermark(log.logEndOffset)
    assertEquals(log.logEndOffset, log.lastStableOffset)
    assertLsoBoundedFetches()
  }

  @Test
  def testLogDeleteDirName(): Unit = {
    val name1 = Log.logDeleteDirName(new TopicPartition("foo", 3))
    assertTrue(name1.length <= 255)
    assertTrue(Pattern.compile("foo-3\\.[0-9a-z]{32}-delete").matcher(name1).matches())
    assertTrue(Log.DeleteDirPattern.matcher(name1).matches())
    assertFalse(Log.FutureDirPattern.matcher(name1).matches())
    val name2 = Log.logDeleteDirName(
      new TopicPartition("n" + String.join("", Collections.nCopies(248, "o")), 5))
    assertEquals(255, name2.length)
    assertTrue(Pattern.compile("n[o]{212}-5\\.[0-9a-z]{32}-delete").matcher(name2).matches())
    assertTrue(Log.DeleteDirPattern.matcher(name2).matches())
    assertFalse(Log.FutureDirPattern.matcher(name2).matches())
  }

  @Test
  def testOffsetFromFile(): Unit = {
    val offset = 23423423L

    val logFile = Log.logFile(tmpDir, offset)
    assertEquals(offset, Log.offsetFromFile(logFile))

    val offsetIndexFile = Log.offsetIndexFile(tmpDir, offset)
    assertEquals(offset, Log.offsetFromFile(offsetIndexFile))

    val timeIndexFile = Log.timeIndexFile(tmpDir, offset)
    assertEquals(offset, Log.offsetFromFile(timeIndexFile))

    val snapshotFile = Log.producerSnapshotFile(tmpDir, offset)
    assertEquals(offset, Log.offsetFromFile(snapshotFile))
  }

  /**
   * Tests for time based log roll. This test appends messages then changes the time
   * using the mock clock to force the log to roll and checks the number of segments.
   */
  @Test
  def testTimeBasedLogRoll(): Unit = {
    def createRecords = TestUtils.singletonRecords("test".getBytes)
    val logConfig = LogTest.createLogConfig(segmentMs = 1 * 60 * 60L)

    // create a log
    val log = createLog(logDir, logConfig, maxProducerIdExpirationMs = 24 * 60)
    assertEquals(1, log.numberOfSegments, "Log begins with a single empty segment.")
    // Test the segment rolling behavior when messages do not have a timestamp.
    mockTime.sleep(log.config.segmentMs + 1)
    log.appendAsLeader(createRecords, leaderEpoch = 0)
    assertEquals(1, log.numberOfSegments, "Log doesn't roll if doing so creates an empty segment.")

    log.appendAsLeader(createRecords, leaderEpoch = 0)
    assertEquals(2, log.numberOfSegments, "Log rolls on this append since time has expired.")

    for (numSegments <- 3 until 5) {
      mockTime.sleep(log.config.segmentMs + 1)
      log.appendAsLeader(createRecords, leaderEpoch = 0)
      assertEquals(numSegments, log.numberOfSegments, "Changing time beyond rollMs and appending should create a new segment.")
    }

    // Append a message with timestamp to a segment whose first message do not have a timestamp.
    val timestamp = mockTime.milliseconds + log.config.segmentMs + 1
    def createRecordsWithTimestamp = TestUtils.singletonRecords(value = "test".getBytes, timestamp = timestamp)
    log.appendAsLeader(createRecordsWithTimestamp, leaderEpoch = 0)
    assertEquals(4, log.numberOfSegments, "Segment should not have been rolled out because the log rolling should be based on wall clock.")

    // Test the segment rolling behavior when messages have timestamps.
    mockTime.sleep(log.config.segmentMs + 1)
    log.appendAsLeader(createRecordsWithTimestamp, leaderEpoch = 0)
    assertEquals(5, log.numberOfSegments, "A new segment should have been rolled out")

    // move the wall clock beyond log rolling time
    mockTime.sleep(log.config.segmentMs + 1)
    log.appendAsLeader(createRecordsWithTimestamp, leaderEpoch = 0)
    assertEquals(5, log.numberOfSegments, "Log should not roll because the roll should depend on timestamp of the first message.")

    val recordWithExpiredTimestamp = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds)
    log.appendAsLeader(recordWithExpiredTimestamp, leaderEpoch = 0)
    assertEquals(6, log.numberOfSegments, "Log should roll because the timestamp in the message should make the log segment expire.")

    val numSegments = log.numberOfSegments
    mockTime.sleep(log.config.segmentMs + 1)
    log.appendAsLeader(MemoryRecords.withRecords(CompressionType.NONE), leaderEpoch = 0)
    assertEquals(numSegments, log.numberOfSegments, "Appending an empty message set should not roll log even if sufficient time has passed.")
  }

  @Test
  def testRollSegmentThatAlreadyExists(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentMs = 1 * 60 * 60L)

    // create a log
    val log = createLog(logDir, logConfig)
    assertEquals(1, log.numberOfSegments, "Log begins with a single empty segment.")

    // roll active segment with the same base offset of size zero should recreate the segment
    log.roll(Some(0L))
    assertEquals(1, log.numberOfSegments, "Expect 1 segment after roll() empty segment with base offset.")

    // should be able to append records to active segment
    val records = TestUtils.records(
      List(new SimpleRecord(mockTime.milliseconds, "k1".getBytes, "v1".getBytes)),
      baseOffset = 0L, partitionLeaderEpoch = 0)
    log.appendAsFollower(records)
    assertEquals(1, log.numberOfSegments, "Expect one segment.")
    assertEquals(0L, log.activeSegment.baseOffset)

    // make sure we can append more records
    val records2 = TestUtils.records(
      List(new SimpleRecord(mockTime.milliseconds + 10, "k2".getBytes, "v2".getBytes)),
      baseOffset = 1L, partitionLeaderEpoch = 0)
    log.appendAsFollower(records2)

    assertEquals(2, log.logEndOffset, "Expect two records in the log")
    assertEquals(0, readLog(log, 0, 1).records.batches.iterator.next().lastOffset)
    assertEquals(1, readLog(log, 1, 1).records.batches.iterator.next().lastOffset)

    // roll so that active segment is empty
    log.roll()
    assertEquals(2L, log.activeSegment.baseOffset, "Expect base offset of active segment to be LEO")
    assertEquals(2, log.numberOfSegments, "Expect two segments.")

    // manually resize offset index to force roll of an empty active segment on next append
    log.activeSegment.offsetIndex.resize(0)
    val records3 = TestUtils.records(
      List(new SimpleRecord(mockTime.milliseconds + 12, "k3".getBytes, "v3".getBytes)),
      baseOffset = 2L, partitionLeaderEpoch = 0)
    log.appendAsFollower(records3)
    assertTrue(log.activeSegment.offsetIndex.maxEntries > 1)
    assertEquals(2, readLog(log, 2, 1).records.batches.iterator.next().lastOffset)
    assertEquals(2, log.numberOfSegments, "Expect two segments.")
  }

  @Test
  def testNonSequentialAppend(): Unit = {
    // create a log
    val log = createLog(logDir, LogConfig())
    val pid = 1L
    val epoch: Short = 0

    val records = TestUtils.records(List(new SimpleRecord(mockTime.milliseconds, "key".getBytes, "value".getBytes)), producerId = pid, producerEpoch = epoch, sequence = 0)
    log.appendAsLeader(records, leaderEpoch = 0)

    val nextRecords = TestUtils.records(List(new SimpleRecord(mockTime.milliseconds, "key".getBytes, "value".getBytes)), producerId = pid, producerEpoch = epoch, sequence = 2)
    assertThrows(classOf[OutOfOrderSequenceException], () => log.appendAsLeader(nextRecords, leaderEpoch = 0))
  }

  @Test
  def testTruncateToEmptySegment(): Unit = {
    val log = createLog(logDir, LogConfig())

    // Force a segment roll by using a large offset. The first segment will be empty
    val records = TestUtils.records(List(new SimpleRecord(mockTime.milliseconds, "key".getBytes, "value".getBytes)),
      baseOffset = Int.MaxValue.toLong + 200)
    appendAsFollower(log, records)
    assertEquals(0, log.logSegments.head.size)
    assertEquals(2, log.logSegments.size)

    // Truncate to an offset before the base offset of the latest segment
    log.truncateTo(0L)
    assertEquals(1, log.logSegments.size)

    // Now verify that we can still append to the active segment
    appendAsFollower(log, TestUtils.records(List(new SimpleRecord(mockTime.milliseconds, "key".getBytes, "value".getBytes)),
      baseOffset = 100L))
    assertEquals(1, log.logSegments.size)
    assertEquals(101L, log.logEndOffset)
  }

  @Test
  def testTruncateToEndOffsetClearsEpochCache(): Unit = {
    val log = createLog(logDir, LogConfig())

    // Seed some initial data in the log
    val records = TestUtils.records(List(new SimpleRecord("a".getBytes), new SimpleRecord("b".getBytes)),
      baseOffset = 27)
    appendAsFollower(log, records, leaderEpoch = 19)
    assertEquals(Some(EpochEntry(epoch = 19, startOffset = 27)),
      log.leaderEpochCache.flatMap(_.latestEntry))
    assertEquals(29, log.logEndOffset)

    def verifyTruncationClearsEpochCache(epoch: Int, truncationOffset: Long): Unit = {
      // Simulate becoming a leader
      log.maybeAssignEpochStartOffset(leaderEpoch = epoch, startOffset = log.logEndOffset)
      assertEquals(Some(EpochEntry(epoch = epoch, startOffset = 29)),
        log.leaderEpochCache.flatMap(_.latestEntry))
      assertEquals(29, log.logEndOffset)

      // Now we become the follower and truncate to an offset greater
      // than or equal to the log end offset. The trivial epoch entry
      // at the end of the log should be gone
      log.truncateTo(truncationOffset)
      assertEquals(Some(EpochEntry(epoch = 19, startOffset = 27)),
        log.leaderEpochCache.flatMap(_.latestEntry))
      assertEquals(29, log.logEndOffset)
    }

    // Truncations greater than or equal to the log end offset should
    // clear the epoch cache
    verifyTruncationClearsEpochCache(epoch = 20, truncationOffset = log.logEndOffset)
    verifyTruncationClearsEpochCache(epoch = 24, truncationOffset = log.logEndOffset + 1)
  }

  /**
   * Test the values returned by the logSegments call
   */
  @Test
  def testLogSegmentsCallCorrect(): Unit = {
    // Create 3 segments and make sure we get the right values from various logSegments calls.
    def createRecords = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds)
    def getSegmentOffsets(log :Log, from: Long, to: Long) = log.logSegments(from, to).map { _.baseOffset }
    val setSize = createRecords.sizeInBytes
    val msgPerSeg = 10
    val segmentSize = msgPerSeg * setSize  // each segment will be 10 messages
    // create a log
    val logConfig = LogTest.createLogConfig(segmentBytes = segmentSize)
    val log = createLog(logDir, logConfig)
    assertEquals(1, log.numberOfSegments, "There should be exactly 1 segment.")

    // segments expire in size
    for (_ <- 1 to (2 * msgPerSeg + 2))
      log.appendAsLeader(createRecords, leaderEpoch = 0)
    assertEquals(3, log.numberOfSegments, "There should be exactly 3 segments.")

    // from == to should always be null
    assertEquals(List.empty[LogSegment], getSegmentOffsets(log, 10, 10))
    assertEquals(List.empty[LogSegment], getSegmentOffsets(log, 15, 15))

    assertEquals(List[Long](0, 10, 20), getSegmentOffsets(log, 0, 21))

    assertEquals(List[Long](0), getSegmentOffsets(log, 1, 5))
    assertEquals(List[Long](10, 20), getSegmentOffsets(log, 13, 21))
    assertEquals(List[Long](10), getSegmentOffsets(log, 13, 17))

    // from < to is bad
    assertThrows(classOf[IllegalArgumentException], () => log.logSegments(10, 0))
  }

  @Test
  def testInitializationOfProducerSnapshotsUpgradePath(): Unit = {
    // simulate the upgrade path by creating a new log with several segments, deleting the
    // snapshot files, and then reloading the log
    val logConfig = LogTest.createLogConfig(segmentBytes = 64 * 10)
    var log = createLog(logDir, logConfig)
    assertEquals(None, log.oldestProducerSnapshotOffset)

    for (i <- 0 to 100) {
      val record = new SimpleRecord(mockTime.milliseconds, i.toString.getBytes)
      log.appendAsLeader(TestUtils.records(List(record)), leaderEpoch = 0)
    }
    assertTrue(log.logSegments.size >= 2)
    val logEndOffset = log.logEndOffset
    log.close()

    deleteProducerSnapshotFiles()

    // Reload after clean shutdown
    log = createLog(logDir, logConfig, recoveryPoint = logEndOffset)
    var expectedSnapshotOffsets = log.logSegments.map(_.baseOffset).takeRight(2).toVector :+ log.logEndOffset
    assertEquals(expectedSnapshotOffsets, listProducerSnapshotOffsets)
    log.close()

    deleteProducerSnapshotFiles()

    // Reload after unclean shutdown with recoveryPoint set to log end offset
    log = createLog(logDir, logConfig, recoveryPoint = logEndOffset, lastShutdownClean = false)
    assertEquals(expectedSnapshotOffsets, listProducerSnapshotOffsets)
    log.close()

    deleteProducerSnapshotFiles()

    // Reload after unclean shutdown with recoveryPoint set to 0
    log = createLog(logDir, logConfig, recoveryPoint = 0L, lastShutdownClean = false)
    // We progressively create a snapshot for each segment after the recovery point
    expectedSnapshotOffsets = log.logSegments.map(_.baseOffset).tail.toVector :+ log.logEndOffset
    assertEquals(expectedSnapshotOffsets, listProducerSnapshotOffsets)
    log.close()
  }


  @Test
  def testRecoverAfterNonMonotonicCoordinatorEpochWrite(): Unit = {
    // Due to KAFKA-9144, we may encounter a coordinator epoch which goes backwards.
    // This test case verifies that recovery logic relaxes validation in this case and
    // just takes the latest write.

    val producerId = 1L
    val coordinatorEpoch = 5
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    var log = createLog(logDir, logConfig)
    val epoch = 0.toShort

    val firstAppendTimestamp = mockTime.milliseconds()
    appendEndTxnMarkerAsLeader(log, producerId, epoch, ControlRecordType.ABORT,
      timestamp = firstAppendTimestamp, coordinatorEpoch = coordinatorEpoch)
    assertEquals(firstAppendTimestamp, log.producerStateManager.lastEntry(producerId).get.lastTimestamp)

    mockTime.sleep(log.maxProducerIdExpirationMs)
    assertEquals(None, log.producerStateManager.lastEntry(producerId))

    val secondAppendTimestamp = mockTime.milliseconds()
    appendEndTxnMarkerAsLeader(log, producerId, epoch, ControlRecordType.ABORT,
      timestamp = secondAppendTimestamp, coordinatorEpoch = coordinatorEpoch - 1)

    log.close()

    // Force recovery by setting the recoveryPoint to the log start
    log = createLog(logDir, logConfig, recoveryPoint = 0L, lastShutdownClean = false)
    assertEquals(secondAppendTimestamp, log.producerStateManager.lastEntry(producerId).get.lastTimestamp)
    log.close()
  }

  @Test
  def testProducerSnapshotsRecoveryAfterUncleanShutdownV1(): Unit = {
    testProducerSnapshotsRecoveryAfterUncleanShutdown(ApiVersion.minSupportedFor(RecordVersion.V1).version)
  }

  @Test
  def testProducerSnapshotsRecoveryAfterUncleanShutdownCurrentMessageFormat(): Unit = {
    testProducerSnapshotsRecoveryAfterUncleanShutdown(ApiVersion.latestVersion.version)
  }

  @Test
  def testLogReinitializeAfterManualDelete(): Unit = {
    val logConfig = LogTest.createLogConfig()
    // simulate a case where log data does not exist but the start offset is non-zero
    val log = createLog(logDir, logConfig, logStartOffset = 500)
    assertEquals(500, log.logStartOffset)
    assertEquals(500, log.logEndOffset)
  }

  @Test
  def testLogEndLessThanStartAfterReopen(): Unit = {
    val logConfig = LogTest.createLogConfig()
    var log = createLog(logDir, logConfig)
    for (i <- 0 until 5) {
      val record = new SimpleRecord(mockTime.milliseconds, i.toString.getBytes)
      log.appendAsLeader(TestUtils.records(List(record)), leaderEpoch = 0)
      log.roll()
    }
    assertEquals(6, log.logSegments.size)

    // Increment the log start offset
    val startOffset = 4
    log.updateHighWatermark(log.logEndOffset)
    log.maybeIncrementLogStartOffset(startOffset, ClientRecordDeletion)
    assertTrue(log.logEndOffset > log.logStartOffset)

    // Append garbage to a segment below the current log start offset
    val segmentToForceTruncation = log.logSegments.take(2).last
    val bw = new BufferedWriter(new FileWriter(segmentToForceTruncation.log.file))
    bw.write("corruptRecord")
    bw.close()
    log.close()

    // Reopen the log. This will cause truncate the segment to which we appended garbage and delete all other segments.
    // All remaining segments will be lower than the current log start offset, which will force deletion of all segments
    // and recreation of a single, active segment starting at logStartOffset.
    log = createLog(logDir, logConfig, logStartOffset = startOffset, lastShutdownClean = false)
    assertEquals(1, log.logSegments.size)
    assertEquals(startOffset, log.logStartOffset)
    assertEquals(startOffset, log.logEndOffset)
  }

  @Test
  def testNonActiveSegmentsFrom(): Unit = {
    val logConfig = LogTest.createLogConfig()
    val log = createLog(logDir, logConfig)

    for (i <- 0 until 5) {
      val record = new SimpleRecord(mockTime.milliseconds, i.toString.getBytes)
      log.appendAsLeader(TestUtils.records(List(record)), leaderEpoch = 0)
      log.roll()
    }

    def nonActiveBaseOffsetsFrom(startOffset: Long): Seq[Long] = {
      log.nonActiveLogSegmentsFrom(startOffset).map(_.baseOffset).toSeq
    }

    assertEquals(5L, log.activeSegment.baseOffset)
    assertEquals(0 until 5, nonActiveBaseOffsetsFrom(0L))
    assertEquals(Seq.empty, nonActiveBaseOffsetsFrom(5L))
    assertEquals(2 until 5, nonActiveBaseOffsetsFrom(2L))
    assertEquals(Seq.empty, nonActiveBaseOffsetsFrom(6L))
  }

  @Test
  def testInconsistentLogSegmentRange(): Unit = {
    val logConfig = LogTest.createLogConfig()
    val log = createLog(logDir, logConfig)

    for (i <- 0 until 5) {
      val record = new SimpleRecord(mockTime.milliseconds, i.toString.getBytes)
      log.appendAsLeader(TestUtils.records(List(record)), leaderEpoch = 0)
      log.roll()
    }

    assertThrows(classOf[IllegalArgumentException], () => log.logSegments(5, 1))
  }

  @Test
  def testLogDelete(): Unit = {
    val logConfig = LogTest.createLogConfig()
    val log = createLog(logDir, logConfig)

    for (i <- 0 to 100) {
      val record = new SimpleRecord(mockTime.milliseconds, i.toString.getBytes)
      log.appendAsLeader(TestUtils.records(List(record)), leaderEpoch = 0)
      log.roll()
    }

    assertTrue(log.logSegments.nonEmpty)
    assertFalse(logDir.listFiles.isEmpty)

    // delete the log
    log.delete()

    assertEquals(0, log.logSegments.size)
    assertFalse(logDir.exists)
  }

  /**
   * Test that "PeriodicProducerExpirationCheck" scheduled task gets canceled after log
   * is deleted.
   */
  @Test
  def testProducerExpireCheckAfterDelete(): Unit = {
    val scheduler = new KafkaScheduler(1)
    try {
      scheduler.startup()
      val logConfig = LogTest.createLogConfig()
      val log = createLog(logDir, logConfig, scheduler = scheduler)

      val producerExpireCheck = log.producerExpireCheck
      assertTrue(scheduler.taskRunning(producerExpireCheck), "producerExpireCheck isn't as part of scheduled tasks")

      log.delete()
      assertFalse(scheduler.taskRunning(producerExpireCheck),
        "producerExpireCheck is part of scheduled tasks even after log deletion")
    } finally {
      scheduler.shutdown()
    }
  }

  private def testProducerSnapshotsRecoveryAfterUncleanShutdown(messageFormatVersion: String): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 64 * 10, messageFormatVersion = messageFormatVersion)
    var log = createLog(logDir, logConfig)
    assertEquals(None, log.oldestProducerSnapshotOffset)

    for (i <- 0 to 100) {
      val record = new SimpleRecord(mockTime.milliseconds, i.toString.getBytes)
      log.appendAsLeader(TestUtils.records(List(record)), leaderEpoch = 0)
    }

    assertTrue(log.logSegments.size >= 5)
    val segmentOffsets = log.logSegments.toVector.map(_.baseOffset)
    val activeSegmentOffset = segmentOffsets.last

    // We want the recovery point to be past the segment offset and before the last 2 segments including a gap of
    // 1 segment. We collect the data before closing the log.
    val offsetForSegmentAfterRecoveryPoint = segmentOffsets(segmentOffsets.size - 3)
    val offsetForRecoveryPointSegment = segmentOffsets(segmentOffsets.size - 4)
    val (segOffsetsBeforeRecovery, segOffsetsAfterRecovery) = segmentOffsets.toSet.partition(_ < offsetForRecoveryPointSegment)
    val recoveryPoint = offsetForRecoveryPointSegment + 1
    assertTrue(recoveryPoint < offsetForSegmentAfterRecoveryPoint)
    log.close()

    val segmentsWithReads = mutable.Set[LogSegment]()
    val recoveredSegments = mutable.Set[LogSegment]()
    val expectedSegmentsWithReads = mutable.Set[Long]()
    val expectedSnapshotOffsets = mutable.Set[Long]()

    if (logConfig.messageFormatVersion < KAFKA_0_11_0_IV0) {
      expectedSegmentsWithReads += activeSegmentOffset
      expectedSnapshotOffsets ++= log.logSegments.map(_.baseOffset).toVector.takeRight(2) :+ log.logEndOffset
    } else {
      expectedSegmentsWithReads ++= segOffsetsBeforeRecovery ++ Set(activeSegmentOffset)
      expectedSnapshotOffsets ++= log.logSegments.map(_.baseOffset).toVector.takeRight(4) :+ log.logEndOffset
    }

    def createLogWithInterceptedReads(recoveryPoint: Long) = {
      val maxProducerIdExpirationMs = 60 * 60 * 1000
      val topicPartition = Log.parseTopicPartitionName(logDir)
      val producerStateManager = new ProducerStateManager(topicPartition, logDir, maxProducerIdExpirationMs)

      // Intercept all segment read calls
      new Log(logDir, logConfig, logStartOffset = 0, recoveryPoint = recoveryPoint, mockTime.scheduler,
        brokerTopicStats, mockTime, maxProducerIdExpirationMs, LogManager.ProducerIdExpirationCheckIntervalMs,
        topicPartition, producerStateManager, new LogDirFailureChannel(10), hadCleanShutdown = false) {

        override def addSegment(segment: LogSegment): LogSegment = {
          val wrapper = new LogSegment(segment.log, segment.lazyOffsetIndex, segment.lazyTimeIndex, segment.txnIndex, segment.baseOffset,
            segment.indexIntervalBytes, segment.rollJitterMs, mockTime) {

            override def read(startOffset: Long, maxSize: Int, maxPosition: Long, minOneMessage: Boolean): FetchDataInfo = {
              segmentsWithReads += this
              super.read(startOffset, maxSize, maxPosition, minOneMessage)
            }

            override def recover(producerStateManager: ProducerStateManager,
                                 leaderEpochCache: Option[LeaderEpochFileCache]): Int = {
              recoveredSegments += this
              super.recover(producerStateManager, leaderEpochCache)
            }
          }
          super.addSegment(wrapper)
        }
      }
    }

    // Retain snapshots for the last 2 segments
    log.producerStateManager.deleteSnapshotsBefore(segmentOffsets(segmentOffsets.size - 2))
    log = createLogWithInterceptedReads(offsetForRecoveryPointSegment)
    // We will reload all segments because the recovery point is behind the producer snapshot files (pre KAFKA-5829 behaviour)
    assertEquals(expectedSegmentsWithReads, segmentsWithReads.map(_.baseOffset))
    assertEquals(segOffsetsAfterRecovery, recoveredSegments.map(_.baseOffset))
    assertEquals(expectedSnapshotOffsets, listProducerSnapshotOffsets.toSet)
    log.close()
    segmentsWithReads.clear()
    recoveredSegments.clear()

    // Only delete snapshots before the base offset of the recovery point segment (post KAFKA-5829 behaviour) to
    // avoid reading all segments
    log.producerStateManager.deleteSnapshotsBefore(offsetForRecoveryPointSegment)
    log = createLogWithInterceptedReads(recoveryPoint = recoveryPoint)
    assertEquals(Set(activeSegmentOffset), segmentsWithReads.map(_.baseOffset))
    assertEquals(segOffsetsAfterRecovery, recoveredSegments.map(_.baseOffset))
    assertEquals(expectedSnapshotOffsets, listProducerSnapshotOffsets.toSet)

    log.close()
  }

  @Test
  def testSizeForLargeLogs(): Unit = {
    val largeSize = Int.MaxValue.toLong * 2
    val logSegment: LogSegment = EasyMock.createMock(classOf[LogSegment])

    EasyMock.expect(logSegment.size).andReturn(Int.MaxValue).anyTimes
    EasyMock.replay(logSegment)

    assertEquals(Int.MaxValue, Log.sizeInBytes(Seq(logSegment)))
    assertEquals(largeSize, Log.sizeInBytes(Seq(logSegment, logSegment)))
    assertTrue(Log.sizeInBytes(Seq(logSegment, logSegment)) > Int.MaxValue)
  }

  @Test
  def testProducerIdMapOffsetUpdatedForNonIdempotentData(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5)
    val log = createLog(logDir, logConfig)
    val records = TestUtils.records(List(new SimpleRecord(mockTime.milliseconds, "key".getBytes, "value".getBytes)))
    log.appendAsLeader(records, leaderEpoch = 0)
    log.takeProducerSnapshot()
    assertEquals(Some(1), log.latestProducerSnapshotOffset)
  }

  @Test
  def testSkipLoadingIfEmptyProducerStateBeforeTruncation(): Unit = {
    val stateManager: ProducerStateManager = EasyMock.mock(classOf[ProducerStateManager])
    EasyMock.expect(stateManager.removeStraySnapshots(EasyMock.anyObject())).anyTimes()
    // Load the log
    EasyMock.expect(stateManager.latestSnapshotOffset).andReturn(None)

    stateManager.updateMapEndOffset(0L)
    EasyMock.expectLastCall().anyTimes()

    EasyMock.expect(stateManager.mapEndOffset).andStubReturn(0L)
    EasyMock.expect(stateManager.isEmpty).andStubReturn(true)

    stateManager.takeSnapshot()
    EasyMock.expectLastCall().anyTimes()

    stateManager.truncateAndReload(EasyMock.eq(0L), EasyMock.eq(0L), EasyMock.anyLong)
    EasyMock.expectLastCall()

    EasyMock.expect(stateManager.firstUnstableOffset).andStubReturn(None)

    EasyMock.replay(stateManager)

    val config = LogConfig(new Properties())
    val log = new Log(logDir,
      config,
      logStartOffset = 0L,
      recoveryPoint = 0L,
      scheduler = mockTime.scheduler,
      brokerTopicStats = brokerTopicStats,
      time = mockTime,
      maxProducerIdExpirationMs = 300000,
      producerIdExpirationCheckIntervalMs = 30000,
      topicPartition = Log.parseTopicPartitionName(logDir),
      producerStateManager = stateManager,
      logDirFailureChannel = new LogDirFailureChannel(1),
      hadCleanShutdown = false)

    EasyMock.verify(stateManager)

    // Append some messages
    EasyMock.reset(stateManager)
    EasyMock.expect(stateManager.firstUnstableOffset).andStubReturn(None)

    stateManager.updateMapEndOffset(1L)
    EasyMock.expectLastCall()
    stateManager.updateMapEndOffset(2L)
    EasyMock.expectLastCall()

    EasyMock.replay(stateManager)

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("a".getBytes))), leaderEpoch = 0)
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("b".getBytes))), leaderEpoch = 0)

    EasyMock.verify(stateManager)

    // Now truncate
    EasyMock.reset(stateManager)
    EasyMock.expect(stateManager.firstUnstableOffset).andStubReturn(None)
    EasyMock.expect(stateManager.latestSnapshotOffset).andReturn(None)
    EasyMock.expect(stateManager.isEmpty).andStubReturn(true)
    EasyMock.expect(stateManager.mapEndOffset).andReturn(2L)
    stateManager.truncateAndReload(EasyMock.eq(0L), EasyMock.eq(1L), EasyMock.anyLong)
    EasyMock.expectLastCall()
    // Truncation causes the map end offset to reset to 0
    EasyMock.expect(stateManager.mapEndOffset).andReturn(0L)
    // We skip directly to updating the map end offset
    EasyMock.expect(stateManager.updateMapEndOffset(1L))
    EasyMock.expect(stateManager.onHighWatermarkUpdated(0L))

    // Finally, we take a snapshot
    stateManager.takeSnapshot()
    EasyMock.expectLastCall().once()

    EasyMock.replay(stateManager)

    log.truncateTo(1L)

    EasyMock.verify(stateManager)
  }

  @Test
  def testSkipTruncateAndReloadIfOldMessageFormatAndNoCleanShutdown(): Unit = {
    val stateManager: ProducerStateManager = EasyMock.mock(classOf[ProducerStateManager])
    EasyMock.expect(stateManager.removeStraySnapshots(EasyMock.anyObject())).anyTimes()

    stateManager.updateMapEndOffset(0L)
    EasyMock.expectLastCall().anyTimes()

    stateManager.takeSnapshot()
    EasyMock.expectLastCall().anyTimes()

    EasyMock.expect(stateManager.isEmpty).andReturn(true)
    EasyMock.expectLastCall().once()

    EasyMock.expect(stateManager.firstUnstableOffset).andReturn(None)
    EasyMock.expectLastCall().once()

    EasyMock.replay(stateManager)

    val logProps = new Properties()
    logProps.put(LogConfig.MessageFormatVersionProp, "0.10.2")
    val config = LogConfig(logProps)
    new Log(logDir,
      config,
      logStartOffset = 0L,
      recoveryPoint = 0L,
      scheduler = mockTime.scheduler,
      brokerTopicStats = brokerTopicStats,
      time = mockTime,
      maxProducerIdExpirationMs = 300000,
      producerIdExpirationCheckIntervalMs = 30000,
      topicPartition = Log.parseTopicPartitionName(logDir),
      producerStateManager = stateManager,
      logDirFailureChannel = null)

    EasyMock.verify(stateManager)
  }

  @Test
  def testSkipTruncateAndReloadIfOldMessageFormatAndCleanShutdown(): Unit = {
    val stateManager: ProducerStateManager = EasyMock.mock(classOf[ProducerStateManager])
    EasyMock.expect(stateManager.removeStraySnapshots(EasyMock.anyObject())).anyTimes()

    stateManager.updateMapEndOffset(0L)
    EasyMock.expectLastCall().anyTimes()

    stateManager.takeSnapshot()
    EasyMock.expectLastCall().anyTimes()

    EasyMock.expect(stateManager.isEmpty).andReturn(true)
    EasyMock.expectLastCall().once()

    EasyMock.expect(stateManager.firstUnstableOffset).andReturn(None)
    EasyMock.expectLastCall().once()

    EasyMock.replay(stateManager)

    val logProps = new Properties()
    logProps.put(LogConfig.MessageFormatVersionProp, "0.10.2")
    val config = LogConfig(logProps)
    new Log(logDir,
      config,
      logStartOffset = 0L,
      recoveryPoint = 0L,
      scheduler = mockTime.scheduler,
      brokerTopicStats = brokerTopicStats,
      time = mockTime,
      maxProducerIdExpirationMs = 300000,
      producerIdExpirationCheckIntervalMs = 30000,
      topicPartition = Log.parseTopicPartitionName(logDir),
      producerStateManager = stateManager,
      logDirFailureChannel = null)

    EasyMock.verify(stateManager)
  }

  @Test
  def testSkipTruncateAndReloadIfNewMessageFormatAndCleanShutdown(): Unit = {
    val stateManager: ProducerStateManager = EasyMock.mock(classOf[ProducerStateManager])
    EasyMock.expect(stateManager.removeStraySnapshots(EasyMock.anyObject())).anyTimes()

    EasyMock.expect(stateManager.latestSnapshotOffset).andReturn(None)

    stateManager.updateMapEndOffset(0L)
    EasyMock.expectLastCall().anyTimes()

    stateManager.takeSnapshot()
    EasyMock.expectLastCall().anyTimes()

    EasyMock.expect(stateManager.isEmpty).andReturn(true)
    EasyMock.expectLastCall().once()

    EasyMock.expect(stateManager.firstUnstableOffset).andReturn(None)
    EasyMock.expectLastCall().once()

    EasyMock.replay(stateManager)

    val logProps = new Properties()
    logProps.put(LogConfig.MessageFormatVersionProp, "0.11.0")
    val config = LogConfig(logProps)
    new Log(logDir,
      config,
      logStartOffset = 0L,
      recoveryPoint = 0L,
      scheduler = mockTime.scheduler,
      brokerTopicStats = brokerTopicStats,
      time = mockTime,
      maxProducerIdExpirationMs = 300000,
      producerIdExpirationCheckIntervalMs = 30000,
      topicPartition = Log.parseTopicPartitionName(logDir),
      producerStateManager = stateManager,
      logDirFailureChannel = null)

    EasyMock.verify(stateManager)
  }

  @Test
  def testRebuildProducerIdMapWithCompactedData(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5)
    val log = createLog(logDir, logConfig)
    val pid = 1L
    val epoch = 0.toShort
    val seq = 0
    val baseOffset = 23L

    // create a batch with a couple gaps to simulate compaction
    val records = TestUtils.records(producerId = pid, producerEpoch = epoch, sequence = seq, baseOffset = baseOffset, records = List(
      new SimpleRecord(mockTime.milliseconds(), "a".getBytes),
      new SimpleRecord(mockTime.milliseconds(), "key".getBytes, "b".getBytes),
      new SimpleRecord(mockTime.milliseconds(), "c".getBytes),
      new SimpleRecord(mockTime.milliseconds(), "key".getBytes, "d".getBytes)))
    records.batches.forEach(_.setPartitionLeaderEpoch(0))

    val filtered = ByteBuffer.allocate(2048)
    records.filterTo(new TopicPartition("foo", 0), new RecordFilter {
      override def checkBatchRetention(batch: RecordBatch): BatchRetention = RecordFilter.BatchRetention.DELETE_EMPTY
      override def shouldRetainRecord(recordBatch: RecordBatch, record: Record): Boolean = !record.hasKey
    }, filtered, Int.MaxValue, BufferSupplier.NO_CACHING)
    filtered.flip()
    val filteredRecords = MemoryRecords.readableRecords(filtered)

    log.appendAsFollower(filteredRecords)

    // append some more data and then truncate to force rebuilding of the PID map
    val moreRecords = TestUtils.records(baseOffset = baseOffset + 4, records = List(
      new SimpleRecord(mockTime.milliseconds(), "e".getBytes),
      new SimpleRecord(mockTime.milliseconds(), "f".getBytes)))
    moreRecords.batches.forEach(_.setPartitionLeaderEpoch(0))
    log.appendAsFollower(moreRecords)

    log.truncateTo(baseOffset + 4)

    val activeProducers = log.activeProducersWithLastSequence
    assertTrue(activeProducers.contains(pid))

    val lastSeq = activeProducers(pid)
    assertEquals(3, lastSeq)
  }

  @Test
  def testRebuildProducerStateWithEmptyCompactedBatch(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5)
    val log = createLog(logDir, logConfig)
    val pid = 1L
    val epoch = 0.toShort
    val seq = 0
    val baseOffset = 23L

    // create an empty batch
    val records = TestUtils.records(producerId = pid, producerEpoch = epoch, sequence = seq, baseOffset = baseOffset, records = List(
      new SimpleRecord(mockTime.milliseconds(), "key".getBytes, "a".getBytes),
      new SimpleRecord(mockTime.milliseconds(), "key".getBytes, "b".getBytes)))
    records.batches.forEach(_.setPartitionLeaderEpoch(0))

    val filtered = ByteBuffer.allocate(2048)
    records.filterTo(new TopicPartition("foo", 0), new RecordFilter {
      override def checkBatchRetention(batch: RecordBatch): BatchRetention = RecordFilter.BatchRetention.RETAIN_EMPTY
      override def shouldRetainRecord(recordBatch: RecordBatch, record: Record): Boolean = false
    }, filtered, Int.MaxValue, BufferSupplier.NO_CACHING)
    filtered.flip()
    val filteredRecords = MemoryRecords.readableRecords(filtered)

    log.appendAsFollower(filteredRecords)

    // append some more data and then truncate to force rebuilding of the PID map
    val moreRecords = TestUtils.records(baseOffset = baseOffset + 2, records = List(
      new SimpleRecord(mockTime.milliseconds(), "e".getBytes),
      new SimpleRecord(mockTime.milliseconds(), "f".getBytes)))
    moreRecords.batches.forEach(_.setPartitionLeaderEpoch(0))
    log.appendAsFollower(moreRecords)

    log.truncateTo(baseOffset + 2)

    val activeProducers = log.activeProducersWithLastSequence
    assertTrue(activeProducers.contains(pid))

    val lastSeq = activeProducers(pid)
    assertEquals(1, lastSeq)
  }

  @Test
  def testUpdateProducerIdMapWithCompactedData(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5)
    val log = createLog(logDir, logConfig)
    val pid = 1L
    val epoch = 0.toShort
    val seq = 0
    val baseOffset = 23L

    // create a batch with a couple gaps to simulate compaction
    val records = TestUtils.records(producerId = pid, producerEpoch = epoch, sequence = seq, baseOffset = baseOffset, records = List(
      new SimpleRecord(mockTime.milliseconds(), "a".getBytes),
      new SimpleRecord(mockTime.milliseconds(), "key".getBytes, "b".getBytes),
      new SimpleRecord(mockTime.milliseconds(), "c".getBytes),
      new SimpleRecord(mockTime.milliseconds(), "key".getBytes, "d".getBytes)))
    records.batches.forEach(_.setPartitionLeaderEpoch(0))

    val filtered = ByteBuffer.allocate(2048)
    records.filterTo(new TopicPartition("foo", 0), new RecordFilter {
      override def checkBatchRetention(batch: RecordBatch): BatchRetention = RecordFilter.BatchRetention.DELETE_EMPTY
      override def shouldRetainRecord(recordBatch: RecordBatch, record: Record): Boolean = !record.hasKey
    }, filtered, Int.MaxValue, BufferSupplier.NO_CACHING)
    filtered.flip()
    val filteredRecords = MemoryRecords.readableRecords(filtered)

    log.appendAsFollower(filteredRecords)
    val activeProducers = log.activeProducersWithLastSequence
    assertTrue(activeProducers.contains(pid))

    val lastSeq = activeProducers(pid)
    assertEquals(3, lastSeq)
  }

  @Test
  def testProducerIdMapTruncateTo(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5)
    val log = createLog(logDir, logConfig)
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("a".getBytes))), leaderEpoch = 0)
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("b".getBytes))), leaderEpoch = 0)
    log.takeProducerSnapshot()

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("c".getBytes))), leaderEpoch = 0)
    log.takeProducerSnapshot()

    log.truncateTo(2)
    assertEquals(Some(2), log.latestProducerSnapshotOffset)
    assertEquals(2, log.latestProducerStateEndOffset)

    log.truncateTo(1)
    assertEquals(Some(1), log.latestProducerSnapshotOffset)
    assertEquals(1, log.latestProducerStateEndOffset)

    log.truncateTo(0)
    assertEquals(None, log.latestProducerSnapshotOffset)
    assertEquals(0, log.latestProducerStateEndOffset)
  }

  @Test
  def testProducerIdMapTruncateToWithNoSnapshots(): Unit = {
    // This ensures that the upgrade optimization path cannot be hit after initial loading
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5)
    val log = createLog(logDir, logConfig)
    val pid = 1L
    val epoch = 0.toShort

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("a".getBytes)), producerId = pid,
      producerEpoch = epoch, sequence = 0), leaderEpoch = 0)
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("b".getBytes)), producerId = pid,
      producerEpoch = epoch, sequence = 1), leaderEpoch = 0)

    deleteProducerSnapshotFiles()

    log.truncateTo(1L)
    assertEquals(1, log.activeProducersWithLastSequence.size)

    val lastSeqOpt = log.activeProducersWithLastSequence.get(pid)
    assertTrue(lastSeqOpt.isDefined)

    val lastSeq = lastSeqOpt.get
    assertEquals(0, lastSeq)
  }

  @Test
  def testLoadProducersAfterDeleteRecordsMidSegment(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5)
    val log = createLog(logDir, logConfig)
    val pid1 = 1L
    val pid2 = 2L
    val epoch = 0.toShort

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord(mockTime.milliseconds(), "a".getBytes)), producerId = pid1,
      producerEpoch = epoch, sequence = 0), leaderEpoch = 0)
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord(mockTime.milliseconds(), "b".getBytes)), producerId = pid2,
      producerEpoch = epoch, sequence = 0), leaderEpoch = 0)
    assertEquals(2, log.activeProducersWithLastSequence.size)

    log.updateHighWatermark(log.logEndOffset)
    log.maybeIncrementLogStartOffset(1L, ClientRecordDeletion)

    // Deleting records should not remove producer state
    assertEquals(2, log.activeProducersWithLastSequence.size)
    val retainedLastSeqOpt = log.activeProducersWithLastSequence.get(pid2)
    assertTrue(retainedLastSeqOpt.isDefined)
    assertEquals(0, retainedLastSeqOpt.get)

    log.close()

    // Because the log start offset did not advance, producer snapshots will still be present and the state will be rebuilt
    val reloadedLog = createLog(logDir, logConfig, logStartOffset = 1L, lastShutdownClean = false)
    assertEquals(2, reloadedLog.activeProducersWithLastSequence.size)
    val reloadedLastSeqOpt = log.activeProducersWithLastSequence.get(pid2)
    assertEquals(retainedLastSeqOpt, reloadedLastSeqOpt)
  }

  @Test
  def testRetentionDeletesProducerStateSnapshots(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5, retentionBytes = 0, retentionMs = 1000 * 60, fileDeleteDelayMs = 0)
    val log = createLog(logDir, logConfig)
    val pid1 = 1L
    val epoch = 0.toShort

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("a".getBytes)), producerId = pid1,
      producerEpoch = epoch, sequence = 0), leaderEpoch = 0)
    log.roll()
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("b".getBytes)), producerId = pid1,
      producerEpoch = epoch, sequence = 1), leaderEpoch = 0)
    log.roll()
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("c".getBytes)), producerId = pid1,
      producerEpoch = epoch, sequence = 2), leaderEpoch = 0)

    log.updateHighWatermark(log.logEndOffset)

    assertEquals(2, ProducerStateManager.listSnapshotFiles(logDir).size)
    // Sleep to breach the retention period
    mockTime.sleep(1000 * 60 + 1)
    log.deleteOldSegments()
    // Sleep to breach the file delete delay and run scheduled file deletion tasks
    mockTime.sleep(1)
    assertEquals(1, ProducerStateManager.listSnapshotFiles(logDir).size,
      "expect a single producer state snapshot remaining")
  }

  @Test
  def testLogStartOffsetMovementDeletesSnapshots(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5, retentionBytes = -1, fileDeleteDelayMs = 0)
    val log = createLog(logDir, logConfig)
    val pid1 = 1L
    val epoch = 0.toShort

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("a".getBytes)), producerId = pid1,
      producerEpoch = epoch, sequence = 0), leaderEpoch = 0)
    log.roll()
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("b".getBytes)), producerId = pid1,
      producerEpoch = epoch, sequence = 1), leaderEpoch = 0)
    log.roll()
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("c".getBytes)), producerId = pid1,
      producerEpoch = epoch, sequence = 2), leaderEpoch = 0)
    log.updateHighWatermark(log.logEndOffset)
    assertEquals(2, ProducerStateManager.listSnapshotFiles(logDir).size)

    // Increment the log start offset to exclude the first two segments.
    log.maybeIncrementLogStartOffset(log.logEndOffset - 1, ClientRecordDeletion)
    log.deleteOldSegments()
    // Sleep to breach the file delete delay and run scheduled file deletion tasks
    mockTime.sleep(1)
    assertEquals(1, ProducerStateManager.listSnapshotFiles(logDir).size,
      "expect a single producer state snapshot remaining")
  }

  @Test
  def testCompactionDeletesProducerStateSnapshots(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5, cleanupPolicy = LogConfig.Compact, fileDeleteDelayMs = 0)
    val log = createLog(logDir, logConfig)
    val pid1 = 1L
    val epoch = 0.toShort
    val cleaner = new Cleaner(id = 0,
      offsetMap = new FakeOffsetMap(Int.MaxValue),
      ioBufferSize = 64 * 1024,
      maxIoBufferSize = 64 * 1024,
      dupBufferLoadFactor = 0.75,
      throttler = new Throttler(Double.MaxValue, Long.MaxValue, false, time = mockTime),
      time = mockTime,
      checkDone = _ => {})

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("a".getBytes, "a".getBytes())), producerId = pid1,
      producerEpoch = epoch, sequence = 0), leaderEpoch = 0)
    log.roll()
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("a".getBytes, "b".getBytes())), producerId = pid1,
      producerEpoch = epoch, sequence = 1), leaderEpoch = 0)
    log.roll()
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("a".getBytes, "c".getBytes())), producerId = pid1,
      producerEpoch = epoch, sequence = 2), leaderEpoch = 0)
    log.updateHighWatermark(log.logEndOffset)
    assertEquals(log.logSegments.map(_.baseOffset).toSeq.sorted.drop(1), ProducerStateManager.listSnapshotFiles(logDir).map(_.offset).sorted,
      "expected a snapshot file per segment base offset, except the first segment")
    assertEquals(2, ProducerStateManager.listSnapshotFiles(logDir).size)

    // Clean segments, this should delete everything except the active segment since there only
    // exists the key "a".
    cleaner.clean(LogToClean(log.topicPartition, log, 0, log.logEndOffset))
    log.deleteOldSegments()
    // Sleep to breach the file delete delay and run scheduled file deletion tasks
    mockTime.sleep(1)
    assertEquals(log.logSegments.map(_.baseOffset).toSeq.sorted.drop(1), ProducerStateManager.listSnapshotFiles(logDir).map(_.offset).sorted,
      "expected a snapshot file per segment base offset, excluding the first")
  }

  /**
   * After loading the log, producer state is truncated such that there are no producer state snapshot files which
   * exceed the log end offset. This test verifies that these are removed.
   */
  @Test
  def testLoadingLogDeletesProducerStateSnapshotsPastLogEndOffset(): Unit = {
    val straySnapshotFile = Log.producerSnapshotFile(logDir, 42).toPath
    Files.createFile(straySnapshotFile)
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5, retentionBytes = -1, fileDeleteDelayMs = 0)
    createLog(logDir, logConfig)
    assertEquals(0, ProducerStateManager.listSnapshotFiles(logDir).size,
      "expected producer state snapshots greater than the log end offset to be cleaned up")
  }

  @Test
  def testLoadingLogKeepsLargestStrayProducerStateSnapshot(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5, retentionBytes = 0, retentionMs = 1000 * 60, fileDeleteDelayMs = 0)
    val log = createLog(logDir, logConfig)
    val pid1 = 1L
    val epoch = 0.toShort

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("a".getBytes)), producerId = pid1, producerEpoch = epoch, sequence = 0), leaderEpoch = 0)
    log.roll()
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("b".getBytes)), producerId = pid1, producerEpoch = epoch, sequence = 1), leaderEpoch = 0)
    log.roll()

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("c".getBytes)), producerId = pid1, producerEpoch = epoch, sequence = 2), leaderEpoch = 0)
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("d".getBytes)), producerId = pid1, producerEpoch = epoch, sequence = 3), leaderEpoch = 0)

    // Close the log, we should now have 3 segments
    log.close()
    assertEquals(log.logSegments.size, 3)
    // We expect 3 snapshot files, two of which are for the first two segments, the last was written out during log closing.
    assertEquals(Seq(1, 2, 4), ProducerStateManager.listSnapshotFiles(logDir).map(_.offset).sorted)
    // Inject a stray snapshot file within the bounds of the log at offset 3, it should be cleaned up after loading the log
    val straySnapshotFile = Log.producerSnapshotFile(logDir, 3).toPath
    Files.createFile(straySnapshotFile)
    assertEquals(Seq(1, 2, 3, 4), ProducerStateManager.listSnapshotFiles(logDir).map(_.offset).sorted)

    createLog(logDir, logConfig, lastShutdownClean = false)
    // We should clean up the stray producer state snapshot file, but keep the largest snapshot file (4)
    assertEquals(Seq(1, 2, 4), ProducerStateManager.listSnapshotFiles(logDir).map(_.offset).sorted)
  }

  @Test
  def testLoadProducersAfterDeleteRecordsOnSegment(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5)
    val log = createLog(logDir, logConfig)
    val pid1 = 1L
    val pid2 = 2L
    val epoch = 0.toShort

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord(mockTime.milliseconds(), "a".getBytes)), producerId = pid1,
      producerEpoch = epoch, sequence = 0), leaderEpoch = 0)
    log.roll()
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord(mockTime.milliseconds(), "b".getBytes)), producerId = pid2,
      producerEpoch = epoch, sequence = 0), leaderEpoch = 0)

    assertEquals(2, log.logSegments.size)
    assertEquals(2, log.activeProducersWithLastSequence.size)

    log.updateHighWatermark(log.logEndOffset)
    log.maybeIncrementLogStartOffset(1L, ClientRecordDeletion)
    log.deleteOldSegments()

    // Deleting records should not remove producer state
    assertEquals(1, log.logSegments.size)
    assertEquals(2, log.activeProducersWithLastSequence.size)
    val retainedLastSeqOpt = log.activeProducersWithLastSequence.get(pid2)
    assertTrue(retainedLastSeqOpt.isDefined)
    assertEquals(0, retainedLastSeqOpt.get)

    log.close()

    // After reloading log, producer state should not be regenerated
    val reloadedLog = createLog(logDir, logConfig, logStartOffset = 1L, lastShutdownClean = false)
    assertEquals(1, reloadedLog.activeProducersWithLastSequence.size)
    val reloadedEntryOpt = log.activeProducersWithLastSequence.get(pid2)
    assertEquals(retainedLastSeqOpt, reloadedEntryOpt)
  }

  @Test
  def testProducerIdMapTruncateFullyAndStartAt(): Unit = {
    val records = TestUtils.singletonRecords("foo".getBytes)
    val logConfig = LogTest.createLogConfig(segmentBytes = records.sizeInBytes, retentionBytes = records.sizeInBytes * 2)
    val log = createLog(logDir, logConfig)
    log.appendAsLeader(records, leaderEpoch = 0)
    log.takeProducerSnapshot()

    log.appendAsLeader(TestUtils.singletonRecords("bar".getBytes), leaderEpoch = 0)
    log.appendAsLeader(TestUtils.singletonRecords("baz".getBytes), leaderEpoch = 0)
    log.takeProducerSnapshot()

    assertEquals(3, log.logSegments.size)
    assertEquals(3, log.latestProducerStateEndOffset)
    assertEquals(Some(3), log.latestProducerSnapshotOffset)

    log.truncateFullyAndStartAt(29)
    assertEquals(1, log.logSegments.size)
    assertEquals(None, log.latestProducerSnapshotOffset)
    assertEquals(29, log.latestProducerStateEndOffset)
  }

  @Test
  def testProducerIdExpirationOnSegmentDeletion(): Unit = {
    val pid1 = 1L
    val records = TestUtils.records(Seq(new SimpleRecord("foo".getBytes)), producerId = pid1, producerEpoch = 0, sequence = 0)
    val logConfig = LogTest.createLogConfig(segmentBytes = records.sizeInBytes, retentionBytes = records.sizeInBytes * 2)
    val log = createLog(logDir, logConfig)
    log.appendAsLeader(records, leaderEpoch = 0)
    log.takeProducerSnapshot()

    val pid2 = 2L
    log.appendAsLeader(TestUtils.records(Seq(new SimpleRecord("bar".getBytes)), producerId = pid2, producerEpoch = 0, sequence = 0),
      leaderEpoch = 0)
    log.appendAsLeader(TestUtils.records(Seq(new SimpleRecord("baz".getBytes)), producerId = pid2, producerEpoch = 0, sequence = 1),
      leaderEpoch = 0)
    log.takeProducerSnapshot()

    assertEquals(3, log.logSegments.size)
    assertEquals(Set(pid1, pid2), log.activeProducersWithLastSequence.keySet)

    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()

    // Producer state should not be removed when deleting log segment
    assertEquals(2, log.logSegments.size)
    assertEquals(Set(pid1, pid2), log.activeProducersWithLastSequence.keySet)
  }

  @Test
  def testTakeSnapshotOnRollAndDeleteSnapshotOnRecoveryPointCheckpoint(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5)
    val log = createLog(logDir, logConfig)
    log.appendAsLeader(TestUtils.singletonRecords("a".getBytes), leaderEpoch = 0)
    log.roll(Some(1L))
    assertEquals(Some(1L), log.latestProducerSnapshotOffset)
    assertEquals(Some(1L), log.oldestProducerSnapshotOffset)

    log.appendAsLeader(TestUtils.singletonRecords("b".getBytes), leaderEpoch = 0)
    log.roll(Some(2L))
    assertEquals(Some(2L), log.latestProducerSnapshotOffset)
    assertEquals(Some(1L), log.oldestProducerSnapshotOffset)

    log.appendAsLeader(TestUtils.singletonRecords("c".getBytes), leaderEpoch = 0)
    log.roll(Some(3L))
    assertEquals(Some(3L), log.latestProducerSnapshotOffset)

    // roll triggers a flush at the starting offset of the new segment, we should retain all snapshots
    assertEquals(Some(1L), log.oldestProducerSnapshotOffset)

    // even if we flush within the active segment, the snapshot should remain
    log.appendAsLeader(TestUtils.singletonRecords("baz".getBytes), leaderEpoch = 0)
    log.flush(4L)
    assertEquals(Some(3L), log.latestProducerSnapshotOffset)
  }

  @Test
  def testProducerSnapshotAfterSegmentRollOnAppend(): Unit = {
    val producerId = 1L
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024)
    val log = createLog(logDir, logConfig)

    log.appendAsLeader(TestUtils.records(Seq(new SimpleRecord(mockTime.milliseconds(), new Array[Byte](512))),
      producerId = producerId, producerEpoch = 0, sequence = 0),
      leaderEpoch = 0)

    // The next append should overflow the segment and cause it to roll
    log.appendAsLeader(TestUtils.records(Seq(new SimpleRecord(mockTime.milliseconds(), new Array[Byte](512))),
      producerId = producerId, producerEpoch = 0, sequence = 1),
      leaderEpoch = 0)

    assertEquals(2, log.logSegments.size)
    assertEquals(1L, log.activeSegment.baseOffset)
    assertEquals(Some(1L), log.latestProducerSnapshotOffset)

    // Force a reload from the snapshot to check its consistency
    log.truncateTo(1L)

    assertEquals(2, log.logSegments.size)
    assertEquals(1L, log.activeSegment.baseOffset)
    assertTrue(log.activeSegment.log.batches.asScala.isEmpty)
    assertEquals(Some(1L), log.latestProducerSnapshotOffset)

    val lastEntry = log.producerStateManager.lastEntry(producerId)
    assertTrue(lastEntry.isDefined)
    assertEquals(0L, lastEntry.get.firstDataOffset)
    assertEquals(0L, lastEntry.get.lastDataOffset)
  }

  @Test
  def testRebuildTransactionalState(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)

    val pid = 137L
    val epoch = 5.toShort
    val seq = 0

    // add some transactional records
    val records = MemoryRecords.withTransactionalRecords(CompressionType.NONE, pid, epoch, seq,
      new SimpleRecord("foo".getBytes),
      new SimpleRecord("bar".getBytes),
      new SimpleRecord("baz".getBytes))
    log.appendAsLeader(records, leaderEpoch = 0)
    val abortAppendInfo = appendEndTxnMarkerAsLeader(log, pid, epoch, ControlRecordType.ABORT)
    log.updateHighWatermark(abortAppendInfo.lastOffset + 1)

    // now there should be no first unstable offset
    assertEquals(None, log.firstUnstableOffset)

    log.close()

    val reopenedLog = createLog(logDir, logConfig, lastShutdownClean = false)
    reopenedLog.updateHighWatermark(abortAppendInfo.lastOffset + 1)
    assertEquals(None, reopenedLog.firstUnstableOffset)
  }

  private def endTxnRecords(controlRecordType: ControlRecordType,
                            producerId: Long,
                            epoch: Short,
                            offset: Long = 0L,
                            coordinatorEpoch: Int,
                            partitionLeaderEpoch: Int = 0,
                            timestamp: Long): MemoryRecords = {
    val marker = new EndTransactionMarker(controlRecordType, coordinatorEpoch)
    MemoryRecords.withEndTransactionMarker(offset, timestamp, partitionLeaderEpoch, producerId, epoch, marker)
  }

  @Test
  def testPeriodicProducerIdExpiration(): Unit = {
    val maxProducerIdExpirationMs = 200
    val producerIdExpirationCheckIntervalMs = 100

    val pid = 23L
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5)
    val log = createLog(logDir, logConfig, maxProducerIdExpirationMs = maxProducerIdExpirationMs,
      producerIdExpirationCheckIntervalMs = producerIdExpirationCheckIntervalMs)
    val records = Seq(new SimpleRecord(mockTime.milliseconds(), "foo".getBytes))
    log.appendAsLeader(TestUtils.records(records, producerId = pid, producerEpoch = 0, sequence = 0), leaderEpoch = 0)

    assertEquals(Set(pid), log.activeProducersWithLastSequence.keySet)

    mockTime.sleep(producerIdExpirationCheckIntervalMs)
    assertEquals(Set(pid), log.activeProducersWithLastSequence.keySet)

    mockTime.sleep(producerIdExpirationCheckIntervalMs)
    assertEquals(Set(), log.activeProducersWithLastSequence.keySet)
  }

  @Test
  def testDuplicateAppends(): Unit = {
    // create a log
    val log = createLog(logDir, LogConfig())
    val pid = 1L
    val epoch: Short = 0

    var seq = 0
    // Pad the beginning of the log.
    for (_ <- 0 to 5) {
      val record = TestUtils.records(List(new SimpleRecord(mockTime.milliseconds, "key".getBytes, "value".getBytes)),
        producerId = pid, producerEpoch = epoch, sequence = seq)
      log.appendAsLeader(record, leaderEpoch = 0)
      seq = seq + 1
    }
    // Append an entry with multiple log records.
    def createRecords = TestUtils.records(List(
      new SimpleRecord(mockTime.milliseconds, s"key-$seq".getBytes, s"value-$seq".getBytes),
      new SimpleRecord(mockTime.milliseconds, s"key-$seq".getBytes, s"value-$seq".getBytes),
      new SimpleRecord(mockTime.milliseconds, s"key-$seq".getBytes, s"value-$seq".getBytes)
    ), producerId = pid, producerEpoch = epoch, sequence = seq)
    val multiEntryAppendInfo = log.appendAsLeader(createRecords, leaderEpoch = 0)
    assertEquals(
      multiEntryAppendInfo.lastOffset - multiEntryAppendInfo.firstOffset.get.messageOffset + 1,
      3,
      "should have appended 3 entries"
    )

    // Append a Duplicate of the tail, when the entry at the tail has multiple records.
    val dupMultiEntryAppendInfo = log.appendAsLeader(createRecords, leaderEpoch = 0)
    assertEquals(
      multiEntryAppendInfo.firstOffset.get.messageOffset,
      dupMultiEntryAppendInfo.firstOffset.get.messageOffset,
      "Somehow appended a duplicate entry with multiple log records to the tail"
    )
    assertEquals(multiEntryAppendInfo.lastOffset, dupMultiEntryAppendInfo.lastOffset,
      "Somehow appended a duplicate entry with multiple log records to the tail")

    seq = seq + 3

    // Append a partial duplicate of the tail. This is not allowed.
    var records = TestUtils.records(
      List(
        new SimpleRecord(mockTime.milliseconds, s"key-$seq".getBytes, s"value-$seq".getBytes),
        new SimpleRecord(mockTime.milliseconds, s"key-$seq".getBytes, s"value-$seq".getBytes)),
      producerId = pid, producerEpoch = epoch, sequence = seq - 2)
    assertThrows(classOf[OutOfOrderSequenceException], () => log.appendAsLeader(records, leaderEpoch = 0),
      () => "Should have received an OutOfOrderSequenceException since we attempted to append a duplicate of a records in the middle of the log.")

    // Append a duplicate of the batch which is 4th from the tail. This should succeed without error since we
    // retain the batch metadata of the last 5 batches.
    val duplicateOfFourth = TestUtils.records(List(new SimpleRecord(mockTime.milliseconds, "key".getBytes, "value".getBytes)),
      producerId = pid, producerEpoch = epoch, sequence = 2)
    log.appendAsLeader(duplicateOfFourth, leaderEpoch = 0)

    // Duplicates at older entries are reported as OutOfOrderSequence errors
    records = TestUtils.records(
      List(new SimpleRecord(mockTime.milliseconds, s"key-1".getBytes, s"value-1".getBytes)),
      producerId = pid, producerEpoch = epoch, sequence = 1)
    assertThrows(classOf[OutOfOrderSequenceException], () => log.appendAsLeader(records, leaderEpoch = 0),
      () => "Should have received an OutOfOrderSequenceException since we attempted to append a duplicate of a batch which is older than the last 5 appended batches.")

    // Append a duplicate entry with a single records at the tail of the log. This should return the appendInfo of the original entry.
    def createRecordsWithDuplicate = TestUtils.records(List(new SimpleRecord(mockTime.milliseconds, "key".getBytes, "value".getBytes)),
      producerId = pid, producerEpoch = epoch, sequence = seq)
    val origAppendInfo = log.appendAsLeader(createRecordsWithDuplicate, leaderEpoch = 0)
    val newAppendInfo = log.appendAsLeader(createRecordsWithDuplicate, leaderEpoch = 0)
    assertEquals(
      origAppendInfo.firstOffset.get.messageOffset,
      newAppendInfo.firstOffset.get.messageOffset,
      "Inserted a duplicate records into the log"
    )
    assertEquals(origAppendInfo.lastOffset, newAppendInfo.lastOffset,
      "Inserted a duplicate records into the log")
  }

  @Test
  def testMultipleProducerIdsPerMemoryRecord(): Unit = {
    // create a log
    val log = createLog(logDir, LogConfig())

    val epoch: Short = 0
    val buffer = ByteBuffer.allocate(512)

    var builder = MemoryRecords.builder(buffer, RecordBatch.MAGIC_VALUE_V2, CompressionType.NONE,
      TimestampType.LOG_APPEND_TIME, 0L, mockTime.milliseconds(), 1L, epoch, 0, false, 0)
    builder.append(new SimpleRecord("key".getBytes, "value".getBytes))
    builder.close()

    builder = MemoryRecords.builder(buffer, RecordBatch.MAGIC_VALUE_V2, CompressionType.NONE,
      TimestampType.LOG_APPEND_TIME, 1L, mockTime.milliseconds(), 2L, epoch, 0, false, 0)
    builder.append(new SimpleRecord("key".getBytes, "value".getBytes))
    builder.close()

    builder = MemoryRecords.builder(buffer, RecordBatch.MAGIC_VALUE_V2, CompressionType.NONE,
      TimestampType.LOG_APPEND_TIME, 2L, mockTime.milliseconds(), 3L, epoch, 0, false, 0)
    builder.append(new SimpleRecord("key".getBytes, "value".getBytes))
    builder.close()

    builder = MemoryRecords.builder(buffer, RecordBatch.MAGIC_VALUE_V2, CompressionType.NONE,
      TimestampType.LOG_APPEND_TIME, 3L, mockTime.milliseconds(), 4L, epoch, 0, false, 0)
    builder.append(new SimpleRecord("key".getBytes, "value".getBytes))
    builder.close()

    buffer.flip()
    val memoryRecords = MemoryRecords.readableRecords(buffer)

    log.appendAsFollower(memoryRecords)
    log.flush()

    val fetchedData = readLog(log, 0, Int.MaxValue)

    val origIterator = memoryRecords.batches.iterator()
    for (batch <- fetchedData.records.batches.asScala) {
      assertTrue(origIterator.hasNext)
      val origEntry = origIterator.next()
      assertEquals(origEntry.producerId, batch.producerId)
      assertEquals(origEntry.baseOffset, batch.baseOffset)
      assertEquals(origEntry.baseSequence, batch.baseSequence)
    }
  }

  @Test
  def testDuplicateAppendToFollower(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)
    val epoch: Short = 0
    val pid = 1L
    val baseSequence = 0
    val partitionLeaderEpoch = 0
    // The point of this test is to ensure that validation isn't performed on the follower.
    // this is a bit contrived. to trigger the duplicate case for a follower append, we have to append
    // a batch with matching sequence numbers, but valid increasing offsets
    assertEquals(0L, log.logEndOffset)
    log.appendAsFollower(MemoryRecords.withIdempotentRecords(0L, CompressionType.NONE, pid, epoch, baseSequence,
      partitionLeaderEpoch, new SimpleRecord("a".getBytes), new SimpleRecord("b".getBytes)))
    log.appendAsFollower(MemoryRecords.withIdempotentRecords(2L, CompressionType.NONE, pid, epoch, baseSequence,
      partitionLeaderEpoch, new SimpleRecord("a".getBytes), new SimpleRecord("b".getBytes)))

    // Ensure that even the duplicate sequences are accepted on the follower.
    assertEquals(4L, log.logEndOffset)
  }

  @Test
  def testMultipleProducersWithDuplicatesInSingleAppend(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)

    val pid1 = 1L
    val pid2 = 2L
    val epoch: Short = 0

    val buffer = ByteBuffer.allocate(512)

    // pid1 seq = 0
    var builder = MemoryRecords.builder(buffer, RecordBatch.CURRENT_MAGIC_VALUE, CompressionType.NONE,
      TimestampType.LOG_APPEND_TIME, 0L, mockTime.milliseconds(), pid1, epoch, 0)
    builder.append(new SimpleRecord("key".getBytes, "value".getBytes))
    builder.close()

    // pid2 seq = 0
    builder = MemoryRecords.builder(buffer, RecordBatch.CURRENT_MAGIC_VALUE, CompressionType.NONE,
      TimestampType.LOG_APPEND_TIME, 1L, mockTime.milliseconds(), pid2, epoch, 0)
    builder.append(new SimpleRecord("key".getBytes, "value".getBytes))
    builder.close()

    // pid1 seq = 1
    builder = MemoryRecords.builder(buffer, RecordBatch.CURRENT_MAGIC_VALUE, CompressionType.NONE,
      TimestampType.LOG_APPEND_TIME, 2L, mockTime.milliseconds(), pid1, epoch, 1)
    builder.append(new SimpleRecord("key".getBytes, "value".getBytes))
    builder.close()

    // pid2 seq = 1
    builder = MemoryRecords.builder(buffer, RecordBatch.CURRENT_MAGIC_VALUE, CompressionType.NONE,
      TimestampType.LOG_APPEND_TIME, 3L, mockTime.milliseconds(), pid2, epoch, 1)
    builder.append(new SimpleRecord("key".getBytes, "value".getBytes))
    builder.close()

    // // pid1 seq = 1 (duplicate)
    builder = MemoryRecords.builder(buffer, RecordBatch.CURRENT_MAGIC_VALUE, CompressionType.NONE,
      TimestampType.LOG_APPEND_TIME, 4L, mockTime.milliseconds(), pid1, epoch, 1)
    builder.append(new SimpleRecord("key".getBytes, "value".getBytes))
    builder.close()

    buffer.flip()

    val records = MemoryRecords.readableRecords(buffer)
    records.batches.forEach(_.setPartitionLeaderEpoch(0))

    // Ensure that batches with duplicates are accepted on the follower.
    assertEquals(0L, log.logEndOffset)
    log.appendAsFollower(records)
    assertEquals(5L, log.logEndOffset)
  }

  @Test
  def testOldProducerEpoch(): Unit = {
    // create a log
    val log = createLog(logDir, LogConfig())
    val pid = 1L
    val newEpoch: Short = 1
    val oldEpoch: Short = 0

    val records = TestUtils.records(List(new SimpleRecord(mockTime.milliseconds, "key".getBytes, "value".getBytes)), producerId = pid, producerEpoch = newEpoch, sequence = 0)
    log.appendAsLeader(records, leaderEpoch = 0)

    val nextRecords = TestUtils.records(List(new SimpleRecord(mockTime.milliseconds, "key".getBytes, "value".getBytes)), producerId = pid, producerEpoch = oldEpoch, sequence = 0)
    assertThrows(classOf[InvalidProducerEpochException], () => log.appendAsLeader(nextRecords, leaderEpoch = 0))
  }

  @Test
  def testDeleteSnapshotsOnIncrementLogStartOffset(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 2048 * 5)
    val log = createLog(logDir, logConfig)
    val pid1 = 1L
    val pid2 = 2L
    val epoch = 0.toShort

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord(mockTime.milliseconds(), "a".getBytes)), producerId = pid1,
      producerEpoch = epoch, sequence = 0), leaderEpoch = 0)
    log.roll()
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord(mockTime.milliseconds(), "b".getBytes)), producerId = pid2,
      producerEpoch = epoch, sequence = 0), leaderEpoch = 0)
    log.roll()

    assertEquals(2, log.activeProducersWithLastSequence.size)
    assertEquals(2, ProducerStateManager.listSnapshotFiles(log.dir).size)

    log.updateHighWatermark(log.logEndOffset)
    log.maybeIncrementLogStartOffset(2L, ClientRecordDeletion)
    log.deleteOldSegments() // force retention to kick in so that the snapshot files are cleaned up.
    mockTime.sleep(logConfig.fileDeleteDelayMs + 1000) // advance the clock so file deletion takes place

    // Deleting records should not remove producer state but should delete snapshots after the file deletion delay.
    assertEquals(2, log.activeProducersWithLastSequence.size)
    assertEquals(1, ProducerStateManager.listSnapshotFiles(log.dir).size)
    val retainedLastSeqOpt = log.activeProducersWithLastSequence.get(pid2)
    assertTrue(retainedLastSeqOpt.isDefined)
    assertEquals(0, retainedLastSeqOpt.get)
  }

  /**
   * Test for jitter s for time based log roll. This test appends messages then changes the time
   * using the mock clock to force the log to roll and checks the number of segments.
   */
  @Test
  def testTimeBasedLogRollJitter(): Unit = {
    var set = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds)
    val maxJitter = 20 * 60L
    // create a log
    val logConfig = LogTest.createLogConfig(segmentMs = 1 * 60 * 60L, segmentJitterMs = maxJitter)
    val log = createLog(logDir, logConfig)
    assertEquals(1, log.numberOfSegments, "Log begins with a single empty segment.")
    log.appendAsLeader(set, leaderEpoch = 0)

    mockTime.sleep(log.config.segmentMs - maxJitter)
    set = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds)
    log.appendAsLeader(set, leaderEpoch = 0)
    assertEquals(1, log.numberOfSegments,
      "Log does not roll on this append because it occurs earlier than max jitter")
    mockTime.sleep(maxJitter - log.activeSegment.rollJitterMs + 1)
    set = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds)
    log.appendAsLeader(set, leaderEpoch = 0)
    assertEquals(2, log.numberOfSegments,
      "Log should roll after segmentMs adjusted by random jitter")
  }

  /**
   * Test that appending more than the maximum segment size rolls the log
   */
  @Test
  def testSizeBasedLogRoll(): Unit = {
    def createRecords = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds)
    val setSize = createRecords.sizeInBytes
    val msgPerSeg = 10
    val segmentSize = msgPerSeg * (setSize - 1) // each segment will be 10 messages
    // create a log
    val logConfig = LogTest.createLogConfig(segmentBytes = segmentSize)
    val log = createLog(logDir, logConfig)
    assertEquals(1, log.numberOfSegments, "There should be exactly 1 segment.")

    // segments expire in size
    for (_ <- 1 to (msgPerSeg + 1))
      log.appendAsLeader(createRecords, leaderEpoch = 0)
    assertEquals(2, log.numberOfSegments,
      "There should be exactly 2 segments.")
  }

  /**
   * Test that we can open and append to an empty log
   */
  @Test
  def testLoadEmptyLog(): Unit = {
    createEmptyLogs(logDir, 0)
    val log = createLog(logDir, LogConfig())
    log.appendAsLeader(TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds), leaderEpoch = 0)
  }

  /**
   * This test case appends a bunch of messages and checks that we can read them all back using sequential offsets.
   */
  @Test
  def testAppendAndReadWithSequentialOffsets(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 71)
    val log = createLog(logDir, logConfig)
    val values = (0 until 100 by 2).map(id => id.toString.getBytes).toArray

    for(value <- values)
      log.appendAsLeader(TestUtils.singletonRecords(value = value), leaderEpoch = 0)

    for(i <- values.indices) {
      val read = readLog(log, i, 1).records.batches.iterator.next()
      assertEquals(i, read.lastOffset, "Offset read should match order appended.")
      val actual = read.iterator.next()
      assertNull(actual.key, "Key should be null")
      assertEquals(ByteBuffer.wrap(values(i)), actual.value, "Values not equal")
    }
    assertEquals(0, readLog(log, values.length, 100).records.batches.asScala.size,
      "Reading beyond the last message returns nothing.")
  }

  /**
   * This test appends a bunch of messages with non-sequential offsets and checks that we can an the correct message
   * from any offset less than the logEndOffset including offsets not appended.
   */
  @Test
  def testAppendAndReadWithNonSequentialOffsets(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 72)
    val log = createLog(logDir, logConfig)
    val messageIds = ((0 until 50) ++ (50 until 200 by 7)).toArray
    val records = messageIds.map(id => new SimpleRecord(id.toString.getBytes))

    // now test the case that we give the offsets and use non-sequential offsets
    for(i <- records.indices)
      log.appendAsFollower(MemoryRecords.withRecords(messageIds(i), CompressionType.NONE, 0, records(i)))
    for(i <- 50 until messageIds.max) {
      val idx = messageIds.indexWhere(_ >= i)
      val read = readLog(log, i, 100).records.records.iterator.next()
      assertEquals(messageIds(idx), read.offset, "Offset read should match message id.")
      assertEquals(records(idx), new SimpleRecord(read), "Message should match appended.")
    }
  }

  /**
   * This test covers an odd case where we have a gap in the offsets that falls at the end of a log segment.
   * Specifically we create a log where the last message in the first segment has offset 0. If we
   * then read offset 1, we should expect this read to come from the second segment, even though the
   * first segment has the greatest lower bound on the offset.
   */
  @Test
  def testReadAtLogGap(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 300)
    val log = createLog(logDir, logConfig)

    // keep appending until we have two segments with only a single message in the second segment
    while(log.numberOfSegments == 1)
      log.appendAsLeader(TestUtils.singletonRecords(value = "42".getBytes), leaderEpoch = 0)

    // now manually truncate off all but one message from the first segment to create a gap in the messages
    log.logSegments.head.truncateTo(1)

    assertEquals(log.logEndOffset - 1, readLog(log, 1, 200).records.batches.iterator.next().lastOffset,
      "A read should now return the last message in the log")
  }

  @Test
  def testLogRollAfterLogHandlerClosed(): Unit = {
    val logConfig = LogTest.createLogConfig()
    val log = createLog(logDir,  logConfig)
    log.closeHandlers()
    assertThrows(classOf[KafkaStorageException], () => log.roll(Some(1L)))
  }

  @Test
  def testReadWithMinMessage(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 72)
    val log = createLog(logDir,  logConfig)
    val messageIds = ((0 until 50) ++ (50 until 200 by 7)).toArray
    val records = messageIds.map(id => new SimpleRecord(id.toString.getBytes))

    // now test the case that we give the offsets and use non-sequential offsets
    for (i <- records.indices)
      log.appendAsFollower(MemoryRecords.withRecords(messageIds(i), CompressionType.NONE, 0, records(i)))

    for (i <- 50 until messageIds.max) {
      val idx = messageIds.indexWhere(_ >= i)
      val reads = Seq(
        readLog(log, i, 1),
        readLog(log, i, 100000),
        readLog(log, i, 100)
      ).map(_.records.records.iterator.next())
      reads.foreach { read =>
        assertEquals(messageIds(idx), read.offset, "Offset read should match message id.")
        assertEquals(records(idx), new SimpleRecord(read), "Message should match appended.")
      }
    }
  }

  @Test
  def testReadWithTooSmallMaxLength(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 72)
    val log = createLog(logDir,  logConfig)
    val messageIds = ((0 until 50) ++ (50 until 200 by 7)).toArray
    val records = messageIds.map(id => new SimpleRecord(id.toString.getBytes))

    // now test the case that we give the offsets and use non-sequential offsets
    for (i <- records.indices)
      log.appendAsFollower(MemoryRecords.withRecords(messageIds(i), CompressionType.NONE, 0, records(i)))

    for (i <- 50 until messageIds.max) {
      assertEquals(MemoryRecords.EMPTY, readLog(log, i, maxLength = 0, minOneMessage = false).records)

      // we return an incomplete message instead of an empty one for the case below
      // we use this mechanism to tell consumers of the fetch request version 2 and below that the message size is
      // larger than the fetch size
      // in fetch request version 3, we no longer need this as we return oversized messages from the first non-empty
      // partition
      val fetchInfo = readLog(log, i, maxLength = 1, minOneMessage = false)
      assertTrue(fetchInfo.firstEntryIncomplete)
      assertTrue(fetchInfo.records.isInstanceOf[FileRecords])
      assertEquals(1, fetchInfo.records.sizeInBytes)
    }
  }

  /**
   * Test reading at the boundary of the log, specifically
   * - reading from the logEndOffset should give an empty message set
   * - reading from the maxOffset should give an empty message set
   * - reading beyond the log end offset should throw an OffsetOutOfRangeException
   */
  @Test
  def testReadOutOfRange(): Unit = {
    createEmptyLogs(logDir, 1024)
    // set up replica log starting with offset 1024 and with one message (at offset 1024)
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024)
    val log = createLog(logDir, logConfig)
    log.appendAsLeader(TestUtils.singletonRecords(value = "42".getBytes), leaderEpoch = 0)

    assertEquals(0, readLog(log, 1025, 1000).records.sizeInBytes,
      "Reading at the log end offset should produce 0 byte read.")

    assertThrows(classOf[OffsetOutOfRangeException], () => readLog(log, 0, 1000))
    assertThrows(classOf[OffsetOutOfRangeException], () => readLog(log, 1026, 1000))
  }

  /**
   * Test that covers reads and writes on a multisegment log. This test appends a bunch of messages
   * and then reads them all back and checks that the message read and offset matches what was appended.
   */
  @Test
  def testLogRolls(): Unit = {
    /* create a multipart log with 100 messages */
    val logConfig = LogTest.createLogConfig(segmentBytes = 100)
    val log = createLog(logDir, logConfig)
    val numMessages = 100
    val messageSets = (0 until numMessages).map(i => TestUtils.singletonRecords(value = i.toString.getBytes,
                                                                                timestamp = mockTime.milliseconds))
    messageSets.foreach(log.appendAsLeader(_, leaderEpoch = 0))
    log.flush()

    /* do successive reads to ensure all our messages are there */
    var offset = 0L
    for(i <- 0 until numMessages) {
      val messages = readLog(log, offset, 1024*1024).records.batches
      val head = messages.iterator.next()
      assertEquals(offset, head.lastOffset, "Offsets not equal")

      val expected = messageSets(i).records.iterator.next()
      val actual = head.iterator.next()
      assertEquals(expected.key, actual.key, s"Keys not equal at offset $offset")
      assertEquals(expected.value, actual.value, s"Values not equal at offset $offset")
      assertEquals(expected.timestamp, actual.timestamp, s"Timestamps not equal at offset $offset")
      offset = head.lastOffset + 1
    }
    val lastRead = readLog(log, startOffset = numMessages, maxLength = 1024*1024).records
    assertEquals(0, lastRead.records.asScala.size, "Should be no more messages")

    // check that rolling the log forced a flushed, the flush is async so retry in case of failure
    TestUtils.retry(1000L){
      assertTrue(log.recoveryPoint >= log.activeSegment.baseOffset, "Log role should have forced flush")
    }
  }

  /**
   * Test reads at offsets that fall within compressed message set boundaries.
   */
  @Test
  def testCompressedMessages(): Unit = {
    /* this log should roll after every messageset */
    val logConfig = LogTest.createLogConfig(segmentBytes = 110)
    val log = createLog(logDir, logConfig)

    /* append 2 compressed message sets, each with two messages giving offsets 0, 1, 2, 3 */
    log.appendAsLeader(MemoryRecords.withRecords(CompressionType.GZIP, new SimpleRecord("hello".getBytes), new SimpleRecord("there".getBytes)), leaderEpoch = 0)
    log.appendAsLeader(MemoryRecords.withRecords(CompressionType.GZIP, new SimpleRecord("alpha".getBytes), new SimpleRecord("beta".getBytes)), leaderEpoch = 0)

    def read(offset: Int) = readLog(log, offset, 4096).records.records

    /* we should always get the first message in the compressed set when reading any offset in the set */
    assertEquals(0, read(0).iterator.next().offset, "Read at offset 0 should produce 0")
    assertEquals(0, read(1).iterator.next().offset, "Read at offset 1 should produce 0")
    assertEquals(2, read(2).iterator.next().offset, "Read at offset 2 should produce 2")
    assertEquals(2, read(3).iterator.next().offset, "Read at offset 3 should produce 2")
  }

  /**
   * Test garbage collecting old segments
   */
  @Test
  def testThatGarbageCollectingSegmentsDoesntChangeOffset(): Unit = {
    for(messagesToAppend <- List(0, 1, 25)) {
      logDir.mkdirs()
      // first test a log segment starting at 0
      val logConfig = LogTest.createLogConfig(segmentBytes = 100, retentionMs = 0)
      val log = createLog(logDir, logConfig)
      for(i <- 0 until messagesToAppend)
        log.appendAsLeader(TestUtils.singletonRecords(value = i.toString.getBytes, timestamp = mockTime.milliseconds - 10), leaderEpoch = 0)

      val currOffset = log.logEndOffset
      assertEquals(currOffset, messagesToAppend)

      // time goes by; the log file is deleted
      log.updateHighWatermark(currOffset)
      log.deleteOldSegments()

      assertEquals(currOffset, log.logEndOffset, "Deleting segments shouldn't have changed the logEndOffset")
      assertEquals(1, log.numberOfSegments, "We should still have one segment left")
      assertEquals(0, log.deleteOldSegments(), "Further collection shouldn't delete anything")
      assertEquals(currOffset, log.logEndOffset, "Still no change in the logEndOffset")
      assertEquals(
        currOffset, 
        log.appendAsLeader(
          TestUtils.singletonRecords(value = "hello".getBytes, timestamp = mockTime.milliseconds),
          leaderEpoch = 0
        ).firstOffset.get.messageOffset,
        "Should still be able to append and should get the logEndOffset assigned to the new append")

      // cleanup the log
      log.delete()
    }
  }

  /**
   *  MessageSet size shouldn't exceed the config.segmentSize, check that it is properly enforced by
   * appending a message set larger than the config.segmentSize setting and checking that an exception is thrown.
   */
  @Test
  def testMessageSetSizeCheck(): Unit = {
    val messageSet = MemoryRecords.withRecords(CompressionType.NONE, new SimpleRecord("You".getBytes), new SimpleRecord("bethe".getBytes))
    // append messages to log
    val configSegmentSize = messageSet.sizeInBytes - 1
    val logConfig = LogTest.createLogConfig(segmentBytes = configSegmentSize)
    val log = createLog(logDir, logConfig)

    assertThrows(classOf[RecordBatchTooLargeException], () => log.appendAsLeader(messageSet, leaderEpoch = 0))
  }

  @Test
  def testCompactedTopicConstraints(): Unit = {
    val keyedMessage = new SimpleRecord("and here it is".getBytes, "this message has a key".getBytes)
    val anotherKeyedMessage = new SimpleRecord("another key".getBytes, "this message also has a key".getBytes)
    val unkeyedMessage = new SimpleRecord("this message does not have a key".getBytes)

    val messageSetWithUnkeyedMessage = MemoryRecords.withRecords(CompressionType.NONE, unkeyedMessage, keyedMessage)
    val messageSetWithOneUnkeyedMessage = MemoryRecords.withRecords(CompressionType.NONE, unkeyedMessage)
    val messageSetWithCompressedKeyedMessage = MemoryRecords.withRecords(CompressionType.GZIP, keyedMessage)
    val messageSetWithCompressedUnkeyedMessage = MemoryRecords.withRecords(CompressionType.GZIP, keyedMessage, unkeyedMessage)

    val messageSetWithKeyedMessage = MemoryRecords.withRecords(CompressionType.NONE, keyedMessage)
    val messageSetWithKeyedMessages = MemoryRecords.withRecords(CompressionType.NONE, keyedMessage, anotherKeyedMessage)

    val logConfig = LogTest.createLogConfig(cleanupPolicy = LogConfig.Compact)
    val log = createLog(logDir, logConfig)

    val errorMsgPrefix = "Compacted topic cannot accept message without key"

    var e = assertThrows(classOf[RecordValidationException],
      () => log.appendAsLeader(messageSetWithUnkeyedMessage, leaderEpoch = 0))
    assertTrue(e.invalidException.isInstanceOf[InvalidRecordException])
    assertEquals(1, e.recordErrors.size)
    assertEquals(0, e.recordErrors.head.batchIndex)
    assertTrue(e.recordErrors.head.message.startsWith(errorMsgPrefix))

    e = assertThrows(classOf[RecordValidationException],
      () => log.appendAsLeader(messageSetWithOneUnkeyedMessage, leaderEpoch = 0))
    assertTrue(e.invalidException.isInstanceOf[InvalidRecordException])
    assertEquals(1, e.recordErrors.size)
    assertEquals(0, e.recordErrors.head.batchIndex)
    assertTrue(e.recordErrors.head.message.startsWith(errorMsgPrefix))

    e = assertThrows(classOf[RecordValidationException],
      () => log.appendAsLeader(messageSetWithCompressedUnkeyedMessage, leaderEpoch = 0))
    assertTrue(e.invalidException.isInstanceOf[InvalidRecordException])
    assertEquals(1, e.recordErrors.size)
    assertEquals(1, e.recordErrors.head.batchIndex)     // batch index is 1
    assertTrue(e.recordErrors.head.message.startsWith(errorMsgPrefix))

    // check if metric for NoKeyCompactedTopicRecordsPerSec is logged
    assertEquals(metricsKeySet.count(_.getMBeanName.endsWith(s"${BrokerTopicStats.NoKeyCompactedTopicRecordsPerSec}")), 1)
    assertTrue(TestUtils.meterCount(s"${BrokerTopicStats.NoKeyCompactedTopicRecordsPerSec}") > 0)

    // the following should succeed without any InvalidMessageException
    log.appendAsLeader(messageSetWithKeyedMessage, leaderEpoch = 0)
    log.appendAsLeader(messageSetWithKeyedMessages, leaderEpoch = 0)
    log.appendAsLeader(messageSetWithCompressedKeyedMessage, leaderEpoch = 0)
  }

  /**
   * We have a max size limit on message appends, check that it is properly enforced by appending a message larger than the
   * setting and checking that an exception is thrown.
   */
  @Test
  def testMessageSizeCheck(): Unit = {
    val first = MemoryRecords.withRecords(CompressionType.NONE, new SimpleRecord("You".getBytes), new SimpleRecord("bethe".getBytes))
    val second = MemoryRecords.withRecords(CompressionType.NONE,
      new SimpleRecord("change (I need more bytes)... blah blah blah.".getBytes),
      new SimpleRecord("More padding boo hoo".getBytes))

    // append messages to log
    val maxMessageSize = second.sizeInBytes - 1
    val logConfig = LogTest.createLogConfig(maxMessageBytes = maxMessageSize)
    val log = createLog(logDir, logConfig)

    // should be able to append the small message
    log.appendAsLeader(first, leaderEpoch = 0)

    assertThrows(classOf[RecordTooLargeException], () => log.appendAsLeader(second, leaderEpoch = 0),
      () => "Second message set should throw MessageSizeTooLargeException.")
  }

  @Test
  def testMessageSizeCheckInAppendAsFollower(): Unit = {
    val first = MemoryRecords.withRecords(0, CompressionType.NONE, 0,
      new SimpleRecord("You".getBytes), new SimpleRecord("bethe".getBytes))
    val second = MemoryRecords.withRecords(5, CompressionType.NONE, 0,
      new SimpleRecord("change (I need more bytes)... blah blah blah.".getBytes),
      new SimpleRecord("More padding boo hoo".getBytes))

    val log = createLog(logDir, LogTest.createLogConfig(maxMessageBytes = second.sizeInBytes - 1))

    log.appendAsFollower(first)
    // the second record is larger then limit but appendAsFollower does not validate the size.
    log.appendAsFollower(second)
  }

  /**
   * Append a bunch of messages to a log and then re-open it both with and without recovery and check that the log re-initializes correctly.
   */
  @Test
  def testLogRecoversToCorrectOffset(): Unit = {
    val numMessages = 100
    val messageSize = 100
    val segmentSize = 7 * messageSize
    val indexInterval = 3 * messageSize
    val logConfig = LogTest.createLogConfig(segmentBytes = segmentSize, indexIntervalBytes = indexInterval, segmentIndexBytes = 4096)
    var log = createLog(logDir, logConfig)
    for(i <- 0 until numMessages)
      log.appendAsLeader(TestUtils.singletonRecords(value = TestUtils.randomBytes(messageSize),
        timestamp = mockTime.milliseconds + i * 10), leaderEpoch = 0)
    assertEquals(numMessages, log.logEndOffset,
      "After appending %d messages to an empty log, the log end offset should be %d".format(numMessages, numMessages))
    val lastIndexOffset = log.activeSegment.offsetIndex.lastOffset
    val numIndexEntries = log.activeSegment.offsetIndex.entries
    val lastOffset = log.logEndOffset
    // After segment is closed, the last entry in the time index should be (largest timestamp -> last offset).
    val lastTimeIndexOffset = log.logEndOffset - 1
    val lastTimeIndexTimestamp  = log.activeSegment.largestTimestamp
    // Depending on when the last time index entry is inserted, an entry may or may not be inserted into the time index.
    val numTimeIndexEntries = log.activeSegment.timeIndex.entries + {
      if (log.activeSegment.timeIndex.lastEntry.offset == log.logEndOffset - 1) 0 else 1
    }
    log.close()

    def verifyRecoveredLog(log: Log, expectedRecoveryPoint: Long): Unit = {
      assertEquals(expectedRecoveryPoint, log.recoveryPoint, s"Unexpected recovery point")
      assertEquals(numMessages, log.logEndOffset, s"Should have $numMessages messages when log is reopened w/o recovery")
      assertEquals(lastIndexOffset, log.activeSegment.offsetIndex.lastOffset, "Should have same last index offset as before.")
      assertEquals(numIndexEntries, log.activeSegment.offsetIndex.entries, "Should have same number of index entries as before.")
      assertEquals(lastTimeIndexTimestamp, log.activeSegment.timeIndex.lastEntry.timestamp, "Should have same last time index timestamp")
      assertEquals(lastTimeIndexOffset, log.activeSegment.timeIndex.lastEntry.offset, "Should have same last time index offset")
      assertEquals(numTimeIndexEntries, log.activeSegment.timeIndex.entries, "Should have same number of time index entries as before.")
    }

    log = createLog(logDir, logConfig, recoveryPoint = lastOffset, lastShutdownClean = false)
    verifyRecoveredLog(log, lastOffset)
    log.close()

    // test recovery case
    val recoveryPoint = 10
    log = createLog(logDir, logConfig, recoveryPoint = recoveryPoint, lastShutdownClean = false)
    // the recovery point should not be updated after unclean shutdown until the log is flushed
    verifyRecoveredLog(log, recoveryPoint)
    log.flush()
    verifyRecoveredLog(log, lastOffset)
    log.close()
  }

  @Test
  def testNoOpWhenKeepPartitionMetadataFileIsFalse(): Unit = {
    val logConfig = LogTest.createLogConfig()
    val log = createLog(logDir, logConfig, keepPartitionMetadataFile = false)

    val topicId = Uuid.randomUuid()
    log.assignTopicId(topicId)

    // We should not write to this file or set the topic ID
    assertFalse(log.partitionMetadataFile.exists())
    assertEquals(Uuid.ZERO_UUID, log.topicId)
    log.close()
  }

  @Test
  def testLogRecoversTopicId(): Unit = {
    val logConfig = LogTest.createLogConfig()
    var log = createLog(logDir, logConfig)

    val topicId = Uuid.randomUuid()
    log.partitionMetadataFile.write(topicId)
    log.close()

    // test recovery case
    log = createLog(logDir, logConfig)
    assertTrue(log.topicId == topicId)
    log.close()
  }

  /**
   * Test building the time index on the follower by setting assignOffsets to false.
   */
  @Test
  def testBuildTimeIndexWhenNotAssigningOffsets(): Unit = {
    val numMessages = 100
    val logConfig = LogTest.createLogConfig(segmentBytes = 10000, indexIntervalBytes = 1)
    val log = createLog(logDir, logConfig)

    val messages = (0 until numMessages).map { i =>
      MemoryRecords.withRecords(100 + i, CompressionType.NONE, 0, new SimpleRecord(mockTime.milliseconds + i, i.toString.getBytes()))
    }
    messages.foreach(log.appendAsFollower)
    val timeIndexEntries = log.logSegments.foldLeft(0) { (entries, segment) => entries + segment.timeIndex.entries }
    assertEquals(numMessages - 1, timeIndexEntries, s"There should be ${numMessages - 1} time index entries")
    assertEquals(mockTime.milliseconds + numMessages - 1, log.activeSegment.timeIndex.lastEntry.timestamp,
      s"The last time index entry should have timestamp ${mockTime.milliseconds + numMessages - 1}")
  }

  /**
   * Test that if we manually delete an index segment it is rebuilt when the log is re-opened
   */
  @Test
  def testIndexRebuild(): Unit = {
    // publish the messages and close the log
    val numMessages = 200
    val logConfig = LogTest.createLogConfig(segmentBytes = 200, indexIntervalBytes = 1)
    var log = createLog(logDir, logConfig)
    for(i <- 0 until numMessages)
      log.appendAsLeader(TestUtils.singletonRecords(value = TestUtils.randomBytes(10), timestamp = mockTime.milliseconds + i * 10), leaderEpoch = 0)
    val indexFiles = log.logSegments.map(_.lazyOffsetIndex.file)
    val timeIndexFiles = log.logSegments.map(_.lazyTimeIndex.file)
    log.close()

    // delete all the index files
    indexFiles.foreach(_.delete())
    timeIndexFiles.foreach(_.delete())

    // reopen the log
    log = createLog(logDir, logConfig, lastShutdownClean = false)
    assertEquals(numMessages, log.logEndOffset, "Should have %d messages when log is reopened".format(numMessages))
    assertTrue(log.logSegments.head.offsetIndex.entries > 0, "The index should have been rebuilt")
    assertTrue(log.logSegments.head.timeIndex.entries > 0, "The time index should have been rebuilt")
    for(i <- 0 until numMessages) {
      assertEquals(i, readLog(log, i, 100).records.batches.iterator.next().lastOffset)
      if (i == 0)
        assertEquals(log.logSegments.head.baseOffset, log.fetchOffsetByTimestamp(mockTime.milliseconds + i * 10).get.offset)
      else
        assertEquals(i, log.fetchOffsetByTimestamp(mockTime.milliseconds + i * 10).get.offset)
    }
    log.close()
  }

  @Test
  def testFetchOffsetByTimestampIncludesLeaderEpoch(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 200, indexIntervalBytes = 1)
    val log = createLog(logDir, logConfig)

    assertEquals(None, log.fetchOffsetByTimestamp(0L))

    val firstTimestamp = mockTime.milliseconds
    val firstLeaderEpoch = 0
    log.appendAsLeader(TestUtils.singletonRecords(
      value = TestUtils.randomBytes(10),
      timestamp = firstTimestamp),
      leaderEpoch = firstLeaderEpoch)

    val secondTimestamp = firstTimestamp + 1
    val secondLeaderEpoch = 1
    log.appendAsLeader(TestUtils.singletonRecords(
      value = TestUtils.randomBytes(10),
      timestamp = secondTimestamp),
      leaderEpoch = secondLeaderEpoch)

    assertEquals(Some(new TimestampAndOffset(firstTimestamp, 0L, Optional.of(firstLeaderEpoch))),
      log.fetchOffsetByTimestamp(firstTimestamp))
    assertEquals(Some(new TimestampAndOffset(secondTimestamp, 1L, Optional.of(secondLeaderEpoch))),
      log.fetchOffsetByTimestamp(secondTimestamp))

    assertEquals(Some(new TimestampAndOffset(ListOffsetsResponse.UNKNOWN_TIMESTAMP, 0L, Optional.of(firstLeaderEpoch))),
      log.fetchOffsetByTimestamp(ListOffsetsRequest.EARLIEST_TIMESTAMP))
    assertEquals(Some(new TimestampAndOffset(ListOffsetsResponse.UNKNOWN_TIMESTAMP, 2L, Optional.of(secondLeaderEpoch))),
      log.fetchOffsetByTimestamp(ListOffsetsRequest.LATEST_TIMESTAMP))

    // The cache can be updated directly after a leader change.
    // The new latest offset should reflect the updated epoch.
    log.maybeAssignEpochStartOffset(2, 2L)

    assertEquals(Some(new TimestampAndOffset(ListOffsetsResponse.UNKNOWN_TIMESTAMP, 2L, Optional.of(2))),
      log.fetchOffsetByTimestamp(ListOffsetsRequest.LATEST_TIMESTAMP))
  }

  /**
   * Test that if messages format version of the messages in a segment is before 0.10.0, the time index should be empty.
   */
  @Test
  def testRebuildTimeIndexForOldMessages(): Unit = {
    val numMessages = 200
    val segmentSize = 200
    val logConfig = LogTest.createLogConfig(segmentBytes = segmentSize, indexIntervalBytes = 1, messageFormatVersion = "0.9.0")
    var log = createLog(logDir, logConfig)
    for (i <- 0 until numMessages)
      log.appendAsLeader(TestUtils.singletonRecords(value = TestUtils.randomBytes(10),
        timestamp = mockTime.milliseconds + i * 10, magicValue = RecordBatch.MAGIC_VALUE_V1), leaderEpoch = 0)
    val timeIndexFiles = log.logSegments.map(_.lazyTimeIndex.file)
    log.close()

    // Delete the time index.
    timeIndexFiles.foreach(file => Files.delete(file.toPath))

    // The rebuilt time index should be empty
    log = createLog(logDir, logConfig, recoveryPoint = numMessages + 1, lastShutdownClean = false)
    for (segment <- log.logSegments.init) {
      assertEquals(0, segment.timeIndex.entries, "The time index should be empty")
      assertEquals(0, segment.lazyTimeIndex.file.length, "The time index file size should be 0")
    }
  }

  /**
   * Test that if we have corrupted an index segment it is rebuilt when the log is re-opened
   */
  @Test
  def testCorruptIndexRebuild(): Unit = {
    // publish the messages and close the log
    val numMessages = 200
    val logConfig = LogTest.createLogConfig(segmentBytes = 200, indexIntervalBytes = 1)
    var log = createLog(logDir, logConfig)
    for(i <- 0 until numMessages)
      log.appendAsLeader(TestUtils.singletonRecords(value = TestUtils.randomBytes(10), timestamp = mockTime.milliseconds + i * 10), leaderEpoch = 0)
    val indexFiles = log.logSegments.map(_.lazyOffsetIndex.file)
    val timeIndexFiles = log.logSegments.map(_.lazyTimeIndex.file)
    log.close()

    // corrupt all the index files
    for( file <- indexFiles) {
      val bw = new BufferedWriter(new FileWriter(file))
      bw.write("  ")
      bw.close()
    }

    // corrupt all the index files
    for( file <- timeIndexFiles) {
      val bw = new BufferedWriter(new FileWriter(file))
      bw.write("  ")
      bw.close()
    }

    // reopen the log with recovery point=0 so that the segment recovery can be triggered
    log = createLog(logDir, logConfig, lastShutdownClean = false)
    assertEquals(numMessages, log.logEndOffset, "Should have %d messages when log is reopened".format(numMessages))
    for(i <- 0 until numMessages) {
      assertEquals(i, readLog(log, i, 100).records.batches.iterator.next().lastOffset)
      if (i == 0)
        assertEquals(log.logSegments.head.baseOffset, log.fetchOffsetByTimestamp(mockTime.milliseconds + i * 10).get.offset)
      else
        assertEquals(i, log.fetchOffsetByTimestamp(mockTime.milliseconds + i * 10).get.offset)
    }
    log.close()
  }

  /**
   * Test the Log truncate operations
   */
  @Test
  def testTruncateTo(): Unit = {
    def createRecords = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds)
    val setSize = createRecords.sizeInBytes
    val msgPerSeg = 10
    val segmentSize = msgPerSeg * setSize  // each segment will be 10 messages

    // create a log
    val logConfig = LogTest.createLogConfig(segmentBytes = segmentSize)
    val log = createLog(logDir, logConfig)
    assertEquals(1, log.numberOfSegments, "There should be exactly 1 segment.")

    for (_ <- 1 to msgPerSeg)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    assertEquals(1, log.numberOfSegments, "There should be exactly 1 segments.")
    assertEquals(msgPerSeg, log.logEndOffset, "Log end offset should be equal to number of messages")

    val lastOffset = log.logEndOffset
    val size = log.size
    log.truncateTo(log.logEndOffset) // keep the entire log
    assertEquals(lastOffset, log.logEndOffset, "Should not change offset")
    assertEquals(size, log.size, "Should not change log size")
    log.truncateTo(log.logEndOffset + 1) // try to truncate beyond lastOffset
    assertEquals(lastOffset, log.logEndOffset, "Should not change offset but should log error")
    assertEquals(size, log.size, "Should not change log size")
    log.truncateTo(msgPerSeg/2) // truncate somewhere in between
    assertEquals(log.logEndOffset, msgPerSeg/2, "Should change offset")
    assertTrue(log.size < size, "Should change log size")
    log.truncateTo(0) // truncate the entire log
    assertEquals(0, log.logEndOffset, "Should change offset")
    assertEquals(0, log.size, "Should change log size")

    for (_ <- 1 to msgPerSeg)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    assertEquals(log.logEndOffset, lastOffset, "Should be back to original offset")
    assertEquals(log.size, size, "Should be back to original size")
    log.truncateFullyAndStartAt(log.logEndOffset - (msgPerSeg - 1))
    assertEquals(log.logEndOffset, lastOffset - (msgPerSeg - 1), "Should change offset")
    assertEquals(log.size, 0, "Should change log size")

    for (_ <- 1 to msgPerSeg)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    assertTrue(log.logEndOffset > msgPerSeg, "Should be ahead of to original offset")
    assertEquals(size, log.size, "log size should be same as before")
    log.truncateTo(0) // truncate before first start offset in the log
    assertEquals(0, log.logEndOffset, "Should change offset")
    assertEquals(log.size, 0, "Should change log size")
  }

  /**
   * Verify that when we truncate a log the index of the last segment is resized to the max index size to allow more appends
   */
  @Test
  def testIndexResizingAtTruncation(): Unit = {
    val setSize = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds).sizeInBytes
    val msgPerSeg = 10
    val segmentSize = msgPerSeg * setSize  // each segment will be 10 messages
    val logConfig = LogTest.createLogConfig(segmentBytes = segmentSize, indexIntervalBytes = setSize - 1)
    val log = createLog(logDir, logConfig)
    assertEquals(1, log.numberOfSegments, "There should be exactly 1 segment.")

    for (i<- 1 to msgPerSeg)
      log.appendAsLeader(TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds + i), leaderEpoch = 0)
    assertEquals(1, log.numberOfSegments, "There should be exactly 1 segment.")

    mockTime.sleep(msgPerSeg)
    for (i<- 1 to msgPerSeg)
      log.appendAsLeader(TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds + i), leaderEpoch = 0)
    assertEquals(2, log.numberOfSegments, "There should be exactly 2 segment.")
    val expectedEntries = msgPerSeg - 1

    assertEquals(expectedEntries, log.logSegments.toList.head.offsetIndex.maxEntries,
      s"The index of the first segment should have $expectedEntries entries")
    assertEquals(expectedEntries, log.logSegments.toList.head.timeIndex.maxEntries,
      s"The time index of the first segment should have $expectedEntries entries")

    log.truncateTo(0)
    assertEquals(1, log.numberOfSegments, "There should be exactly 1 segment.")
    assertEquals(log.config.maxIndexSize/8, log.logSegments.toList.head.offsetIndex.maxEntries,
      "The index of segment 1 should be resized to maxIndexSize")
    assertEquals(log.config.maxIndexSize/12, log.logSegments.toList.head.timeIndex.maxEntries,
      "The time index of segment 1 should be resized to maxIndexSize")

    mockTime.sleep(msgPerSeg)
    for (i<- 1 to msgPerSeg)
      log.appendAsLeader(TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds + i), leaderEpoch = 0)
    assertEquals(1, log.numberOfSegments,
      "There should be exactly 1 segment.")
  }

  /**
   * When we open a log any index segments without an associated log segment should be deleted.
   */
  @Test
  def testBogusIndexSegmentsAreRemoved(): Unit = {
    val bogusIndex1 = Log.offsetIndexFile(logDir, 0)
    val bogusTimeIndex1 = Log.timeIndexFile(logDir, 0)
    val bogusIndex2 = Log.offsetIndexFile(logDir, 5)
    val bogusTimeIndex2 = Log.timeIndexFile(logDir, 5)

    // The files remain absent until we first access it because we are doing lazy loading for time index and offset index
    // files but in this test case we need to create these files in order to test we will remove them.
    bogusIndex2.createNewFile()
    bogusTimeIndex2.createNewFile()

    def createRecords = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, segmentIndexBytes = 1000, indexIntervalBytes = 1)
    val log = createLog(logDir, logConfig)

    // Force the segment to access the index files because we are doing index lazy loading.
    log.logSegments.toSeq.head.offsetIndex
    log.logSegments.toSeq.head.timeIndex

    assertTrue(bogusIndex1.length > 0,
      "The first index file should have been replaced with a larger file")
    assertTrue(bogusTimeIndex1.length > 0,
      "The first time index file should have been replaced with a larger file")
    assertFalse(bogusIndex2.exists,
      "The second index file should have been deleted.")
    assertFalse(bogusTimeIndex2.exists,
      "The second time index file should have been deleted.")

    // check that we can append to the log
    for (_ <- 0 until 10)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    log.delete()
  }

  /**
   * Verify that truncation works correctly after re-opening the log
   */
  @Test
  def testReopenThenTruncate(): Unit = {
    def createRecords = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds)
    // create a log
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, segmentIndexBytes = 1000, indexIntervalBytes = 10000)
    var log = createLog(logDir, logConfig)

    // add enough messages to roll over several segments then close and re-open and attempt to truncate
    for (_ <- 0 until 100)
      log.appendAsLeader(createRecords, leaderEpoch = 0)
    log.close()
    log = createLog(logDir, logConfig, lastShutdownClean = false)
    log.truncateTo(3)
    assertEquals(1, log.numberOfSegments, "All but one segment should be deleted.")
    assertEquals(3, log.logEndOffset, "Log end offset should be 3.")
  }

  /**
   * Test that deleted files are deleted after the appropriate time.
   */
  @Test
  def testAsyncDelete(): Unit = {
    def createRecords = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds - 1000L)
    val asyncDeleteMs = 1000
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, segmentIndexBytes = 1000, indexIntervalBytes = 10000,
                                    retentionMs = 999, fileDeleteDelayMs = asyncDeleteMs)
    val log = createLog(logDir, logConfig)

    // append some messages to create some segments
    for (_ <- 0 until 100)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    // files should be renamed
    val segments = log.logSegments.toArray
    val oldFiles = segments.map(_.log.file) ++ segments.map(_.lazyOffsetIndex.file)

    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()

    assertEquals(1, log.numberOfSegments, "Only one segment should remain.")
    assertTrue(segments.forall(_.log.file.getName.endsWith(Log.DeletedFileSuffix)) &&
      segments.forall(_.lazyOffsetIndex.file.getName.endsWith(Log.DeletedFileSuffix)),
      "All log and index files should end in .deleted")
    assertTrue(segments.forall(_.log.file.exists) && segments.forall(_.lazyOffsetIndex.file.exists),
      "The .deleted files should still be there.")
    assertTrue(oldFiles.forall(!_.exists), "The original file should be gone.")

    // when enough time passes the files should be deleted
    val deletedFiles = segments.map(_.log.file) ++ segments.map(_.lazyOffsetIndex.file)
    mockTime.sleep(asyncDeleteMs + 1)
    assertTrue(deletedFiles.forall(!_.exists), "Files should all be gone.")
  }

  /**
   * Any files ending in .deleted should be removed when the log is re-opened.
   */
  @Test
  def testOpenDeletesObsoleteFiles(): Unit = {
    def createRecords = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds - 1000)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, segmentIndexBytes = 1000, retentionMs = 999)
    var log = createLog(logDir, logConfig)

    // append some messages to create some segments
    for (_ <- 0 until 100)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    // expire all segments
    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()
    log.close()
    log = createLog(logDir, logConfig, lastShutdownClean = false)
    assertEquals(1, log.numberOfSegments, "The deleted segments should be gone.")
  }

  @Test
  def testAppendMessageWithNullPayload(): Unit = {
    val log = createLog(logDir, LogConfig())
    log.appendAsLeader(TestUtils.singletonRecords(value = null), leaderEpoch = 0)
    val head = readLog(log, 0, 4096).records.records.iterator.next()
    assertEquals(0, head.offset)
    assertFalse(head.hasValue, "Message payload should be null.")
  }

  @Test
  def testAppendWithOutOfOrderOffsetsThrowsException(): Unit = {
    val log = createLog(logDir, LogConfig())

    val appendOffsets = Seq(0L, 1L, 3L, 2L, 4L)
    val buffer = ByteBuffer.allocate(512)
    for (offset <- appendOffsets) {
      val builder = MemoryRecords.builder(buffer, RecordBatch.MAGIC_VALUE_V2, CompressionType.NONE,
                                          TimestampType.LOG_APPEND_TIME, offset, mockTime.milliseconds(),
                                          1L, 0, 0, false, 0)
      builder.append(new SimpleRecord("key".getBytes, "value".getBytes))
      builder.close()
    }
    buffer.flip()
    val memoryRecords = MemoryRecords.readableRecords(buffer)

    assertThrows(classOf[OffsetsOutOfOrderException], () =>
      log.appendAsFollower(memoryRecords)
    )
  }

  @Test
  def testAppendBelowExpectedOffsetThrowsException(): Unit = {
    val log = createLog(logDir, LogConfig())
    val records = (0 until 2).map(id => new SimpleRecord(id.toString.getBytes)).toArray
    records.foreach(record => log.appendAsLeader(MemoryRecords.withRecords(CompressionType.NONE, record), leaderEpoch = 0))

    val magicVals = Seq(RecordBatch.MAGIC_VALUE_V0, RecordBatch.MAGIC_VALUE_V1, RecordBatch.MAGIC_VALUE_V2)
    val compressionTypes = Seq(CompressionType.NONE, CompressionType.LZ4)
    for (magic <- magicVals; compression <- compressionTypes) {
      val invalidRecord = MemoryRecords.withRecords(magic, compression, new SimpleRecord(1.toString.getBytes))
      assertThrows(classOf[UnexpectedAppendOffsetException],
        () => log.appendAsFollower(invalidRecord),
        () => s"Magic=$magic, compressionType=$compression")
    }
  }

  @Test
  def testAppendEmptyLogBelowLogStartOffsetThrowsException(): Unit = {
    createEmptyLogs(logDir, 7)
    val log = createLog(logDir, LogConfig(), brokerTopicStats = brokerTopicStats)
    assertEquals(7L, log.logStartOffset)
    assertEquals(7L, log.logEndOffset)

    val firstOffset = 4L
    val magicVals = Seq(RecordBatch.MAGIC_VALUE_V0, RecordBatch.MAGIC_VALUE_V1, RecordBatch.MAGIC_VALUE_V2)
    val compressionTypes = Seq(CompressionType.NONE, CompressionType.LZ4)
    for (magic <- magicVals; compression <- compressionTypes) {
      val batch = TestUtils.records(List(new SimpleRecord("k1".getBytes, "v1".getBytes),
                                         new SimpleRecord("k2".getBytes, "v2".getBytes),
                                         new SimpleRecord("k3".getBytes, "v3".getBytes)),
                                    magicValue = magic, codec = compression,
                                    baseOffset = firstOffset)

      val exception = assertThrows(classOf[UnexpectedAppendOffsetException], () => log.appendAsFollower(records = batch))
      assertEquals(firstOffset, exception.firstOffset, s"Magic=$magic, compressionType=$compression, UnexpectedAppendOffsetException#firstOffset")
      assertEquals(firstOffset + 2, exception.lastOffset, s"Magic=$magic, compressionType=$compression, UnexpectedAppendOffsetException#lastOffset")
    }
  }

  @Test
  def testAppendWithNoTimestamp(): Unit = {
    val log = createLog(logDir, LogConfig())
    log.appendAsLeader(MemoryRecords.withRecords(CompressionType.NONE,
      new SimpleRecord(RecordBatch.NO_TIMESTAMP, "key".getBytes, "value".getBytes)), leaderEpoch = 0)
  }

  @Test
  def testAppendToOrReadFromLogInFailedLogDir(): Unit = {
    val pid = 1L
    val epoch = 0.toShort
    val log = createLog(logDir, LogConfig())
    log.appendAsLeader(TestUtils.singletonRecords(value = null), leaderEpoch = 0)
    assertEquals(0, readLog(log, 0, 4096).records.records.iterator.next().offset)
    val append = appendTransactionalAsLeader(log, pid, epoch)
    append(10)
    // Kind of a hack, but renaming the index to a directory ensures that the append
    // to the index will fail.
    log.activeSegment.txnIndex.renameTo(log.dir)
    assertThrows(classOf[KafkaStorageException], () => appendEndTxnMarkerAsLeader(log, pid, epoch, ControlRecordType.ABORT, coordinatorEpoch = 1))
    assertThrows(classOf[KafkaStorageException], () => log.appendAsLeader(TestUtils.singletonRecords(value = null), leaderEpoch = 0))
    assertThrows(classOf[KafkaStorageException], () => readLog(log, 0, 4096).records.records.iterator.next().offset)
  }

  @Test
  def testCorruptLog(): Unit = {
    // append some messages to create some segments
    val logConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1, maxMessageBytes = 64 * 1024)
    def createRecords = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds)
    val recoveryPoint = 50L
    for (_ <- 0 until 10) {
      // create a log and write some messages to it
      logDir.mkdirs()
      var log = createLog(logDir, logConfig)
      val numMessages = 50 + TestUtils.random.nextInt(50)
      for (_ <- 0 until numMessages)
        log.appendAsLeader(createRecords, leaderEpoch = 0)
      val records = log.logSegments.flatMap(_.log.records.asScala.toList).toList
      log.close()

      // corrupt index and log by appending random bytes
      TestUtils.appendNonsenseToFile(log.activeSegment.lazyOffsetIndex.file, TestUtils.random.nextInt(1024) + 1)
      TestUtils.appendNonsenseToFile(log.activeSegment.log.file, TestUtils.random.nextInt(1024) + 1)

      // attempt recovery
      log = createLog(logDir, logConfig, brokerTopicStats, 0L, recoveryPoint, lastShutdownClean = false)
      assertEquals(numMessages, log.logEndOffset)

      val recovered = log.logSegments.flatMap(_.log.records.asScala.toList).toList
      assertEquals(records.size, recovered.size)

      for (i <- records.indices) {
        val expected = records(i)
        val actual = recovered(i)
        assertEquals(expected.key, actual.key, s"Keys not equal")
        assertEquals(expected.value, actual.value, s"Values not equal")
        assertEquals(expected.timestamp, actual.timestamp, s"Timestamps not equal")
      }

      Utils.delete(logDir)
    }
  }

  @Test
  def testOverCompactedLogRecovery(): Unit = {
    // append some messages to create some segments
    val logConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1, maxMessageBytes = 64 * 1024)
    val log = createLog(logDir, logConfig)
    val set1 = MemoryRecords.withRecords(0, CompressionType.NONE, 0, new SimpleRecord("v1".getBytes(), "k1".getBytes()))
    val set2 = MemoryRecords.withRecords(Integer.MAX_VALUE.toLong + 2, CompressionType.NONE, 0, new SimpleRecord("v3".getBytes(), "k3".getBytes()))
    val set3 = MemoryRecords.withRecords(Integer.MAX_VALUE.toLong + 3, CompressionType.NONE, 0, new SimpleRecord("v4".getBytes(), "k4".getBytes()))
    val set4 = MemoryRecords.withRecords(Integer.MAX_VALUE.toLong + 4, CompressionType.NONE, 0, new SimpleRecord("v5".getBytes(), "k5".getBytes()))
    //Writes into an empty log with baseOffset 0
    log.appendAsFollower(set1)
    assertEquals(0L, log.activeSegment.baseOffset)
    //This write will roll the segment, yielding a new segment with base offset = max(1, Integer.MAX_VALUE+2) = Integer.MAX_VALUE+2
    log.appendAsFollower(set2)
    assertEquals(Integer.MAX_VALUE.toLong + 2, log.activeSegment.baseOffset)
    assertTrue(Log.producerSnapshotFile(logDir, Integer.MAX_VALUE.toLong + 2).exists)
    //This will go into the existing log
    log.appendAsFollower(set3)
    assertEquals(Integer.MAX_VALUE.toLong + 2, log.activeSegment.baseOffset)
    //This will go into the existing log
    log.appendAsFollower(set4)
    assertEquals(Integer.MAX_VALUE.toLong + 2, log.activeSegment.baseOffset)
    log.close()
    val indexFiles = logDir.listFiles.filter(file => file.getName.contains(".index"))
    assertEquals(2, indexFiles.length)
    for (file <- indexFiles) {
      val offsetIndex = new OffsetIndex(file, file.getName.replace(".index","").toLong)
      assertTrue(offsetIndex.lastOffset >= 0)
      offsetIndex.close()
    }
    Utils.delete(logDir)
  }

  @Test
  def testWriteLeaderEpochCheckpointAfterDirectoryRename(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1, maxMessageBytes = 64 * 1024)
    val log = createLog(logDir, logConfig)
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("foo".getBytes()))), leaderEpoch = 5)
    assertEquals(Some(5), log.latestEpoch)

    // Ensure that after a directory rename, the epoch cache is written to the right location
    val tp = Log.parseTopicPartitionName(log.dir)
    log.renameDir(Log.logDeleteDirName(tp))
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("foo".getBytes()))), leaderEpoch = 10)
    assertEquals(Some(10), log.latestEpoch)
    assertTrue(LeaderEpochCheckpointFile.newFile(log.dir).exists())
    assertFalse(LeaderEpochCheckpointFile.newFile(this.logDir).exists())
  }

  @Test
  def testTopicIdTransfersAfterDirectoryRename(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1, maxMessageBytes = 64 * 1024)
    val log = createLog(logDir, logConfig)

    // Write a topic ID to the partition metadata file to ensure it is transferred correctly.
    val id = Uuid.randomUuid()
    log.topicId = id
    log.partitionMetadataFile.write(id)

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("foo".getBytes()))), leaderEpoch = 5)
    assertEquals(Some(5), log.latestEpoch)

    // Ensure that after a directory rename, the partition metadata file is written to the right location.
    val tp = Log.parseTopicPartitionName(log.dir)
    log.renameDir(Log.logDeleteDirName(tp))
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("foo".getBytes()))), leaderEpoch = 10)
    assertEquals(Some(10), log.latestEpoch)
    assertTrue(PartitionMetadataFile.newFile(log.dir).exists())
    assertFalse(PartitionMetadataFile.newFile(this.logDir).exists())

    // Check the topic ID remains in memory and was copied correctly.
    assertEquals(id, log.topicId)
    assertEquals(id, log.partitionMetadataFile.read().topicId)
  }

  @Test
  def testLeaderEpochCacheClearedAfterDowngradeInAppendedMessages(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1, maxMessageBytes = 64 * 1024)
    val log = createLog(logDir, logConfig)
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("foo".getBytes()))), leaderEpoch = 5)
    assertEquals(Some(5), log.leaderEpochCache.flatMap(_.latestEpoch))

    log.appendAsFollower(TestUtils.records(List(new SimpleRecord("foo".getBytes())),
      baseOffset = 1L,
      magicValue = RecordVersion.V1.value))
    assertEquals(None, log.leaderEpochCache.flatMap(_.latestEpoch))
  }

  @Test
  def testLeaderEpochCacheClearedAfterStaticMessageFormatDowngrade(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1, maxMessageBytes = 64 * 1024)
    val log = createLog(logDir, logConfig)
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("foo".getBytes()))), leaderEpoch = 5)
    assertEquals(Some(5), log.latestEpoch)
    log.close()

    // reopen the log with an older message format version and check the cache
    val downgradedLogConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1,
      maxMessageBytes = 64 * 1024, messageFormatVersion = kafka.api.KAFKA_0_10_2_IV0.shortVersion)
    val reopened = createLog(logDir, downgradedLogConfig, lastShutdownClean = false)
    assertLeaderEpochCacheEmpty(reopened)

    reopened.appendAsLeader(TestUtils.records(List(new SimpleRecord("bar".getBytes())),
      magicValue = RecordVersion.V1.value), leaderEpoch = 5)
    assertLeaderEpochCacheEmpty(reopened)
  }

  @Test
  def testLeaderEpochCacheClearedAfterDynamicMessageFormatDowngrade(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1, maxMessageBytes = 64 * 1024)
    val log = createLog(logDir, logConfig)
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("foo".getBytes()))), leaderEpoch = 5)
    assertEquals(Some(5), log.latestEpoch)

    val downgradedLogConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1,
      maxMessageBytes = 64 * 1024, messageFormatVersion = kafka.api.KAFKA_0_10_2_IV0.shortVersion)
    log.updateConfig(downgradedLogConfig)
    assertLeaderEpochCacheEmpty(log)

    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("bar".getBytes())),
      magicValue = RecordVersion.V1.value), leaderEpoch = 5)
    assertLeaderEpochCacheEmpty(log)
  }

  @Test
  def testLeaderEpochCacheCreatedAfterMessageFormatUpgrade(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1,
      maxMessageBytes = 64 * 1024, messageFormatVersion = kafka.api.KAFKA_0_10_2_IV0.shortVersion)
    val log = createLog(logDir, logConfig)
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("bar".getBytes())),
      magicValue = RecordVersion.V1.value), leaderEpoch = 5)
    assertLeaderEpochCacheEmpty(log)

    val upgradedLogConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1,
      maxMessageBytes = 64 * 1024, messageFormatVersion = kafka.api.KAFKA_0_11_0_IV0.shortVersion)
    log.updateConfig(upgradedLogConfig)
    log.appendAsLeader(TestUtils.records(List(new SimpleRecord("foo".getBytes()))), leaderEpoch = 5)
    assertEquals(Some(5), log.latestEpoch)
  }

  private def assertLeaderEpochCacheEmpty(log: Log): Unit = {
    assertEquals(None, log.leaderEpochCache)
    assertEquals(None, log.latestEpoch)
    assertFalse(LeaderEpochCheckpointFile.newFile(log.dir).exists())
  }

  @Test
  def testOverCompactedLogRecoveryMultiRecord(): Unit = {
    // append some messages to create some segments
    val logConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1, maxMessageBytes = 64 * 1024)
    val log = createLog(logDir, logConfig)
    val set1 = MemoryRecords.withRecords(0, CompressionType.NONE, 0, new SimpleRecord("v1".getBytes(), "k1".getBytes()))
    val set2 = MemoryRecords.withRecords(Integer.MAX_VALUE.toLong + 2, CompressionType.GZIP, 0,
      new SimpleRecord("v3".getBytes(), "k3".getBytes()),
      new SimpleRecord("v4".getBytes(), "k4".getBytes()))
    val set3 = MemoryRecords.withRecords(Integer.MAX_VALUE.toLong + 4, CompressionType.GZIP, 0,
      new SimpleRecord("v5".getBytes(), "k5".getBytes()),
      new SimpleRecord("v6".getBytes(), "k6".getBytes()))
    val set4 = MemoryRecords.withRecords(Integer.MAX_VALUE.toLong + 6, CompressionType.GZIP, 0,
      new SimpleRecord("v7".getBytes(), "k7".getBytes()),
      new SimpleRecord("v8".getBytes(), "k8".getBytes()))
    //Writes into an empty log with baseOffset 0
    log.appendAsFollower(set1)
    assertEquals(0L, log.activeSegment.baseOffset)
    //This write will roll the segment, yielding a new segment with base offset = max(1, Integer.MAX_VALUE+2) = Integer.MAX_VALUE+2
    log.appendAsFollower(set2)
    assertEquals(Integer.MAX_VALUE.toLong + 2, log.activeSegment.baseOffset)
    assertTrue(Log.producerSnapshotFile(logDir, Integer.MAX_VALUE.toLong + 2).exists)
    //This will go into the existing log
    log.appendAsFollower(set3)
    assertEquals(Integer.MAX_VALUE.toLong + 2, log.activeSegment.baseOffset)
    //This will go into the existing log
    log.appendAsFollower(set4)
    assertEquals(Integer.MAX_VALUE.toLong + 2, log.activeSegment.baseOffset)
    log.close()
    val indexFiles = logDir.listFiles.filter(file => file.getName.contains(".index"))
    assertEquals(2, indexFiles.length)
    for (file <- indexFiles) {
      val offsetIndex = new OffsetIndex(file, file.getName.replace(".index","").toLong)
      assertTrue(offsetIndex.lastOffset >= 0)
      offsetIndex.close()
    }
    Utils.delete(logDir)
  }

  @Test
  def testOverCompactedLogRecoveryMultiRecordV1(): Unit = {
    // append some messages to create some segments
    val logConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1, maxMessageBytes = 64 * 1024)
    val log = createLog(logDir, logConfig)
    val set1 = MemoryRecords.withRecords(RecordBatch.MAGIC_VALUE_V1, 0, CompressionType.NONE,
      new SimpleRecord("v1".getBytes(), "k1".getBytes()))
    val set2 = MemoryRecords.withRecords(RecordBatch.MAGIC_VALUE_V1, Integer.MAX_VALUE.toLong + 2, CompressionType.GZIP,
      new SimpleRecord("v3".getBytes(), "k3".getBytes()),
      new SimpleRecord("v4".getBytes(), "k4".getBytes()))
    val set3 = MemoryRecords.withRecords(RecordBatch.MAGIC_VALUE_V1, Integer.MAX_VALUE.toLong + 4, CompressionType.GZIP,
      new SimpleRecord("v5".getBytes(), "k5".getBytes()),
      new SimpleRecord("v6".getBytes(), "k6".getBytes()))
    val set4 = MemoryRecords.withRecords(RecordBatch.MAGIC_VALUE_V1, Integer.MAX_VALUE.toLong + 6, CompressionType.GZIP,
      new SimpleRecord("v7".getBytes(), "k7".getBytes()),
      new SimpleRecord("v8".getBytes(), "k8".getBytes()))
    //Writes into an empty log with baseOffset 0
    log.appendAsFollower(set1)
    assertEquals(0L, log.activeSegment.baseOffset)
    //This write will roll the segment, yielding a new segment with base offset = max(1, 3) = 3
    log.appendAsFollower(set2)
    assertEquals(3, log.activeSegment.baseOffset)
    assertTrue(Log.producerSnapshotFile(logDir, 3).exists)
    //This will also roll the segment, yielding a new segment with base offset = max(5, Integer.MAX_VALUE+4) = Integer.MAX_VALUE+4
    log.appendAsFollower(set3)
    assertEquals(Integer.MAX_VALUE.toLong + 4, log.activeSegment.baseOffset)
    assertTrue(Log.producerSnapshotFile(logDir, Integer.MAX_VALUE.toLong + 4).exists)
    //This will go into the existing log
    log.appendAsFollower(set4)
    assertEquals(Integer.MAX_VALUE.toLong + 4, log.activeSegment.baseOffset)
    log.close()
    val indexFiles = logDir.listFiles.filter(file => file.getName.contains(".index"))
    assertEquals(3, indexFiles.length)
    for (file <- indexFiles) {
      val offsetIndex = new OffsetIndex(file, file.getName.replace(".index","").toLong)
      assertTrue(offsetIndex.lastOffset >= 0)
      offsetIndex.close()
    }
    Utils.delete(logDir)
  }

  @Test
  def testSplitOnOffsetOverflow(): Unit = {
    // create a log such that one log segment has offsets that overflow, and call the split API on that segment
    val logConfig = LogTest.createLogConfig(indexIntervalBytes = 1, fileDeleteDelayMs = 1000)
    val (log, segmentWithOverflow) = createLogWithOffsetOverflow(logConfig)
    assertTrue(LogTest.hasOffsetOverflow(log), "At least one segment must have offset overflow")

    val allRecordsBeforeSplit = LogTest.allRecords(log)

    // split the segment with overflow
    log.splitOverflowedSegment(segmentWithOverflow)

    // assert we were successfully able to split the segment
    assertEquals(4, log.numberOfSegments)
    LogTest.verifyRecordsInLog(log, allRecordsBeforeSplit)

    // verify we do not have offset overflow anymore
    assertFalse(LogTest.hasOffsetOverflow(log))
  }

  @Test
  def testDegenerateSegmentSplit(): Unit = {
    // This tests a scenario where all of the batches appended to a segment have overflowed.
    // When we split the overflowed segment, only one new segment will be created.

    val overflowOffset = Int.MaxValue + 1L
    val batch1 = MemoryRecords.withRecords(overflowOffset, CompressionType.NONE, 0,
      new SimpleRecord("a".getBytes))
    val batch2 = MemoryRecords.withRecords(overflowOffset + 1, CompressionType.NONE, 0,
      new SimpleRecord("b".getBytes))

    testDegenerateSplitSegmentWithOverflow(segmentBaseOffset = 0L, List(batch1, batch2))
  }

  @Test
  def testDegenerateSegmentSplitWithOutOfRangeBatchLastOffset(): Unit = {
    // Degenerate case where the only batch in the segment overflows. In this scenario,
    // the first offset of the batch is valid, but the last overflows.

    val firstBatchBaseOffset = Int.MaxValue - 1
    val records = MemoryRecords.withRecords(firstBatchBaseOffset, CompressionType.NONE, 0,
      new SimpleRecord("a".getBytes),
      new SimpleRecord("b".getBytes),
      new SimpleRecord("c".getBytes))

    testDegenerateSplitSegmentWithOverflow(segmentBaseOffset = 0L, List(records))
  }

  private def testDegenerateSplitSegmentWithOverflow(segmentBaseOffset: Long, records: List[MemoryRecords]): Unit = {
    val segment = LogTest.rawSegment(logDir, segmentBaseOffset)
    // Need to create the offset files explicitly to avoid triggering segment recovery to truncate segment.
    Log.offsetIndexFile(logDir, segmentBaseOffset).createNewFile()
    Log.timeIndexFile(logDir, segmentBaseOffset).createNewFile()
    records.foreach(segment.append _)
    segment.close()

    val logConfig = LogTest.createLogConfig(indexIntervalBytes = 1, fileDeleteDelayMs = 1000)
    val log = createLog(logDir, logConfig, recoveryPoint = Long.MaxValue)

    val segmentWithOverflow = LogTest.firstOverflowSegment(log).getOrElse {
      throw new AssertionError("Failed to create log with a segment which has overflowed offsets")
    }

    val allRecordsBeforeSplit = LogTest.allRecords(log)
    log.splitOverflowedSegment(segmentWithOverflow)

    assertEquals(1, log.numberOfSegments)

    val firstBatchBaseOffset = records.head.batches.asScala.head.baseOffset
    assertEquals(firstBatchBaseOffset, log.activeSegment.baseOffset)
    LogTest.verifyRecordsInLog(log, allRecordsBeforeSplit)

    assertFalse(LogTest.hasOffsetOverflow(log))
  }

  @Test
  def testRecoveryOfSegmentWithOffsetOverflow(): Unit = {
    val logConfig = LogTest.createLogConfig(indexIntervalBytes = 1, fileDeleteDelayMs = 1000)
    val (log, _) = createLogWithOffsetOverflow(logConfig)
    val expectedKeys = LogTest.keysInLog(log)

    // Run recovery on the log. This should split the segment underneath. Ignore .deleted files as we could have still
    // have them lying around after the split.
    val recoveredLog = recoverAndCheck(logConfig, expectedKeys)
    assertEquals(expectedKeys, LogTest.keysInLog(recoveredLog))

    // Running split again would throw an error

    for (segment <- recoveredLog.logSegments) {
      assertThrows(classOf[IllegalArgumentException], () => log.splitOverflowedSegment(segment))
    }
  }

  @Test
  def testRecoveryAfterCrashDuringSplitPhase1(): Unit = {
    val logConfig = LogTest.createLogConfig(indexIntervalBytes = 1, fileDeleteDelayMs = 1000)
    val (log, segmentWithOverflow) = createLogWithOffsetOverflow(logConfig)
    val expectedKeys = LogTest.keysInLog(log)
    val numSegmentsInitial = log.logSegments.size

    // Split the segment
    val newSegments = log.splitOverflowedSegment(segmentWithOverflow)

    // Simulate recovery just after .cleaned file is created, before rename to .swap. On recovery, existing split
    // operation is aborted but the recovery process itself kicks off split which should complete.
    newSegments.reverse.foreach(segment => {
      segment.changeFileSuffixes("", Log.CleanedFileSuffix)
      segment.truncateTo(0)
    })
    for (file <- logDir.listFiles if file.getName.endsWith(Log.DeletedFileSuffix))
      Utils.atomicMoveWithFallback(file.toPath, Paths.get(CoreUtils.replaceSuffix(file.getPath, Log.DeletedFileSuffix, "")))

    val recoveredLog = recoverAndCheck(logConfig, expectedKeys)
    assertEquals(expectedKeys, LogTest.keysInLog(recoveredLog))
    assertEquals(numSegmentsInitial + 1, recoveredLog.logSegments.size)
    recoveredLog.close()
  }

  @Test
  def testRecoveryAfterCrashDuringSplitPhase2(): Unit = {
    val logConfig = LogTest.createLogConfig(indexIntervalBytes = 1, fileDeleteDelayMs = 1000)
    val (log, segmentWithOverflow) = createLogWithOffsetOverflow(logConfig)
    val expectedKeys = LogTest.keysInLog(log)
    val numSegmentsInitial = log.logSegments.size

    // Split the segment
    val newSegments = log.splitOverflowedSegment(segmentWithOverflow)

    // Simulate recovery just after one of the new segments has been renamed to .swap. On recovery, existing split
    // operation is aborted but the recovery process itself kicks off split which should complete.
    newSegments.reverse.foreach { segment =>
      if (segment != newSegments.last)
        segment.changeFileSuffixes("", Log.CleanedFileSuffix)
      else
        segment.changeFileSuffixes("", Log.SwapFileSuffix)
      segment.truncateTo(0)
    }
    for (file <- logDir.listFiles if file.getName.endsWith(Log.DeletedFileSuffix))
      Utils.atomicMoveWithFallback(file.toPath, Paths.get(CoreUtils.replaceSuffix(file.getPath, Log.DeletedFileSuffix, "")))

    val recoveredLog = recoverAndCheck(logConfig, expectedKeys)
    assertEquals(expectedKeys, LogTest.keysInLog(recoveredLog))
    assertEquals(numSegmentsInitial + 1, recoveredLog.logSegments.size)
    recoveredLog.close()
  }

  @Test
  def testRecoveryAfterCrashDuringSplitPhase3(): Unit = {
    val logConfig = LogTest.createLogConfig(indexIntervalBytes = 1, fileDeleteDelayMs = 1000)
    val (log, segmentWithOverflow) = createLogWithOffsetOverflow(logConfig)
    val expectedKeys = LogTest.keysInLog(log)
    val numSegmentsInitial = log.logSegments.size

    // Split the segment
    val newSegments = log.splitOverflowedSegment(segmentWithOverflow)

    // Simulate recovery right after all new segments have been renamed to .swap. On recovery, existing split operation
    // is completed and the old segment must be deleted.
    newSegments.reverse.foreach(segment => {
        segment.changeFileSuffixes("", Log.SwapFileSuffix)
    })
    for (file <- logDir.listFiles if file.getName.endsWith(Log.DeletedFileSuffix))
      Utils.atomicMoveWithFallback(file.toPath, Paths.get(CoreUtils.replaceSuffix(file.getPath, Log.DeletedFileSuffix, "")))

    // Truncate the old segment
    segmentWithOverflow.truncateTo(0)

    val recoveredLog = recoverAndCheck(logConfig, expectedKeys)
    assertEquals(expectedKeys, LogTest.keysInLog(recoveredLog))
    assertEquals(numSegmentsInitial + 1, recoveredLog.logSegments.size)
    log.close()
  }

  @Test
  def testRecoveryAfterCrashDuringSplitPhase4(): Unit = {
    val logConfig = LogTest.createLogConfig(indexIntervalBytes = 1, fileDeleteDelayMs = 1000)
    val (log, segmentWithOverflow) = createLogWithOffsetOverflow(logConfig)
    val expectedKeys = LogTest.keysInLog(log)
    val numSegmentsInitial = log.logSegments.size

    // Split the segment
    val newSegments = log.splitOverflowedSegment(segmentWithOverflow)

    // Simulate recovery right after all new segments have been renamed to .swap and old segment has been deleted. On
    // recovery, existing split operation is completed.
    newSegments.reverse.foreach(_.changeFileSuffixes("", Log.SwapFileSuffix))

    for (file <- logDir.listFiles if file.getName.endsWith(Log.DeletedFileSuffix))
      Utils.delete(file)

    // Truncate the old segment
    segmentWithOverflow.truncateTo(0)

    val recoveredLog = recoverAndCheck(logConfig, expectedKeys)
    assertEquals(expectedKeys, LogTest.keysInLog(recoveredLog))
    assertEquals(numSegmentsInitial + 1, recoveredLog.logSegments.size)
    recoveredLog.close()
  }

  @Test
  def testRecoveryAfterCrashDuringSplitPhase5(): Unit = {
    val logConfig = LogTest.createLogConfig(indexIntervalBytes = 1, fileDeleteDelayMs = 1000)
    val (log, segmentWithOverflow) = createLogWithOffsetOverflow(logConfig)
    val expectedKeys = LogTest.keysInLog(log)
    val numSegmentsInitial = log.logSegments.size

    // Split the segment
    val newSegments = log.splitOverflowedSegment(segmentWithOverflow)

    // Simulate recovery right after one of the new segment has been renamed to .swap and the other to .log. On
    // recovery, existing split operation is completed.
    newSegments.last.changeFileSuffixes("", Log.SwapFileSuffix)

    // Truncate the old segment
    segmentWithOverflow.truncateTo(0)

    val recoveredLog = recoverAndCheck(logConfig, expectedKeys)
    assertEquals(expectedKeys, LogTest.keysInLog(recoveredLog))
    assertEquals(numSegmentsInitial + 1, recoveredLog.logSegments.size)
    recoveredLog.close()
  }

  @Test
  def testCleanShutdownFile(): Unit = {
    // append some messages to create some segments
    val logConfig = LogTest.createLogConfig(segmentBytes = 1000, indexIntervalBytes = 1, maxMessageBytes = 64 * 1024)
    def createRecords = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds)

    var recoveryPoint = 0L
    // create a log and write some messages to it
    var log = createLog(logDir, logConfig)
    for (_ <- 0 until 100)
      log.appendAsLeader(createRecords, leaderEpoch = 0)
    log.close()

    // check if recovery was attempted. Even if the recovery point is 0L, recovery should not be attempted as the
    // clean shutdown file exists. Note: Earlier, Log layer relied on the presence of clean shutdown file to determine the status
    // of last shutdown. Now, LogManager checks for the presence of this file and immediately deletes the same. It passes
    // down a clean shutdown flag to the Log layer as log is loaded. Recovery is attempted based on this flag.
    recoveryPoint = log.logEndOffset
    log = createLog(logDir, logConfig)
    assertEquals(recoveryPoint, log.logEndOffset)
  }

  @Test
  def testParseTopicPartitionName(): Unit = {
    val topic = "test_topic"
    val partition = "143"
    val dir = new File(logDir, topicPartitionName(topic, partition))
    val topicPartition = Log.parseTopicPartitionName(dir)
    assertEquals(topic, topicPartition.topic)
    assertEquals(partition.toInt, topicPartition.partition)
  }

  /**
   * Tests that log directories with a period in their name that have been marked for deletion
   * are parsed correctly by `Log.parseTopicPartitionName` (see KAFKA-5232 for details).
   */
  @Test
  def testParseTopicPartitionNameWithPeriodForDeletedTopic(): Unit = {
    val topic = "foo.bar-testtopic"
    val partition = "42"
    val dir = new File(logDir, Log.logDeleteDirName(new TopicPartition(topic, partition.toInt)))
    val topicPartition = Log.parseTopicPartitionName(dir)
    assertEquals(topic, topicPartition.topic, "Unexpected topic name parsed")
    assertEquals(partition.toInt, topicPartition.partition, "Unexpected partition number parsed")
  }

  @Test
  def testParseTopicPartitionNameForEmptyName(): Unit = {
    val dir = new File("")
    assertThrows(classOf[KafkaException], () => Log.parseTopicPartitionName(dir),
      () => "KafkaException should have been thrown for dir: " + dir.getCanonicalPath)
  }

  @Test
  def testParseTopicPartitionNameForNull(): Unit = {
    val dir: File = null
    assertThrows(classOf[KafkaException], () => Log.parseTopicPartitionName(dir),
      () => "KafkaException should have been thrown for dir: " + dir)
  }

  @Test
  def testParseTopicPartitionNameForMissingSeparator(): Unit = {
    val topic = "test_topic"
    val partition = "1999"
    val dir = new File(logDir, topic + partition)
    assertThrows(classOf[KafkaException], () => Log.parseTopicPartitionName(dir),
      () => "KafkaException should have been thrown for dir: " + dir.getCanonicalPath)
    // also test the "-delete" marker case
    val deleteMarkerDir = new File(logDir, topic + partition + "." + DeleteDirSuffix)
    assertThrows(classOf[KafkaException], () => Log.parseTopicPartitionName(deleteMarkerDir),
      () => "KafkaException should have been thrown for dir: " + deleteMarkerDir.getCanonicalPath)
  }

  @Test
  def testParseTopicPartitionNameForMissingTopic(): Unit = {
    val topic = ""
    val partition = "1999"
    val dir = new File(logDir, topicPartitionName(topic, partition))
    assertThrows(classOf[KafkaException], () => Log.parseTopicPartitionName(dir),
      () => "KafkaException should have been thrown for dir: " + dir.getCanonicalPath)

    // also test the "-delete" marker case
    val deleteMarkerDir = new File(logDir, Log.logDeleteDirName(new TopicPartition(topic, partition.toInt)))

    assertThrows(classOf[KafkaException], () => Log.parseTopicPartitionName(deleteMarkerDir),
      () => "KafkaException should have been thrown for dir: " + deleteMarkerDir.getCanonicalPath)
  }

  @Test
  def testParseTopicPartitionNameForMissingPartition(): Unit = {
    val topic = "test_topic"
    val partition = ""
    val dir = new File(logDir.getPath + topicPartitionName(topic, partition))
    assertThrows(classOf[KafkaException], () => Log.parseTopicPartitionName(dir),
      () => "KafkaException should have been thrown for dir: " + dir.getCanonicalPath)

    // also test the "-delete" marker case
    val deleteMarkerDir = new File(logDir, topicPartitionName(topic, partition) + "." + DeleteDirSuffix)
    assertThrows(classOf[KafkaException], () => Log.parseTopicPartitionName(deleteMarkerDir),
      () => "KafkaException should have been thrown for dir: " + deleteMarkerDir.getCanonicalPath)
  }

  @Test
  def testParseTopicPartitionNameForInvalidPartition(): Unit = {
    val topic = "test_topic"
    val partition = "1999a"
    val dir = new File(logDir, topicPartitionName(topic, partition))
    assertThrows(classOf[KafkaException], () => Log.parseTopicPartitionName(dir),
      () => "KafkaException should have been thrown for dir: " + dir.getCanonicalPath)

    // also test the "-delete" marker case
    val deleteMarkerDir = new File(logDir, topic + partition + "." + DeleteDirSuffix)
    assertThrows(classOf[KafkaException], () => Log.parseTopicPartitionName(deleteMarkerDir),
      () => "KafkaException should have been thrown for dir: " + deleteMarkerDir.getCanonicalPath)
  }

  @Test
  def testParseTopicPartitionNameForExistingInvalidDir(): Unit = {
    val dir1 = new File(logDir.getPath + "/non_kafka_dir")
    assertThrows(classOf[KafkaException], () => Log.parseTopicPartitionName(dir1),
      () => "KafkaException should have been thrown for dir: " + dir1.getCanonicalPath)
    val dir2 = new File(logDir.getPath + "/non_kafka_dir-delete")
    assertThrows(classOf[KafkaException], () => Log.parseTopicPartitionName(dir2),
      () => "KafkaException should have been thrown for dir: " + dir2.getCanonicalPath)
  }

  def topicPartitionName(topic: String, partition: String): String =
    topic + "-" + partition

  @Test
  def testDeleteOldSegments(): Unit = {
    def createRecords = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds - 1000)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, segmentIndexBytes = 1000, retentionMs = 999)
    val log = createLog(logDir, logConfig)

    // append some messages to create some segments
    for (_ <- 0 until 100)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    log.maybeAssignEpochStartOffset(0, 40)
    log.maybeAssignEpochStartOffset(1, 90)

    // segments are not eligible for deletion if no high watermark has been set
    val numSegments = log.numberOfSegments
    log.deleteOldSegments()
    assertEquals(numSegments, log.numberOfSegments)
    assertEquals(0L, log.logStartOffset)

    // only segments with offset before the current high watermark are eligible for deletion
    for (hw <- 25 to 30) {
      log.updateHighWatermark(hw)
      log.deleteOldSegments()
      assertTrue(log.logStartOffset <= hw)
      log.logSegments.foreach { segment =>
        val segmentFetchInfo = segment.read(startOffset = segment.baseOffset, maxSize = Int.MaxValue)
        val segmentLastOffsetOpt = segmentFetchInfo.records.records.asScala.lastOption.map(_.offset)
        segmentLastOffsetOpt.foreach { lastOffset =>
          assertTrue(lastOffset >= hw)
        }
      }
    }

    // expire all segments
    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()
    assertEquals(1, log.numberOfSegments, "The deleted segments should be gone.")
    assertEquals(1, epochCache(log).epochEntries.size, "Epoch entries should have gone.")
    assertEquals(EpochEntry(1, 100), epochCache(log).epochEntries.head, "Epoch entry should be the latest epoch and the leo.")

    // append some messages to create some segments
    for (_ <- 0 until 100)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    log.delete()
    assertEquals(0, log.numberOfSegments, "The number of segments should be 0")
    assertEquals(0, log.deleteOldSegments(), "The number of deleted segments should be zero.")
    assertEquals(0, epochCache(log).epochEntries.size, "Epoch entries should have gone.")
  }

  @Test
  def testLogDeletionAfterClose(): Unit = {
    def createRecords = TestUtils.singletonRecords(value = "test".getBytes, timestamp = mockTime.milliseconds - 1000)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, segmentIndexBytes = 1000, retentionMs = 999)
    val log = createLog(logDir, logConfig)

    // append some messages to create some segments
    log.appendAsLeader(createRecords, leaderEpoch = 0)

    assertEquals(1, log.numberOfSegments, "The deleted segments should be gone.")
    assertEquals(1, epochCache(log).epochEntries.size, "Epoch entries should have gone.")

    log.close()
    log.delete()
    assertEquals(0, log.numberOfSegments, "The number of segments should be 0")
    assertEquals(0, epochCache(log).epochEntries.size, "Epoch entries should have gone.")
  }

  @Test
  def testLogDeletionAfterDeleteRecords(): Unit = {
    def createRecords = TestUtils.singletonRecords("test".getBytes)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5)
    val log = createLog(logDir, logConfig)

    for (_ <- 0 until 15)
      log.appendAsLeader(createRecords, leaderEpoch = 0)
    assertEquals(3, log.numberOfSegments, "should have 3 segments")
    assertEquals(log.logStartOffset, 0)
    log.updateHighWatermark(log.logEndOffset)

    log.maybeIncrementLogStartOffset(1, ClientRecordDeletion)
    log.deleteOldSegments()
    assertEquals(3, log.numberOfSegments, "should have 3 segments")
    assertEquals(log.logStartOffset, 1)

    log.maybeIncrementLogStartOffset(6, ClientRecordDeletion)
    log.deleteOldSegments()
    assertEquals(2, log.numberOfSegments, "should have 2 segments")
    assertEquals(log.logStartOffset, 6)

    log.maybeIncrementLogStartOffset(15, ClientRecordDeletion)
    log.deleteOldSegments()
    assertEquals(1, log.numberOfSegments, "should have 1 segments")
    assertEquals(log.logStartOffset, 15)
  }

  def epochCache(log: Log): LeaderEpochFileCache = {
    log.leaderEpochCache.get
  }

  @Test
  def shouldDeleteSizeBasedSegments(): Unit = {
    def createRecords = TestUtils.singletonRecords("test".getBytes)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, retentionBytes = createRecords.sizeInBytes * 10)
    val log = createLog(logDir, logConfig)

    // append some messages to create some segments
    for (_ <- 0 until 15)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()
    assertEquals(2,log.numberOfSegments, "should have 2 segments")
  }

  @Test
  def shouldNotDeleteSizeBasedSegmentsWhenUnderRetentionSize(): Unit = {
    def createRecords = TestUtils.singletonRecords("test".getBytes)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, retentionBytes = createRecords.sizeInBytes * 15)
    val log = createLog(logDir, logConfig)

    // append some messages to create some segments
    for (_ <- 0 until 15)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()
    assertEquals(3,log.numberOfSegments, "should have 3 segments")
  }

  @Test
  def shouldDeleteTimeBasedSegmentsReadyToBeDeleted(): Unit = {
    def createRecords = TestUtils.singletonRecords("test".getBytes, timestamp = 10)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, retentionMs = 10000)
    val log = createLog(logDir, logConfig)

    // append some messages to create some segments
    for (_ <- 0 until 15)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()
    assertEquals(1, log.numberOfSegments, "There should be 1 segment remaining")
  }

  @Test
  def shouldNotDeleteTimeBasedSegmentsWhenNoneReadyToBeDeleted(): Unit = {
    def createRecords = TestUtils.singletonRecords("test".getBytes, timestamp = mockTime.milliseconds)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, retentionMs = 10000000)
    val log = createLog(logDir, logConfig)

    // append some messages to create some segments
    for (_ <- 0 until 15)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()
    assertEquals(3, log.numberOfSegments, "There should be 3 segments remaining")
  }

  @Test
  def shouldNotDeleteSegmentsWhenPolicyDoesNotIncludeDelete(): Unit = {
    def createRecords = TestUtils.singletonRecords("test".getBytes, key = "test".getBytes(), timestamp = 10L)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, retentionMs = 10000, cleanupPolicy = "compact")
    val log = createLog(logDir, logConfig)

    // append some messages to create some segments
    for (_ <- 0 until 15)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    // mark oldest segment as older the retention.ms
    log.logSegments.head.lastModified = mockTime.milliseconds - 20000

    val segments = log.numberOfSegments
    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()
    assertEquals(segments, log.numberOfSegments, "There should be 3 segments remaining")
  }

  @Test
  def shouldDeleteSegmentsReadyToBeDeletedWhenCleanupPolicyIsCompactAndDelete(): Unit = {
    def createRecords = TestUtils.singletonRecords("test".getBytes, key = "test".getBytes, timestamp = 10L)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, retentionMs = 10000, cleanupPolicy = "compact,delete")
    val log = createLog(logDir, logConfig)

    // append some messages to create some segments
    for (_ <- 0 until 15)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()
    assertEquals(1, log.numberOfSegments, "There should be 1 segment remaining")
  }

  @Test
  def shouldDeleteStartOffsetBreachedSegmentsWhenPolicyDoesNotIncludeDelete(): Unit = {
    def createRecords = TestUtils.singletonRecords("test".getBytes, key = "test".getBytes, timestamp = 10L)
    val recordsPerSegment = 5
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * recordsPerSegment, retentionMs = 10000, cleanupPolicy = "compact")
    val log = createLog(logDir, logConfig, brokerTopicStats)

    // append some messages to create some segments
    for (_ <- 0 until 15)
      log.appendAsLeader(createRecords, leaderEpoch = 0)

    // Three segments should be created
    assertEquals(3, log.logSegments.count(_ => true))
    log.updateHighWatermark(log.logEndOffset)
    log.maybeIncrementLogStartOffset(recordsPerSegment, ClientRecordDeletion)

    // The first segment, which is entirely before the log start offset, should be deleted
    // Of the remaining the segments, the first can overlap the log start offset and the rest must have a base offset
    // greater than the start offset
    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()
    assertEquals(2, log.numberOfSegments, "There should be 2 segments remaining")
    assertTrue(log.logSegments.head.baseOffset <= log.logStartOffset)
    assertTrue(log.logSegments.tail.forall(s => s.baseOffset > log.logStartOffset))
  }

  @Test
  def shouldApplyEpochToMessageOnAppendIfLeader(): Unit = {
    val records = (0 until 50).toArray.map(id => new SimpleRecord(id.toString.getBytes))

    //Given this partition is on leader epoch 72
    val epoch = 72
    val log = createLog(logDir, LogConfig())
    log.maybeAssignEpochStartOffset(epoch, records.length)

    //When appending messages as a leader (i.e. assignOffsets = true)
    for (record <- records)
      log.appendAsLeader(
        MemoryRecords.withRecords(CompressionType.NONE, record),
        leaderEpoch = epoch
      )

    //Then leader epoch should be set on messages
    for (i <- records.indices) {
      val read = readLog(log, i, 1).records.batches.iterator.next()
      assertEquals(72, read.partitionLeaderEpoch, "Should have set leader epoch")
    }
  }

  @Test
  def followerShouldSaveEpochInformationFromReplicatedMessagesToTheEpochCache(): Unit = {
    val messageIds = (0 until 50).toArray
    val records = messageIds.map(id => new SimpleRecord(id.toString.getBytes))

    //Given each message has an offset & epoch, as msgs from leader would
    def recordsForEpoch(i: Int): MemoryRecords = {
      val recs = MemoryRecords.withRecords(messageIds(i), CompressionType.NONE, records(i))
      recs.batches.forEach{record =>
        record.setPartitionLeaderEpoch(42)
        record.setLastOffset(i)
      }
      recs
    }

    val log = createLog(logDir, LogConfig())

    //When appending as follower (assignOffsets = false)
    for (i <- records.indices)
      log.appendAsFollower(recordsForEpoch(i))

    assertEquals(Some(42), log.latestEpoch)
  }

  @Test
  def shouldTruncateLeaderEpochsWhenDeletingSegments(): Unit = {
    def createRecords = TestUtils.singletonRecords("test".getBytes)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, retentionBytes = createRecords.sizeInBytes * 10)
    val log = createLog(logDir, logConfig)
    val cache = epochCache(log)

    // Given three segments of 5 messages each
    for (_ <- 0 until 15) {
      log.appendAsLeader(createRecords, leaderEpoch = 0)
    }

    //Given epochs
    cache.assign(0, 0)
    cache.assign(1, 5)
    cache.assign(2, 10)

    //When first segment is removed
    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()

    //The oldest epoch entry should have been removed
    assertEquals(ListBuffer(EpochEntry(1, 5), EpochEntry(2, 10)), cache.epochEntries)
  }

  @Test
  def shouldUpdateOffsetForLeaderEpochsWhenDeletingSegments(): Unit = {
    def createRecords = TestUtils.singletonRecords("test".getBytes)
    val logConfig = LogTest.createLogConfig(segmentBytes = createRecords.sizeInBytes * 5, retentionBytes = createRecords.sizeInBytes * 10)
    val log = createLog(logDir, logConfig)
    val cache = epochCache(log)

    // Given three segments of 5 messages each
    for (_ <- 0 until 15) {
      log.appendAsLeader(createRecords, leaderEpoch = 0)
    }

    //Given epochs
    cache.assign(0, 0)
    cache.assign(1, 7)
    cache.assign(2, 10)

    //When first segment removed (up to offset 5)
    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()

    //The first entry should have gone from (0,0) => (0,5)
    assertEquals(ListBuffer(EpochEntry(0, 5), EpochEntry(1, 7), EpochEntry(2, 10)), cache.epochEntries)
  }

  @Test
  def shouldTruncateLeaderEpochCheckpointFileWhenTruncatingLog(): Unit = {
    def createRecords(startOffset: Long, epoch: Int): MemoryRecords = {
      TestUtils.records(Seq(new SimpleRecord("value".getBytes)),
        baseOffset = startOffset, partitionLeaderEpoch = epoch)
    }

    val logConfig = LogTest.createLogConfig(segmentBytes = 10 * createRecords(0, 0).sizeInBytes)
    val log = createLog(logDir, logConfig)
    val cache = epochCache(log)

    def append(epoch: Int, startOffset: Long, count: Int): Unit = {
      for (i <- 0 until count)
        log.appendAsFollower(createRecords(startOffset + i, epoch))
    }

    //Given 2 segments, 10 messages per segment
    append(epoch = 0, startOffset = 0, count = 10)
    append(epoch = 1, startOffset = 10, count = 6)
    append(epoch = 2, startOffset = 16, count = 4)

    assertEquals(2, log.numberOfSegments)
    assertEquals(20, log.logEndOffset)

    //When truncate to LEO (no op)
    log.truncateTo(log.logEndOffset)

    //Then no change
    assertEquals(3, cache.epochEntries.size)

    //When truncate
    log.truncateTo(11)

    //Then no change
    assertEquals(2, cache.epochEntries.size)

    //When truncate
    log.truncateTo(10)

    //Then
    assertEquals(1, cache.epochEntries.size)

    //When truncate all
    log.truncateTo(0)

    //Then
    assertEquals(0, cache.epochEntries.size)
  }

  /**
   * Append a bunch of messages to a log and then re-open it with recovery and check that the leader epochs are recovered properly.
   */
  @Test
  def testLogRecoversForLeaderEpoch(): Unit = {
    val log = createLog(logDir, LogConfig())
    val leaderEpochCache = epochCache(log)
    val firstBatch = singletonRecordsWithLeaderEpoch(value = "random".getBytes, leaderEpoch = 1, offset = 0)
    log.appendAsFollower(records = firstBatch)

    val secondBatch = singletonRecordsWithLeaderEpoch(value = "random".getBytes, leaderEpoch = 2, offset = 1)
    log.appendAsFollower(records = secondBatch)

    val thirdBatch = singletonRecordsWithLeaderEpoch(value = "random".getBytes, leaderEpoch = 2, offset = 2)
    log.appendAsFollower(records = thirdBatch)

    val fourthBatch = singletonRecordsWithLeaderEpoch(value = "random".getBytes, leaderEpoch = 3, offset = 3)
    log.appendAsFollower(records = fourthBatch)

    assertEquals(ListBuffer(EpochEntry(1, 0), EpochEntry(2, 1), EpochEntry(3, 3)), leaderEpochCache.epochEntries)

    // deliberately remove some of the epoch entries
    leaderEpochCache.truncateFromEnd(2)
    assertNotEquals(ListBuffer(EpochEntry(1, 0), EpochEntry(2, 1), EpochEntry(3, 3)), leaderEpochCache.epochEntries)
    log.close()

    // reopen the log and recover from the beginning
    val recoveredLog = createLog(logDir, LogConfig(), lastShutdownClean = false)
    val recoveredLeaderEpochCache = epochCache(recoveredLog)

    // epoch entries should be recovered
    assertEquals(ListBuffer(EpochEntry(1, 0), EpochEntry(2, 1), EpochEntry(3, 3)), recoveredLeaderEpochCache.epochEntries)
    recoveredLog.close()
  }

  /**
   * Wrap a single record log buffer with leader epoch.
   */
  private def singletonRecordsWithLeaderEpoch(value: Array[Byte],
                                              key: Array[Byte] = null,
                                              leaderEpoch: Int,
                                              offset: Long,
                                              codec: CompressionType = CompressionType.NONE,
                                              timestamp: Long = RecordBatch.NO_TIMESTAMP,
                                              magicValue: Byte = RecordBatch.CURRENT_MAGIC_VALUE): MemoryRecords = {
    val records = Seq(new SimpleRecord(timestamp, key, value))

    val buf = ByteBuffer.allocate(DefaultRecordBatch.sizeInBytes(records.asJava))
    val builder = MemoryRecords.builder(buf, magicValue, codec, TimestampType.CREATE_TIME, offset,
      mockTime.milliseconds, leaderEpoch)
    records.foreach(builder.append)
    builder.build()
  }

  @Test
  def testFirstUnstableOffsetNoTransactionalData(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)

    val records = MemoryRecords.withRecords(CompressionType.NONE,
      new SimpleRecord("foo".getBytes),
      new SimpleRecord("bar".getBytes),
      new SimpleRecord("baz".getBytes))

    log.appendAsLeader(records, leaderEpoch = 0)
    assertEquals(None, log.firstUnstableOffset)
  }

  @Test
  def testFirstUnstableOffsetWithTransactionalData(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)

    val pid = 137L
    val epoch = 5.toShort
    var seq = 0

    // add some transactional records
    val records = MemoryRecords.withTransactionalRecords(CompressionType.NONE, pid, epoch, seq,
      new SimpleRecord("foo".getBytes),
      new SimpleRecord("bar".getBytes),
      new SimpleRecord("baz".getBytes))

    val firstAppendInfo = log.appendAsLeader(records, leaderEpoch = 0)
    assertEquals(firstAppendInfo.firstOffset.map(_.messageOffset), log.firstUnstableOffset)

    // add more transactional records
    seq += 3
    log.appendAsLeader(MemoryRecords.withTransactionalRecords(CompressionType.NONE, pid, epoch, seq,
      new SimpleRecord("blah".getBytes)), leaderEpoch = 0)

    // LSO should not have changed
    assertEquals(firstAppendInfo.firstOffset.map(_.messageOffset), log.firstUnstableOffset)

    // now transaction is committed
    val commitAppendInfo = appendEndTxnMarkerAsLeader(log, pid, epoch, ControlRecordType.COMMIT)

    // first unstable offset is not updated until the high watermark is advanced
    assertEquals(firstAppendInfo.firstOffset.map(_.messageOffset), log.firstUnstableOffset)
    log.updateHighWatermark(commitAppendInfo.lastOffset + 1)

    // now there should be no first unstable offset
    assertEquals(None, log.firstUnstableOffset)
  }

  @Test
  def testReadCommittedWithConcurrentHighWatermarkUpdates(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)
    val lastOffset = 50L

    val producerEpoch = 0.toShort
    val producerId = 15L
    val appendProducer = appendTransactionalAsLeader(log, producerId, producerEpoch)

    // Thread 1 writes single-record transactions and attempts to read them
    // before they have been aborted, and then aborts them
    val txnWriteAndReadLoop: Callable[Int] = () => {
      var nonEmptyReads = 0
      while (log.logEndOffset < lastOffset) {
        val currentLogEndOffset = log.logEndOffset

        appendProducer(1)

        val readInfo = log.read(
          startOffset = currentLogEndOffset,
          maxLength = Int.MaxValue,
          isolation = FetchTxnCommitted,
          minOneMessage = false)

        if (readInfo.records.sizeInBytes() > 0)
          nonEmptyReads += 1

        appendEndTxnMarkerAsLeader(log, producerId, producerEpoch, ControlRecordType.ABORT)
      }
      nonEmptyReads
    }

    // Thread 2 watches the log and updates the high watermark
    val hwUpdateLoop: Runnable = () => {
      while (log.logEndOffset < lastOffset) {
        log.updateHighWatermark(log.logEndOffset)
      }
    }

    val executor = Executors.newFixedThreadPool(2)
    try {
      executor.submit(hwUpdateLoop)

      val future = executor.submit(txnWriteAndReadLoop)
      val nonEmptyReads = future.get()

      assertEquals(0, nonEmptyReads)
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  def testTransactionIndexUpdated(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)
    val epoch = 0.toShort

    val pid1 = 1L
    val pid2 = 2L
    val pid3 = 3L
    val pid4 = 4L

    val appendPid1 = appendTransactionalAsLeader(log, pid1, epoch)
    val appendPid2 = appendTransactionalAsLeader(log, pid2, epoch)
    val appendPid3 = appendTransactionalAsLeader(log, pid3, epoch)
    val appendPid4 = appendTransactionalAsLeader(log, pid4, epoch)

    // mix transactional and non-transactional data
    appendPid1(5) // nextOffset: 5
    appendNonTransactionalAsLeader(log, 3) // 8
    appendPid2(2) // 10
    appendPid1(4) // 14
    appendPid3(3) // 17
    appendNonTransactionalAsLeader(log, 2) // 19
    appendPid1(10) // 29
    appendEndTxnMarkerAsLeader(log, pid1, epoch, ControlRecordType.ABORT) // 30
    appendPid2(6) // 36
    appendPid4(3) // 39
    appendNonTransactionalAsLeader(log, 10) // 49
    appendPid3(9) // 58
    appendEndTxnMarkerAsLeader(log, pid3, epoch, ControlRecordType.COMMIT) // 59
    appendPid4(8) // 67
    appendPid2(7) // 74
    appendEndTxnMarkerAsLeader(log, pid2, epoch, ControlRecordType.ABORT) // 75
    appendNonTransactionalAsLeader(log, 10) // 85
    appendPid4(4) // 89
    appendEndTxnMarkerAsLeader(log, pid4, epoch, ControlRecordType.COMMIT) // 90

    val abortedTransactions = allAbortedTransactions(log)
    val expectedTransactions = List(
      new AbortedTxn(pid1, 0L, 29L, 8L),
      new AbortedTxn(pid2, 8L, 74L, 36L)
    )
    assertEquals(expectedTransactions, abortedTransactions)

    // Verify caching of the segment position of the first unstable offset
    log.updateHighWatermark(30L)
    assertCachedFirstUnstableOffset(log, expectedOffset = 8L)

    log.updateHighWatermark(75L)
    assertCachedFirstUnstableOffset(log, expectedOffset = 36L)

    log.updateHighWatermark(log.logEndOffset)
    assertEquals(None, log.firstUnstableOffset)
  }

  @Test
  def testFullTransactionIndexRecovery(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 128 * 5)
    val log = createLog(logDir, logConfig)
    val epoch = 0.toShort

    val pid1 = 1L
    val pid2 = 2L
    val pid3 = 3L
    val pid4 = 4L

    val appendPid1 = appendTransactionalAsLeader(log, pid1, epoch)
    val appendPid2 = appendTransactionalAsLeader(log, pid2, epoch)
    val appendPid3 = appendTransactionalAsLeader(log, pid3, epoch)
    val appendPid4 = appendTransactionalAsLeader(log, pid4, epoch)

    // mix transactional and non-transactional data
    appendPid1(5) // nextOffset: 5
    appendNonTransactionalAsLeader(log, 3) // 8
    appendPid2(2) // 10
    appendPid1(4) // 14
    appendPid3(3) // 17
    appendNonTransactionalAsLeader(log, 2) // 19
    appendPid1(10) // 29
    appendEndTxnMarkerAsLeader(log, pid1, epoch, ControlRecordType.ABORT) // 30
    appendPid2(6) // 36
    appendPid4(3) // 39
    appendNonTransactionalAsLeader(log, 10) // 49
    appendPid3(9) // 58
    appendEndTxnMarkerAsLeader(log, pid3, epoch, ControlRecordType.COMMIT) // 59
    appendPid4(8) // 67
    appendPid2(7) // 74
    appendEndTxnMarkerAsLeader(log, pid2, epoch, ControlRecordType.ABORT) // 75
    appendNonTransactionalAsLeader(log, 10) // 85
    appendPid4(4) // 89
    appendEndTxnMarkerAsLeader(log, pid4, epoch, ControlRecordType.COMMIT) // 90

    // delete all the offset and transaction index files to force recovery
    log.logSegments.foreach { segment =>
      segment.offsetIndex.deleteIfExists()
      segment.txnIndex.deleteIfExists()
    }

    log.close()

    val reloadedLogConfig = LogTest.createLogConfig(segmentBytes = 1024 * 5)
    val reloadedLog = createLog(logDir, reloadedLogConfig, lastShutdownClean = false)
    val abortedTransactions = allAbortedTransactions(reloadedLog)
    assertEquals(List(new AbortedTxn(pid1, 0L, 29L, 8L), new AbortedTxn(pid2, 8L, 74L, 36L)), abortedTransactions)
  }

  @Test
  def testRecoverOnlyLastSegment(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 128 * 5)
    val log = createLog(logDir, logConfig)
    val epoch = 0.toShort

    val pid1 = 1L
    val pid2 = 2L
    val pid3 = 3L
    val pid4 = 4L

    val appendPid1 = appendTransactionalAsLeader(log, pid1, epoch)
    val appendPid2 = appendTransactionalAsLeader(log, pid2, epoch)
    val appendPid3 = appendTransactionalAsLeader(log, pid3, epoch)
    val appendPid4 = appendTransactionalAsLeader(log, pid4, epoch)

    // mix transactional and non-transactional data
    appendPid1(5) // nextOffset: 5
    appendNonTransactionalAsLeader(log, 3) // 8
    appendPid2(2) // 10
    appendPid1(4) // 14
    appendPid3(3) // 17
    appendNonTransactionalAsLeader(log, 2) // 19
    appendPid1(10) // 29
    appendEndTxnMarkerAsLeader(log, pid1, epoch, ControlRecordType.ABORT) // 30
    appendPid2(6) // 36
    appendPid4(3) // 39
    appendNonTransactionalAsLeader(log, 10) // 49
    appendPid3(9) // 58
    appendEndTxnMarkerAsLeader(log, pid3, epoch, ControlRecordType.COMMIT) // 59
    appendPid4(8) // 67
    appendPid2(7) // 74
    appendEndTxnMarkerAsLeader(log, pid2, epoch, ControlRecordType.ABORT) // 75
    appendNonTransactionalAsLeader(log, 10) // 85
    appendPid4(4) // 89
    appendEndTxnMarkerAsLeader(log, pid4, epoch, ControlRecordType.COMMIT) // 90

    // delete the last offset and transaction index files to force recovery
    val lastSegment = log.logSegments.last
    val recoveryPoint = lastSegment.baseOffset
    lastSegment.offsetIndex.deleteIfExists()
    lastSegment.txnIndex.deleteIfExists()

    log.close()

    val reloadedLogConfig = LogTest.createLogConfig(segmentBytes = 1024 * 5)
    val reloadedLog = createLog(logDir, reloadedLogConfig, recoveryPoint = recoveryPoint, lastShutdownClean = false)
    val abortedTransactions = allAbortedTransactions(reloadedLog)
    assertEquals(List(new AbortedTxn(pid1, 0L, 29L, 8L), new AbortedTxn(pid2, 8L, 74L, 36L)), abortedTransactions)
  }

  @Test
  def testRecoverLastSegmentWithNoSnapshots(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 128 * 5)
    val log = createLog(logDir, logConfig)
    val epoch = 0.toShort

    val pid1 = 1L
    val pid2 = 2L
    val pid3 = 3L
    val pid4 = 4L

    val appendPid1 = appendTransactionalAsLeader(log, pid1, epoch)
    val appendPid2 = appendTransactionalAsLeader(log, pid2, epoch)
    val appendPid3 = appendTransactionalAsLeader(log, pid3, epoch)
    val appendPid4 = appendTransactionalAsLeader(log, pid4, epoch)

    // mix transactional and non-transactional data
    appendPid1(5) // nextOffset: 5
    appendNonTransactionalAsLeader(log, 3) // 8
    appendPid2(2) // 10
    appendPid1(4) // 14
    appendPid3(3) // 17
    appendNonTransactionalAsLeader(log, 2) // 19
    appendPid1(10) // 29
    appendEndTxnMarkerAsLeader(log, pid1, epoch, ControlRecordType.ABORT) // 30
    appendPid2(6) // 36
    appendPid4(3) // 39
    appendNonTransactionalAsLeader(log, 10) // 49
    appendPid3(9) // 58
    appendEndTxnMarkerAsLeader(log, pid3, epoch, ControlRecordType.COMMIT) // 59
    appendPid4(8) // 67
    appendPid2(7) // 74
    appendEndTxnMarkerAsLeader(log, pid2, epoch, ControlRecordType.ABORT) // 75
    appendNonTransactionalAsLeader(log, 10) // 85
    appendPid4(4) // 89
    appendEndTxnMarkerAsLeader(log, pid4, epoch, ControlRecordType.COMMIT) // 90

    deleteProducerSnapshotFiles()

    // delete the last offset and transaction index files to force recovery. this should force us to rebuild
    // the producer state from the start of the log
    val lastSegment = log.logSegments.last
    val recoveryPoint = lastSegment.baseOffset
    lastSegment.offsetIndex.deleteIfExists()
    lastSegment.txnIndex.deleteIfExists()

    log.close()

    val reloadedLogConfig = LogTest.createLogConfig(segmentBytes = 1024 * 5)
    val reloadedLog = createLog(logDir, reloadedLogConfig, recoveryPoint = recoveryPoint, lastShutdownClean = false)
    val abortedTransactions = allAbortedTransactions(reloadedLog)
    assertEquals(List(new AbortedTxn(pid1, 0L, 29L, 8L), new AbortedTxn(pid2, 8L, 74L, 36L)), abortedTransactions)
  }

  @Test
  def testTransactionIndexUpdatedThroughReplication(): Unit = {
    val epoch = 0.toShort
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)
    val buffer = ByteBuffer.allocate(2048)

    val pid1 = 1L
    val pid2 = 2L
    val pid3 = 3L
    val pid4 = 4L

    val appendPid1 = appendTransactionalToBuffer(buffer, pid1, epoch)
    val appendPid2 = appendTransactionalToBuffer(buffer, pid2, epoch)
    val appendPid3 = appendTransactionalToBuffer(buffer, pid3, epoch)
    val appendPid4 = appendTransactionalToBuffer(buffer, pid4, epoch)

    appendPid1(0L, 5)
    appendNonTransactionalToBuffer(buffer, 5L, 3)
    appendPid2(8L, 2)
    appendPid1(10L, 4)
    appendPid3(14L, 3)
    appendNonTransactionalToBuffer(buffer, 17L, 2)
    appendPid1(19L, 10)
    appendEndTxnMarkerToBuffer(buffer, pid1, epoch, 29L, ControlRecordType.ABORT)
    appendPid2(30L, 6)
    appendPid4(36L, 3)
    appendNonTransactionalToBuffer(buffer, 39L, 10)
    appendPid3(49L, 9)
    appendEndTxnMarkerToBuffer(buffer, pid3, epoch, 58L, ControlRecordType.COMMIT)
    appendPid4(59L, 8)
    appendPid2(67L, 7)
    appendEndTxnMarkerToBuffer(buffer, pid2, epoch, 74L, ControlRecordType.ABORT)
    appendNonTransactionalToBuffer(buffer, 75L, 10)
    appendPid4(85L, 4)
    appendEndTxnMarkerToBuffer(buffer, pid4, epoch, 89L, ControlRecordType.COMMIT)

    buffer.flip()

    appendAsFollower(log, MemoryRecords.readableRecords(buffer))

    val abortedTransactions = allAbortedTransactions(log)
    val expectedTransactions = List(
      new AbortedTxn(pid1, 0L, 29L, 8L),
      new AbortedTxn(pid2, 8L, 74L, 36L)
    )

    assertEquals(expectedTransactions, abortedTransactions)

    // Verify caching of the segment position of the first unstable offset
    log.updateHighWatermark(30L)
    assertCachedFirstUnstableOffset(log, expectedOffset = 8L)

    log.updateHighWatermark(75L)
    assertCachedFirstUnstableOffset(log, expectedOffset = 36L)

    log.updateHighWatermark(log.logEndOffset)
    assertEquals(None, log.firstUnstableOffset)
  }

  private def assertCachedFirstUnstableOffset(log: Log, expectedOffset: Long): Unit = {
    assertTrue(log.producerStateManager.firstUnstableOffset.isDefined)
    val firstUnstableOffset = log.producerStateManager.firstUnstableOffset.get
    assertEquals(expectedOffset, firstUnstableOffset.messageOffset)
    assertFalse(firstUnstableOffset.messageOffsetOnly)
    assertValidLogOffsetMetadata(log, firstUnstableOffset)
  }

  private def assertValidLogOffsetMetadata(log: Log, offsetMetadata: LogOffsetMetadata): Unit = {
    assertFalse(offsetMetadata.messageOffsetOnly)

    val segmentBaseOffset = offsetMetadata.segmentBaseOffset
    val segmentOpt = log.logSegments(segmentBaseOffset, segmentBaseOffset + 1).headOption
    assertTrue(segmentOpt.isDefined)

    val segment = segmentOpt.get
    assertEquals(segmentBaseOffset, segment.baseOffset)
    assertTrue(offsetMetadata.relativePositionInSegment <= segment.size)

    val readInfo = segment.read(offsetMetadata.messageOffset,
      maxSize = 2048,
      maxPosition = segment.size,
      minOneMessage = false)

    if (offsetMetadata.relativePositionInSegment < segment.size)
      assertEquals(offsetMetadata, readInfo.fetchOffsetMetadata)
    else
      assertNull(readInfo)
  }

  @Test
  def testZombieCoordinatorFenced(): Unit = {
    val pid = 1L
    val epoch = 0.toShort
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)

    val append = appendTransactionalAsLeader(log, pid, epoch)

    append(10)
    appendEndTxnMarkerAsLeader(log, pid, epoch, ControlRecordType.ABORT, coordinatorEpoch = 1)

    append(5)
    appendEndTxnMarkerAsLeader(log, pid, epoch, ControlRecordType.COMMIT, coordinatorEpoch = 2)

    assertThrows(classOf[TransactionCoordinatorFencedException], () => appendEndTxnMarkerAsLeader(log, pid, epoch,
      ControlRecordType.ABORT, coordinatorEpoch = 1))
  }

  @Test
  def testZombieCoordinatorFencedEmptyTransaction(): Unit = {
    val pid = 1L
    val epoch = 0.toShort
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)

    val buffer = ByteBuffer.allocate(256)
    val append = appendTransactionalToBuffer(buffer, pid, epoch, leaderEpoch = 1)
    append(0, 10)
    appendEndTxnMarkerToBuffer(buffer, pid, epoch, 10L, ControlRecordType.COMMIT,
      coordinatorEpoch = 0, leaderEpoch = 1)

    buffer.flip()
    log.appendAsFollower(MemoryRecords.readableRecords(buffer))

    appendEndTxnMarkerAsLeader(log, pid, epoch, ControlRecordType.ABORT, coordinatorEpoch = 2, leaderEpoch = 1)
    appendEndTxnMarkerAsLeader(log, pid, epoch, ControlRecordType.ABORT, coordinatorEpoch = 2, leaderEpoch = 1)
    assertThrows(classOf[TransactionCoordinatorFencedException],
      () => appendEndTxnMarkerAsLeader(log, pid, epoch, ControlRecordType.ABORT, coordinatorEpoch = 1, leaderEpoch = 1))
  }

  @Test
  def testEndTxnWithFencedProducerEpoch(): Unit = {
    val producerId = 1L
    val epoch = 5.toShort
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)
    appendEndTxnMarkerAsLeader(log, producerId, epoch, ControlRecordType.ABORT, coordinatorEpoch = 1)

    assertThrows(classOf[InvalidProducerEpochException],
      () => appendEndTxnMarkerAsLeader(log, producerId, (epoch - 1).toShort, ControlRecordType.ABORT, coordinatorEpoch = 1))
  }

  @Test
  def testLastStableOffsetDoesNotExceedLogStartOffsetMidSegment(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)
    val epoch = 0.toShort
    val pid = 1L
    val appendPid = appendTransactionalAsLeader(log, pid, epoch)

    appendPid(5)
    appendNonTransactionalAsLeader(log, 3)
    assertEquals(8L, log.logEndOffset)

    log.roll()
    assertEquals(2, log.logSegments.size)
    appendPid(5)

    assertEquals(Some(0L), log.firstUnstableOffset)

    log.updateHighWatermark(log.logEndOffset)
    log.maybeIncrementLogStartOffset(5L, ClientRecordDeletion)

    // the first unstable offset should be lower bounded by the log start offset
    assertEquals(Some(5L), log.firstUnstableOffset)
  }

  @Test
  def testLastStableOffsetDoesNotExceedLogStartOffsetAfterSegmentDeletion(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)
    val epoch = 0.toShort
    val pid = 1L
    val appendPid = appendTransactionalAsLeader(log, pid, epoch)

    appendPid(5)
    appendNonTransactionalAsLeader(log, 3)
    assertEquals(8L, log.logEndOffset)

    log.roll()
    assertEquals(2, log.logSegments.size)
    appendPid(5)

    assertEquals(Some(0L), log.firstUnstableOffset)

    log.updateHighWatermark(log.logEndOffset)
    log.maybeIncrementLogStartOffset(8L, ClientRecordDeletion)
    log.updateHighWatermark(log.logEndOffset)
    log.deleteOldSegments()
    assertEquals(1, log.logSegments.size)

    // the first unstable offset should be lower bounded by the log start offset
    assertEquals(Some(8L), log.firstUnstableOffset)
  }

  @Test
  def testAppendToTransactionIndexFailure(): Unit = {
    val pid = 1L
    val epoch = 0.toShort
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)

    val append = appendTransactionalAsLeader(log, pid, epoch)
    append(10)

    // Kind of a hack, but renaming the index to a directory ensures that the append
    // to the index will fail.
    log.activeSegment.txnIndex.renameTo(log.dir)

    // The append will be written to the log successfully, but the write to the index will fail
    assertThrows(classOf[KafkaStorageException],
      () => appendEndTxnMarkerAsLeader(log, pid, epoch, ControlRecordType.ABORT, coordinatorEpoch = 1))
    assertEquals(11L, log.logEndOffset)
    assertEquals(0L, log.lastStableOffset)

    // Try the append a second time. The appended offset in the log should not increase
    // because the log dir is marked as failed.  Nor will there be a write to the transaction
    // index.
    assertThrows(classOf[KafkaStorageException], () => appendEndTxnMarkerAsLeader(log, pid, epoch, ControlRecordType.ABORT, coordinatorEpoch = 1))
    assertEquals(11L, log.logEndOffset)
    assertEquals(0L, log.lastStableOffset)

    // Even if the high watermark is updated, the first unstable offset does not move
    log.updateHighWatermark(12L)
    assertEquals(0L, log.lastStableOffset)

    assertThrows(classOf[KafkaStorageException], () => log.close())
    val reopenedLog = createLog(logDir, logConfig, lastShutdownClean = false)
    assertEquals(11L, reopenedLog.logEndOffset)
    assertEquals(1, reopenedLog.activeSegment.txnIndex.allAbortedTxns.size)
    reopenedLog.updateHighWatermark(12L)
    assertEquals(None, reopenedLog.firstUnstableOffset)
  }

  @Test
  def testOffsetSnapshot(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)

    // append a few records
    appendAsFollower(log, MemoryRecords.withRecords(CompressionType.NONE,
      new SimpleRecord("a".getBytes),
      new SimpleRecord("b".getBytes),
      new SimpleRecord("c".getBytes)), 5)


    log.updateHighWatermark(2L)
    var offsets: LogOffsetSnapshot = log.fetchOffsetSnapshot
    assertEquals(offsets.highWatermark.messageOffset, 2L)
    assertFalse(offsets.highWatermark.messageOffsetOnly)

    offsets = log.fetchOffsetSnapshot
    assertEquals(offsets.highWatermark.messageOffset, 2L)
    assertFalse(offsets.highWatermark.messageOffsetOnly)
  }

  @Test
  def testLastStableOffsetWithMixedProducerData(): Unit = {
    val logConfig = LogTest.createLogConfig(segmentBytes = 1024 * 1024 * 5)
    val log = createLog(logDir, logConfig)

    // for convenience, both producers share the same epoch
    val epoch = 5.toShort

    val pid1 = 137L
    val seq1 = 0
    val pid2 = 983L
    val seq2 = 0

    // add some transactional records
    val firstAppendInfo = log.appendAsLeader(MemoryRecords.withTransactionalRecords(CompressionType.NONE, pid1, epoch, seq1,
      new SimpleRecord("a".getBytes),
      new SimpleRecord("b".getBytes),
      new SimpleRecord("c".getBytes)), leaderEpoch = 0)
    assertEquals(firstAppendInfo.firstOffset.map(_.messageOffset), log.firstUnstableOffset)

    // mix in some non-transactional data
    log.appendAsLeader(MemoryRecords.withRecords(CompressionType.NONE,
      new SimpleRecord("g".getBytes),
      new SimpleRecord("h".getBytes),
      new SimpleRecord("i".getBytes)), leaderEpoch = 0)

    // append data from a second transactional producer
    val secondAppendInfo = log.appendAsLeader(MemoryRecords.withTransactionalRecords(CompressionType.NONE, pid2, epoch, seq2,
      new SimpleRecord("d".getBytes),
      new SimpleRecord("e".getBytes),
      new SimpleRecord("f".getBytes)), leaderEpoch = 0)

    // LSO should not have changed
    assertEquals(firstAppendInfo.firstOffset.map(_.messageOffset), log.firstUnstableOffset)

    // now first producer's transaction is aborted
    val abortAppendInfo = appendEndTxnMarkerAsLeader(log, pid1, epoch, ControlRecordType.ABORT)
    log.updateHighWatermark(abortAppendInfo.lastOffset + 1)

    // LSO should now point to one less than the first offset of the second transaction
    assertEquals(secondAppendInfo.firstOffset.map(_.messageOffset), log.firstUnstableOffset)

    // commit the second transaction
    val commitAppendInfo = appendEndTxnMarkerAsLeader(log, pid2, epoch, ControlRecordType.COMMIT)
    log.updateHighWatermark(commitAppendInfo.lastOffset + 1)

    // now there should be no first unstable offset
    assertEquals(None, log.firstUnstableOffset)
  }

  @Test
  def testAbortedTransactionSpanningMultipleSegments(): Unit = {
    val pid = 137L
    val epoch = 5.toShort
    var seq = 0

    val records = MemoryRecords.withTransactionalRecords(CompressionType.NONE, pid, epoch, seq,
      new SimpleRecord("a".getBytes),
      new SimpleRecord("b".getBytes),
      new SimpleRecord("c".getBytes))

    val logConfig = LogTest.createLogConfig(segmentBytes = records.sizeInBytes)
    val log = createLog(logDir, logConfig)

    val firstAppendInfo = log.appendAsLeader(records, leaderEpoch = 0)
    assertEquals(firstAppendInfo.firstOffset.map(_.messageOffset), log.firstUnstableOffset)

    // this write should spill to the second segment
    seq = 3
    log.appendAsLeader(MemoryRecords.withTransactionalRecords(CompressionType.NONE, pid, epoch, seq,
      new SimpleRecord("d".getBytes),
      new SimpleRecord("e".getBytes),
      new SimpleRecord("f".getBytes)), leaderEpoch = 0)
    assertEquals(firstAppendInfo.firstOffset.map(_.messageOffset), log.firstUnstableOffset)
    assertEquals(3L, log.logEndOffsetMetadata.segmentBaseOffset)

    // now abort the transaction
    val abortAppendInfo = appendEndTxnMarkerAsLeader(log, pid, epoch, ControlRecordType.ABORT)
    log.updateHighWatermark(abortAppendInfo.lastOffset + 1)
    assertEquals(None, log.firstUnstableOffset)

    // now check that a fetch includes the aborted transaction
    val fetchDataInfo = log.read(0L,
      maxLength = 2048,
      isolation = FetchTxnCommitted,
      minOneMessage = true)
    assertEquals(1, fetchDataInfo.abortedTransactions.size)

    assertTrue(fetchDataInfo.abortedTransactions.isDefined)
    assertEquals(new AbortedTransaction(pid, 0), fetchDataInfo.abortedTransactions.get.head)
  }

  @Test
  def testLoadPartitionDirWithNoSegmentsShouldNotThrow(): Unit = {
    val dirName = Log.logDeleteDirName(new TopicPartition("foo", 3))
    val logDir = new File(tmpDir, dirName)
    logDir.mkdirs()
    val logConfig = LogTest.createLogConfig()
    val log = createLog(logDir, logConfig)
    assertEquals(1, log.numberOfSegments)
  }

  private def allAbortedTransactions(log: Log) = log.logSegments.flatMap(_.txnIndex.allAbortedTxns)

  private def appendTransactionalAsLeader(
    log: Log,
    producerId: Long,
    producerEpoch: Short
  ): Int => Unit = {
    appendIdempotentAsLeader(log, producerId, producerEpoch, isTransactional = true)
  }

  private def appendIdempotentAsLeader(
    log: Log,
    producerId: Long,
    producerEpoch: Short,
    isTransactional: Boolean = false
  ): Int => Unit = {
    var sequence = 0
    numRecords: Int => {
      val simpleRecords = (sequence until sequence + numRecords).map { seq =>
        new SimpleRecord(mockTime.milliseconds(), s"$seq".getBytes)
      }

      val records = if (isTransactional) {
        MemoryRecords.withTransactionalRecords(CompressionType.NONE, producerId,
          producerEpoch, sequence, simpleRecords: _*)
      } else {
        MemoryRecords.withIdempotentRecords(CompressionType.NONE, producerId,
          producerEpoch, sequence, simpleRecords: _*)
      }

      log.appendAsLeader(records, leaderEpoch = 0)
      sequence += numRecords
    }
  }

  private def appendEndTxnMarkerAsLeader(log: Log,
                                         producerId: Long,
                                         producerEpoch: Short,
                                         controlType: ControlRecordType,
                                         coordinatorEpoch: Int = 0,
                                         leaderEpoch: Int = 0,
                                         timestamp: Long = mockTime.milliseconds()): LogAppendInfo = {
    val records = endTxnRecords(controlType, producerId, producerEpoch,
      coordinatorEpoch = coordinatorEpoch, timestamp = timestamp)
    log.appendAsLeader(records, origin = AppendOrigin.Coordinator, leaderEpoch = leaderEpoch)
  }

  private def appendNonTransactionalAsLeader(log: Log, numRecords: Int): Unit = {
    val simpleRecords = (0 until numRecords).map { seq =>
      new SimpleRecord(s"$seq".getBytes)
    }
    val records = MemoryRecords.withRecords(CompressionType.NONE, simpleRecords: _*)
    log.appendAsLeader(records, leaderEpoch = 0)
  }

  private def appendTransactionalToBuffer(buffer: ByteBuffer,
                                          producerId: Long,
                                          producerEpoch: Short,
                                          leaderEpoch: Int = 0): (Long, Int) => Unit = {
    var sequence = 0
    (offset: Long, numRecords: Int) => {
      val builder = MemoryRecords.builder(buffer, RecordBatch.CURRENT_MAGIC_VALUE, CompressionType.NONE, TimestampType.CREATE_TIME,
        offset, mockTime.milliseconds(), producerId, producerEpoch, sequence, true, leaderEpoch)
      for (seq <- sequence until sequence + numRecords) {
        val record = new SimpleRecord(s"$seq".getBytes)
        builder.append(record)
      }

      sequence += numRecords
      builder.close()
    }
  }

  private def appendEndTxnMarkerToBuffer(buffer: ByteBuffer,
                                         producerId: Long,
                                         producerEpoch: Short,
                                         offset: Long,
                                         controlType: ControlRecordType,
                                         coordinatorEpoch: Int = 0,
                                         leaderEpoch: Int = 0): Unit = {
    val marker = new EndTransactionMarker(controlType, coordinatorEpoch)
    MemoryRecords.writeEndTransactionalMarker(buffer, offset, mockTime.milliseconds(), leaderEpoch, producerId, producerEpoch, marker)
  }

  private def appendNonTransactionalToBuffer(buffer: ByteBuffer, offset: Long, numRecords: Int): Unit = {
    val builder = MemoryRecords.builder(buffer, CompressionType.NONE, TimestampType.CREATE_TIME, offset)
    (0 until numRecords).foreach { seq =>
      builder.append(new SimpleRecord(s"$seq".getBytes))
    }
    builder.close()
  }

  private def appendAsFollower(log: Log, records: MemoryRecords, leaderEpoch: Int = 0): Unit = {
    records.batches.forEach(_.setPartitionLeaderEpoch(leaderEpoch))
    log.appendAsFollower(records)
  }

  private def deleteProducerSnapshotFiles(): Unit = {
    val files = logDir.listFiles.filter(f => f.isFile && f.getName.endsWith(Log.ProducerSnapshotFileSuffix))
    files.foreach(Utils.delete)
  }

  private def listProducerSnapshotOffsets: Seq[Long] =
    ProducerStateManager.listSnapshotFiles(logDir).map(_.offset).sorted

  private def createLog(dir: File,
                        config: LogConfig,
                        brokerTopicStats: BrokerTopicStats = brokerTopicStats,
                        logStartOffset: Long = 0L,
                        recoveryPoint: Long = 0L,
                        scheduler: Scheduler = mockTime.scheduler,
                        time: Time = mockTime,
                        maxProducerIdExpirationMs: Int = 60 * 60 * 1000,
                        producerIdExpirationCheckIntervalMs: Int = LogManager.ProducerIdExpirationCheckIntervalMs,
                        lastShutdownClean: Boolean = true,
                        keepPartitionMetadataFile: Boolean = true): Log = {
    LogTest.createLog(dir, config, brokerTopicStats, scheduler, time, logStartOffset, recoveryPoint,
      maxProducerIdExpirationMs, producerIdExpirationCheckIntervalMs, lastShutdownClean, keepPartitionMetadataFile)
  }

  private def createLogWithOffsetOverflow(logConfig: LogConfig): (Log, LogSegment) = {
    LogTest.initializeLogDirWithOverflowedSegment(logDir)

    val log = createLog(logDir, logConfig, recoveryPoint = Long.MaxValue)
    val segmentWithOverflow = LogTest.firstOverflowSegment(log).getOrElse {
      throw new AssertionError("Failed to create log with a segment which has overflowed offsets")
    }

    (log, segmentWithOverflow)
  }

  private def recoverAndCheck(config: LogConfig, expectedKeys: Iterable[Long]) = {
    // method is called only in case of recovery from hard reset
    LogTest.recoverAndCheck(logDir, config, expectedKeys, brokerTopicStats, mockTime, mockTime.scheduler)
  }

  private def readLog(log: Log,
                      startOffset: Long,
                      maxLength: Int,
                      isolation: FetchIsolation = FetchLogEnd,
                      minOneMessage: Boolean = true): FetchDataInfo = {
    log.read(startOffset, maxLength, isolation, minOneMessage)
  }

}

object LogTest {
  def createLogConfig(segmentMs: Long = Defaults.SegmentMs,
                      segmentBytes: Int = Defaults.SegmentSize,
                      retentionMs: Long = Defaults.RetentionMs,
                      retentionBytes: Long = Defaults.RetentionSize,
                      segmentJitterMs: Long = Defaults.SegmentJitterMs,
                      cleanupPolicy: String = Defaults.CleanupPolicy,
                      maxMessageBytes: Int = Defaults.MaxMessageSize,
                      indexIntervalBytes: Int = Defaults.IndexInterval,
                      segmentIndexBytes: Int = Defaults.MaxIndexSize,
                      messageFormatVersion: String = Defaults.MessageFormatVersion,
                      fileDeleteDelayMs: Long = Defaults.FileDeleteDelayMs): LogConfig = {
    val logProps = new Properties()

    logProps.put(LogConfig.SegmentMsProp, segmentMs: java.lang.Long)
    logProps.put(LogConfig.SegmentBytesProp, segmentBytes: Integer)
    logProps.put(LogConfig.RetentionMsProp, retentionMs: java.lang.Long)
    logProps.put(LogConfig.RetentionBytesProp, retentionBytes: java.lang.Long)
    logProps.put(LogConfig.SegmentJitterMsProp, segmentJitterMs: java.lang.Long)
    logProps.put(LogConfig.CleanupPolicyProp, cleanupPolicy)
    logProps.put(LogConfig.MaxMessageBytesProp, maxMessageBytes: Integer)
    logProps.put(LogConfig.IndexIntervalBytesProp, indexIntervalBytes: Integer)
    logProps.put(LogConfig.SegmentIndexBytesProp, segmentIndexBytes: Integer)
    logProps.put(LogConfig.MessageFormatVersionProp, messageFormatVersion)
    logProps.put(LogConfig.FileDeleteDelayMsProp, fileDeleteDelayMs: java.lang.Long)
    LogConfig(logProps)
  }

  def createLog(dir: File,
                config: LogConfig,
                brokerTopicStats: BrokerTopicStats,
                scheduler: Scheduler,
                time: Time,
                logStartOffset: Long = 0L,
                recoveryPoint: Long = 0L,
                maxProducerIdExpirationMs: Int = 60 * 60 * 1000,
                producerIdExpirationCheckIntervalMs: Int = LogManager.ProducerIdExpirationCheckIntervalMs,
                lastShutdownClean: Boolean = true,
                keepPartitionMetadataFile: Boolean = true): Log = {
    Log(dir = dir,
      config = config,
      logStartOffset = logStartOffset,
      recoveryPoint = recoveryPoint,
      scheduler = scheduler,
      brokerTopicStats = brokerTopicStats,
      time = time,
      maxProducerIdExpirationMs = maxProducerIdExpirationMs,
      producerIdExpirationCheckIntervalMs = producerIdExpirationCheckIntervalMs,
      logDirFailureChannel = new LogDirFailureChannel(10),
      lastShutdownClean = lastShutdownClean,
      keepPartitionMetadataFile = keepPartitionMetadataFile)
  }

  /**
   * Check if the given log contains any segment with records that cause offset overflow.
   * @param log Log to check
   * @return true if log contains at least one segment with offset overflow; false otherwise
   */
  def hasOffsetOverflow(log: Log): Boolean = firstOverflowSegment(log).isDefined

  def firstOverflowSegment(log: Log): Option[LogSegment] = {
    def hasOverflow(baseOffset: Long, batch: RecordBatch): Boolean =
      batch.lastOffset > baseOffset + Int.MaxValue || batch.baseOffset < baseOffset

    for (segment <- log.logSegments) {
      val overflowBatch = segment.log.batches.asScala.find(batch => hasOverflow(segment.baseOffset, batch))
      if (overflowBatch.isDefined)
        return Some(segment)
    }
    None
  }

  private def rawSegment(logDir: File, baseOffset: Long): FileRecords =
    FileRecords.open(Log.logFile(logDir, baseOffset))

  /**
   * Initialize the given log directory with a set of segments, one of which will have an
   * offset which overflows the segment
   */
  def initializeLogDirWithOverflowedSegment(logDir: File): Unit = {
    def writeSampleBatches(baseOffset: Long, segment: FileRecords): Long = {
      def record(offset: Long) = {
        val data = offset.toString.getBytes
        new SimpleRecord(data, data)
      }

      segment.append(MemoryRecords.withRecords(baseOffset, CompressionType.NONE, 0,
        record(baseOffset)))
      segment.append(MemoryRecords.withRecords(baseOffset + 1, CompressionType.NONE, 0,
        record(baseOffset + 1),
        record(baseOffset + 2)))
      segment.append(MemoryRecords.withRecords(baseOffset + Int.MaxValue - 1, CompressionType.NONE, 0,
        record(baseOffset + Int.MaxValue - 1)))
      // Need to create the offset files explicitly to avoid triggering segment recovery to truncate segment.
      Log.offsetIndexFile(logDir, baseOffset).createNewFile()
      Log.timeIndexFile(logDir, baseOffset).createNewFile()
      baseOffset + Int.MaxValue
    }

    def writeNormalSegment(baseOffset: Long): Long = {
      val segment = rawSegment(logDir, baseOffset)
      try writeSampleBatches(baseOffset, segment)
      finally segment.close()
    }

    def writeOverflowSegment(baseOffset: Long): Long = {
      val segment = rawSegment(logDir, baseOffset)
      try {
        val nextOffset = writeSampleBatches(baseOffset, segment)
        writeSampleBatches(nextOffset, segment)
      } finally segment.close()
    }

    // We create three segments, the second of which contains offsets which overflow
    var nextOffset = 0L
    nextOffset = writeNormalSegment(nextOffset)
    nextOffset = writeOverflowSegment(nextOffset)
    writeNormalSegment(nextOffset)
  }

  def allRecords(log: Log): List[Record] = {
    val recordsFound = ListBuffer[Record]()
    for (logSegment <- log.logSegments) {
      for (batch <- logSegment.log.batches.asScala) {
        recordsFound ++= batch.iterator().asScala
      }
    }
    recordsFound.toList
  }

  def verifyRecordsInLog(log: Log, expectedRecords: List[Record]): Unit = {
    assertEquals(expectedRecords, allRecords(log))
  }

  /* extract all the keys from a log */
  def keysInLog(log: Log): Iterable[Long] = {
    for (logSegment <- log.logSegments;
         batch <- logSegment.log.batches.asScala if !batch.isControlBatch;
         record <- batch.asScala if record.hasValue && record.hasKey)
      yield TestUtils.readString(record.key).toLong
  }

  def recoverAndCheck(logDir: File, config: LogConfig, expectedKeys: Iterable[Long], brokerTopicStats: BrokerTopicStats, time: Time, scheduler: Scheduler): Log = {
    // Recover log file and check that after recovery, keys are as expected
    // and all temporary files have been deleted
    val recoveredLog = createLog(logDir, config, brokerTopicStats, scheduler, time, lastShutdownClean = false)
    time.sleep(config.fileDeleteDelayMs + 1)
    for (file <- logDir.listFiles) {
      assertFalse(file.getName.endsWith(Log.DeletedFileSuffix), "Unexpected .deleted file after recovery")
      assertFalse(file.getName.endsWith(Log.CleanedFileSuffix), "Unexpected .cleaned file after recovery")
      assertFalse(file.getName.endsWith(Log.SwapFileSuffix), "Unexpected .swap file after recovery")
    }
    assertEquals(expectedKeys, LogTest.keysInLog(recoveredLog))
    assertFalse(LogTest.hasOffsetOverflow(recoveredLog))
    recoveredLog
  }
}
