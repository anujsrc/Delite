package ppl.delite.runtime.graph.ops

import java.util.concurrent.locks.ReentrantLock

/**
 * Author: Kevin J. Brown
 * Date: Nov 29, 2010
 * Time: 11:21:14 PM
 * 
 * Pervasive Parallelism Laboratory (PPL)
 * Stanford University
 */

/**
 * This is a special singleton OP that signifies the end of the application
 * This OP should always be inserted by the scheduler such that it is the last to run (depends on the "result" node of the task graph
 * Execution of the kernel will shut down the Delite Runtime
 */
object EOP extends DeliteOP {

  /**
   * OP features
   */
  def isDataParallel = false

  def task = "ppl.delite.runtime.graph.ops.EOP_Kernel"

  def outputType = "Unit"

  def nested = null
  def cost = 0
  def size = 0

  /**
   * EOP implementation
   */
  private val lock = new ReentrantLock
  private val cond = lock.newCondition
  private var notDone: Boolean = true

  def signalAll {
    lock.lock
    try {
      notDone = false
      cond.signalAll
    }
    finally {
      lock.unlock
    }
  }

  def await {
    lock.lock
    try {
      while (notDone) cond.await
    }
    finally {
      lock.unlock
    }
  }

}

object EOP_Kernel {

  def apply() {
    EOP.signalAll
  }

}