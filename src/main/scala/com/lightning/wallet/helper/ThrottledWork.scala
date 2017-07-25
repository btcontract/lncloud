package com.lightning.wallet.helper

import rx.lang.scala.{Observable => Obs}
import com.lightning.wallet.ln.Tools.none


abstract class ThrottledWork[T] {
  private var lastInput: Option[String] = None
  private var isWorking: Boolean = false
  def work(input: String): Obs[T]
  def process(result: T): Unit

  private def doWork(input: String) = work(input)
    .doAfterTerminate { lastInput foreach onNewQuery }
    .doOnTerminate { isWorking = false }
    .doOnSubscribe { isWorking = true }
    .doOnSubscribe { lastInput = None }
    .subscribe(process, none)

  def onNewQuery(input: String): Unit =
    if (isWorking) lastInput = Some(input)
    else doWork(input)
}