package net.c0f3.srv.executor

import cats.effect.std.Queue
import cats.effect.{Async, IO, Resource}
import cats.implicits._
import fs2.io.IOException
import fs2.text

import java.util.Locale
import scala.jdk.CollectionConverters._

class CommandsExecuter[F[_] : Async] {

  private val isWindows: Boolean = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")

  private def adoptCommand(command: List[String]): java.util.List[String] = {
    if(isWindows) {
      val winCommands = new java.util.ArrayList[String](command.size + 1)
      winCommands.add("cmd")
      winCommands.add("/c")
      command.foreach { c =>
        winCommands.add(c)
      }
      winCommands
    } else {
      command.asJava
    }
  }

  def collectOutput: fs2.Pipe[F, ExecCommand, Either[ProcessingError, ExecCommandResult]] =
    _.flatMap { cmd =>

      try {
        val process = Resource.make(Async[F].blocking(new ProcessBuilder()
          .command(adoptCommand(cmd.command))
          .start()))(process => Async[F].blocking(process.destroy()))

        fs2.Stream.resource(process)
          .flatMap { ranProcess =>
            try {
              fs2.io.unsafeReadInputStream(
                Async[F].blocking(ranProcess.getInputStream), 1024, closeAfterUse = false
              ).through(text.utf8.decode)
                .map { s =>
                  Either.right[ProcessingError, ExecCommandResult](new ExecCommandResult(cmd.path, s))
                }
            } catch {
              case ioex: IOException => fs2.Stream.emit {
                Either.left[ProcessingError, ExecCommandResult](
                  new ProcessingError("file read", ioex)
                )
              }
            }
          }
      } catch {
        case ioex: IOException =>
          fs2.Stream.emit(Either.left[ProcessingError, ExecCommandResult](
            new ProcessingError("file read", ioex)
          ))
      }
    }

  def processErrors(): fs2.Pipe[F, Either[ProcessingError, ExecCommandResult], ExecCommandResult] =
    _.filter(_.isRight)
    .map { either =>
      either.getOrElse {
        throw new IllegalStateException("impossible state, stream closed")
      }
    }

}

object CommandsExecuter {

  def processCommands[F[_] : Async](
                                     queue: Queue[F, Option[ExecCommand]],
                                     usage: fs2.Pipe[F, ExecCommandResult, Unit]
                                   ): F[Unit] = {

    val executor = new CommandsExecuter[F]

    fs2.Stream.fromQueueNoneTerminated(queue, 1)
      .through(executor.collectOutput)
      .through(executor.processErrors())
      .through(usage)
      .compile
      .drain

  }

}
