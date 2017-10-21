package com.lightning.wallet.helper


abstract class ThrottledWork[T, V] {
  import rx.lang.scala.{Observable => Obs}
  private var lastWork: Option[T] = None
  private var isWorking: Boolean = false

  def work(input: T): Obs[V]
  def process(result: V): Unit
  def error(err: Throwable): Unit

  private def doWork(input: T) = work(input)
    .doAfterTerminate { lastWork foreach addWork }
    .doOnTerminate { isWorking = false }
    .doOnSubscribe { isWorking = true }
    .doOnSubscribe { lastWork = None }
    .subscribe(process, error)

  def addWork(data: T): Unit =
    if (isWorking) lastWork = Some(data)
    else doWork(data)
}