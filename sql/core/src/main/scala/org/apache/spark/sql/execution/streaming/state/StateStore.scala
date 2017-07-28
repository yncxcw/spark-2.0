/*
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

package org.apache.spark.sql.execution.streaming.state

import java.util.concurrent.{ScheduledFuture, TimeUnit}
import javax.annotation.concurrent.GuardedBy

import scala.collection.mutable
import scala.util.control.NonFatal

import org.apache.hadoop.conf.Configuration

import org.apache.spark.SparkEnv
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.{ThreadUtils, Utils}


/**
 * Base trait for a versioned key-value store. Each instance of a `StateStore` represents a specific
 * version of state data, and such instances are created through a [[StateStoreProvider]].
 */
trait StateStore {

  /** Unique identifier of the store */
  def id: StateStoreId

  /** Version of the data in this store before committing updates. */
  def version: Long

  /**
   * Get the current value of a non-null key.
   * @return a non-null row if the key exists in the store, otherwise null.
   */
  def get(key: UnsafeRow): UnsafeRow

  /**
   * Put a new value for a non-null key. Implementations must be aware that the UnsafeRows in
   * the params can be reused, and must make copies of the data as needed for persistence.
   */
  def put(key: UnsafeRow, value: UnsafeRow): Unit

  /**
   * Remove a single non-null key.
   */
  def remove(key: UnsafeRow): Unit

  /**
   * Get key value pairs with optional approximate `start` and `end` extents.
   * If the State Store implementation maintains indices for the data based on the optional
   * `keyIndexOrdinal` over fields `keySchema` (see `StateStoreProvider.init()`), then it can use
   * `start` and `end` to make a best-effort scan over the data. Default implementation returns
   * the full data scan iterator, which is correct but inefficient. Custom implementations must
   * ensure that updates (puts, removes) can be made while iterating over this iterator.
   *
   * @param start UnsafeRow having the `keyIndexOrdinal` column set with appropriate starting value.
   * @param end UnsafeRow having the `keyIndexOrdinal` column set with appropriate ending value.
   * @return An iterator of key-value pairs that is guaranteed not miss any key between start and
   *         end, both inclusive.
   */
  def getRange(start: Option[UnsafeRow], end: Option[UnsafeRow]): Iterator[UnsafeRowPair] = {
    iterator()
  }

  /**
   * Commit all the updates that have been made to the store, and return the new version.
   * Implementations should ensure that no more updates (puts, removes) can be after a commit in
   * order to avoid incorrect usage.
   */
  def commit(): Long

  /**
   * Abort all the updates that have been made to the store. Implementations should ensure that
   * no more updates (puts, removes) can be after an abort in order to avoid incorrect usage.
   */
  def abort(): Unit

  def iterator(): Iterator[UnsafeRowPair]

  /** Number of keys in the state store */
  def numKeys(): Long

  /**
   * Whether all updates have been committed
   */
  private[streaming] def hasCommitted: Boolean
}


/**
 * Trait representing a provider that provide [[StateStore]] instances representing
 * versions of state data.
 *
 * The life cycle of a provider and its provide stores are as follows.
 *
 * - A StateStoreProvider is created in a executor for each unique [[StateStoreId]] when
 *   the first batch of a streaming query is executed on the executor. All subsequent batches reuse
 *   this provider instance until the query is stopped.
 *
 * - Every batch of streaming data request a specific version of the state data by invoking
 *   `getStore(version)` which returns an instance of [[StateStore]] through which the required
 *   version of the data can be accessed. It is the responsible of the provider to populate
 *   this store with context information like the schema of keys and values, etc.
 *
 * - After the streaming query is stopped, the created provider instances are lazily disposed off.
 */
trait StateStoreProvider {

  /**
   * Initialize the provide with more contextual information from the SQL operator.
   * This method will be called first after creating an instance of the StateStoreProvider by
   * reflection.
   *
   * @param stateStoreId Id of the versioned StateStores that this provider will generate
   * @param keySchema Schema of keys to be stored
   * @param valueSchema Schema of value to be stored
   * @param keyIndexOrdinal Optional column (represent as the ordinal of the field in keySchema) by
   *                        which the StateStore implementation could index the data.
   * @param storeConfs Configurations used by the StateStores
   * @param hadoopConf Hadoop configuration that could be used by StateStore to save state data
   */
  def init(
      stateStoreId: StateStoreId,
      keySchema: StructType,
      valueSchema: StructType,
      keyIndexOrdinal: Option[Int], // for sorting the data by their keys
      storeConfs: StateStoreConf,
      hadoopConf: Configuration): Unit

  /**
   * Return the id of the StateStores this provider will generate.
   * Should be the same as the one passed in init().
   */
  def id: StateStoreId

  /** Called when the provider instance is unloaded from the executor */
  def close(): Unit

  /** Return an instance of [[StateStore]] representing state data of the given version */
  def getStore(version: Long): StateStore

  /** Optional method for providers to allow for background maintenance (e.g. compactions) */
  def doMaintenance(): Unit = { }
}

object StateStoreProvider {
  /**
   * Return a provider instance of the given provider class.
   * The instance will be already initialized.
   */
  def instantiate(
      stateStoreId: StateStoreId,
      keySchema: StructType,
      valueSchema: StructType,
      indexOrdinal: Option[Int], // for sorting the data
      storeConf: StateStoreConf,
      hadoopConf: Configuration): StateStoreProvider = {
    val providerClass = storeConf.providerClass.map(Utils.classForName)
        .getOrElse(classOf[HDFSBackedStateStoreProvider])
    val provider = providerClass.newInstance().asInstanceOf[StateStoreProvider]
    provider.init(stateStoreId, keySchema, valueSchema, indexOrdinal, storeConf, hadoopConf)
    provider
  }
}


/** Unique identifier for a bunch of keyed state data. */
case class StateStoreId(
    checkpointLocation: String,
    operatorId: Long,
    partitionId: Int,
    name: String = "")

/** Mutable, and reusable class for representing a pair of UnsafeRows. */
class UnsafeRowPair(var key: UnsafeRow = null, var value: UnsafeRow = null) {
  def withRows(key: UnsafeRow, value: UnsafeRow): UnsafeRowPair = {
    this.key = key
    this.value = value
    this
  }
}


/**
 * Companion object to [[StateStore]] that provides helper methods to create and retrieve stores
 * by their unique ids. In addition, when a SparkContext is active (i.e. SparkEnv.get is not null),
 * it also runs a periodic background task to do maintenance on the loaded stores. For each
 * store, it uses the [[StateStoreCoordinator]] to ensure whether the current loaded instance of
 * the store is the active instance. Accordingly, it either keeps it loaded and performs
 * maintenance, or unloads the store.
 */
object StateStore extends Logging {

  val MAINTENANCE_INTERVAL_CONFIG = "spark.sql.streaming.stateStore.maintenanceInterval"
  val MAINTENANCE_INTERVAL_DEFAULT_SECS = 60

  @GuardedBy("loadedProviders")
  private val loadedProviders = new mutable.HashMap[StateStoreId, StateStoreProvider]()

  /**
   * Runs the `task` periodically and automatically cancels it if there is an exception. `onError`
   * will be called when an exception happens.
   */
  class MaintenanceTask(periodMs: Long, task: => Unit, onError: => Unit) {
    private val executor =
      ThreadUtils.newDaemonSingleThreadScheduledExecutor("state-store-maintenance-task")

    private val runnable = new Runnable {
      override def run(): Unit = {
        try {
          task
        } catch {
          case NonFatal(e) =>
            logWarning("Error running maintenance thread", e)
            onError
            throw e
        }
      }
    }

    private val future: ScheduledFuture[_] = executor.scheduleAtFixedRate(
      runnable, periodMs, periodMs, TimeUnit.MILLISECONDS)

    def stop(): Unit = {
      future.cancel(false)
      executor.shutdown()
    }

    def isRunning: Boolean = !future.isDone
  }

  @GuardedBy("loadedProviders")
  private var maintenanceTask: MaintenanceTask = null

  @GuardedBy("loadedProviders")
  private var _coordRef: StateStoreCoordinatorRef = null

  /** Get or create a store associated with the id. */
  def get(
      storeId: StateStoreId,
      keySchema: StructType,
      valueSchema: StructType,
      indexOrdinal: Option[Int],
      version: Long,
      storeConf: StateStoreConf,
      hadoopConf: Configuration): StateStore = {
    require(version >= 0)
    val storeProvider = loadedProviders.synchronized {
      startMaintenanceIfNeeded()
      val provider = loadedProviders.getOrElseUpdate(
        storeId,
        StateStoreProvider.instantiate(
          storeId, keySchema, valueSchema, indexOrdinal, storeConf, hadoopConf)
      )
      reportActiveStoreInstance(storeId)
      provider
    }
    storeProvider.getStore(version)
  }

  /** Unload a state store provider */
  def unload(storeId: StateStoreId): Unit = loadedProviders.synchronized {
    loadedProviders.remove(storeId).foreach(_.close())
  }

  /** Whether a state store provider is loaded or not */
  def isLoaded(storeId: StateStoreId): Boolean = loadedProviders.synchronized {
    loadedProviders.contains(storeId)
  }

  def isMaintenanceRunning: Boolean = loadedProviders.synchronized {
    maintenanceTask != null && maintenanceTask.isRunning
  }

  /** Unload and stop all state store providers */
  def stop(): Unit = loadedProviders.synchronized {
    loadedProviders.keySet.foreach { key => unload(key) }
    loadedProviders.clear()
    _coordRef = null
    if (maintenanceTask != null) {
      maintenanceTask.stop()
      maintenanceTask = null
    }
    logInfo("StateStore stopped")
  }

  /** Start the periodic maintenance task if not already started and if Spark active */
  private def startMaintenanceIfNeeded(): Unit = loadedProviders.synchronized {
    val env = SparkEnv.get
    if (env != null && !isMaintenanceRunning) {
      val periodMs = env.conf.getTimeAsMs(
        MAINTENANCE_INTERVAL_CONFIG, s"${MAINTENANCE_INTERVAL_DEFAULT_SECS}s")
      maintenanceTask = new MaintenanceTask(
        periodMs,
        task = { doMaintenance() },
        onError = { loadedProviders.synchronized { loadedProviders.clear() } }
      )
      logInfo("State Store maintenance task started")
    }
  }

  /**
   * Execute background maintenance task in all the loaded store providers if they are still
   * the active instances according to the coordinator.
   */
  private def doMaintenance(): Unit = {
    logDebug("Doing maintenance")
    if (SparkEnv.get == null) {
      throw new IllegalStateException("SparkEnv not active, cannot do maintenance on StateStores")
    }
    loadedProviders.synchronized { loadedProviders.toSeq }.foreach { case (id, provider) =>
      try {
        if (verifyIfStoreInstanceActive(id)) {
          provider.doMaintenance()
        } else {
          unload(id)
          logInfo(s"Unloaded $provider")
        }
      } catch {
        case NonFatal(e) =>
          logWarning(s"Error managing $provider, stopping management thread")
          throw e
      }
    }
  }

  private def reportActiveStoreInstance(storeId: StateStoreId): Unit = {
    if (SparkEnv.get != null) {
      val host = SparkEnv.get.blockManager.blockManagerId.host
      val executorId = SparkEnv.get.blockManager.blockManagerId.executorId
      coordinatorRef.foreach(_.reportActiveInstance(storeId, host, executorId))
      logDebug(s"Reported that the loaded instance $storeId is active")
    }
  }

  private def verifyIfStoreInstanceActive(storeId: StateStoreId): Boolean = {
    if (SparkEnv.get != null) {
      val executorId = SparkEnv.get.blockManager.blockManagerId.executorId
      val verified =
        coordinatorRef.map(_.verifyIfInstanceActive(storeId, executorId)).getOrElse(false)
      logDebug(s"Verified whether the loaded instance $storeId is active: $verified")
      verified
    } else {
      false
    }
  }

  private def coordinatorRef: Option[StateStoreCoordinatorRef] = loadedProviders.synchronized {
    val env = SparkEnv.get
    if (env != null) {
      if (_coordRef == null) {
        _coordRef = StateStoreCoordinatorRef.forExecutor(env)
      }
      logDebug(s"Retrieved reference to StateStoreCoordinator: ${_coordRef}")
      Some(_coordRef)
    } else {
      _coordRef = null
      None
    }
  }
}

