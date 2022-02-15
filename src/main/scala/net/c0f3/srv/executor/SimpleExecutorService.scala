package net.c0f3.srv.executor

import cats.effect.std.Queue
import cats.effect.{Async, ExitCode, IO, IOApp}
import cats.implicits.{catsSyntaxParallelAp, catsSyntaxParallelSequence1}

import java.io.File
import java.nio.file.Paths

object SimpleExecutorService extends IOApp {

  /*
   * -- protocol: file with command
   * -- result: folder with output and result files
   */

  /*
    TO DO:
    1) extract types
    2) catch exceptions, process errors
    2) use queue and stream from queue
    3) save resul to result file
    3) run as service
   */


  def sinkResult[F[_] : Async]: fs2.Pipe[F, ExecCommandResult, Unit] =
    _.evalMap { cer =>
      Async[F].blocking(println(s"execution result of eval of ${cer.path} is: \n ${cer.result}"))
    }

  override def run(args: List[String]): IO[ExitCode] = {

    val pwd = System.getProperty("user.dir")
    val path = Paths.get(pwd + File.separator + "queue" + File.separator + "input")
    val fs2path = fs2.io.file.Path.fromNioPath(path)
    println(s"queue path: $fs2path")

    for {
      q <- Queue.bounded[IO, Option[ExecCommand]](1)
      fiber = FileBasedCommandsSource.subscribe[IO](fs2path, q)
      io = CommandsExecuter.processCommands[IO](q, sinkResult)
      _ <- fiber.parProduct(io)
    } yield ExitCode.Success

  }

}
