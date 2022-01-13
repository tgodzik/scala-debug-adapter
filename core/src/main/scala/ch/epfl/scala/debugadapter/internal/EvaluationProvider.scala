package ch.epfl.scala.debugadapter.internal

import ch.epfl.scala.debugadapter.DebuggeeRunner
import ch.epfl.scala.debugadapter.internal.evaluator.ExpressionCompiler
import ch.epfl.scala.debugadapter.internal.evaluator.ExpressionEvaluator
import ch.epfl.scala.debugadapter.internal.evaluator.JdiObject
import ch.epfl.scala.debugadapter.internal.evaluator.MethodInvocationFailed
import com.microsoft.java.debug.core.IEvaluatableBreakpoint
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext
import com.microsoft.java.debug.core.adapter.IEvaluationProvider
import com.sun.jdi.ObjectReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Failure
import scala.util.Success
import com.microsoft.java.debug.plugin.internal.eval.JdtEvaluationProvider

private[internal] object EvaluationProvider {
  def apply(
      runner: DebuggeeRunner,
      sourceLookUpProvider: SourceLookUpProvider
  ): IEvaluationProvider = {
    val evaluator = runner.evaluationClassLoader
      .flatMap(ExpressionCompiler(_))
      .map(
        new ExpressionEvaluator(
          runner.classPath,
          sourceLookUpProvider,
          _
        )
      )
    new EvaluationProvider(evaluator)
  }
}

private[internal] class EvaluationProvider(
    evaluator: Option[ExpressionEvaluator]
) extends IEvaluationProvider {

  private var debugContext: IDebugAdapterContext = _
  private val isEvaluating = new AtomicBoolean(false)
  private val javaEval = new JdtEvaluationProvider()
  override def initialize(
      debugContext: IDebugAdapterContext,
      options: java.util.Map[String, AnyRef]
  ): Unit = {
    this.debugContext = debugContext
    javaEval.initialize(debugContext, options)
  }

  override def isInEvaluation(thread: ThreadReference) = isEvaluating.get()

  override def evaluate(
      expression: String,
      thread: ThreadReference,
      depth: Int
  ): CompletableFuture[Value] = {
    val frame = thread.frames().get(depth)
    val isJava = frame.location().sourcePath().endsWith(".java")
    val future = new CompletableFuture[Value]()
    evaluator match {
      case None =>
        future.completeExceptionally(
          new Exception("Missing evaluator for this debug session")
        )
        debugContext.getStackFrameManager.reloadStackFrames(thread)
        future
      case _ if isJava =>
        javaEval.evaluate(expression, thread, depth)
      case Some(evaluator) =>
        evaluationBlock {
          evaluator.evaluate(expression, thread, frame) match {
            case Failure(exception) =>
              future.completeExceptionally(exception)
            case Success(value) =>
              future.complete(value)
          }
        }
        debugContext.getStackFrameManager.reloadStackFrames(thread)
        future
    }

  }

  override def evaluate(
      expression: String,
      thisContext: ObjectReference,
      thread: ThreadReference
  ): CompletableFuture[Value] = ???

  override def evaluateForBreakpoint(
      breakpoint: IEvaluatableBreakpoint,
      thread: ThreadReference
  ): CompletableFuture[Value] = ???

  override def invokeMethod(
      thisContext: ObjectReference,
      methodName: String,
      methodSignature: String,
      args: Array[Value],
      thread: ThreadReference,
      invokeSuper: Boolean
  ): CompletableFuture[Value] = {
    val future = new CompletableFuture[Value]()
    val obj = new JdiObject(thisContext, thread)
    evaluationBlock {
      val invocation = obj
        .invoke(
          methodName,
          methodSignature,
          if (args == null) List() else args.toList
        )
        .recover {
          // if invocation throws an exception, we return that exception as the result
          case MethodInvocationFailed(msg, exception) => exception
        }
      invocation.getResult match {
        case Success(value) =>
          future.complete(value)
        case Failure(exception) =>
          future.completeExceptionally(exception)
      }
      debugContext.getStackFrameManager.reloadStackFrames(thread)
    }
    future
  }

  private def evaluationBlock(f: => Unit): Unit = {
    isEvaluating.set(true)
    try f
    finally { isEvaluating.set(false) }
  }

  override def clearState(thread: ThreadReference): Unit = {}
}
