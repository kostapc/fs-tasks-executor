package net.c0f3.srv.executor

import cats.effect.Async
import cats.effect.std.Queue
import cats.implicits._
import fs2.io.IOException
import fs2.io.file.Watcher
import fs2.io.file.Watcher.Event.Created
import fs2.text

import java.nio.file.FileSystems

class FileBasedCommandsSource[F[_] : Async](
                                           path: fs2.io.file.Path
                                           ) {

  def listExistsFiles(): fs2.Stream[F, Watcher.Event] = {
    // TODO: list already exists files
    fs2.Stream()
  }

  def subscribeOnPath(): fs2.Stream[F, Watcher.Event] =
    fs2.Stream.resource(Watcher.fromFileSystem[F](FileSystems.getDefault))
      .flatMap { watcher =>
        fs2.Stream.eval(watcher.register(path))
          .flatMap(_ => watcher.events())
      }

  def readFile: fs2.Pipe[F, Watcher.Event, Either[ProcessingError, ExecCommand]] =
    _.collect {
      case e: Created => e
    }.flatMap {
      case Created(path, _) =>
        Console.println("file event caught")
        try {
          fs2.io.file.Files[F]
            .readAll(path)
            .through(text.utf8.decode)
            .map(ExecCommand.fromRawString(path, _))
            .map(Either.right[ProcessingError, ExecCommand](_))
        } catch {
          case ioex: IOException => fs2.Stream.emit {
            Either.left[ProcessingError, ExecCommand](
              new ProcessingError("file read", ioex)
            )
          }
        }
    }

  def processErrors: fs2.Pipe[F, Either[ProcessingError, ExecCommand], ExecCommand] =
    _.filter(_.isRight)
      .map { either =>
        either.getOrElse {
          throw new IllegalStateException("impossible state, stream closed")
        }
      }

}


object FileBasedCommandsSource {

  def subscribe[F[_] : Async](
                               path: fs2.io.file.Path,
                               queue: Queue[F, Option[ExecCommand]]
                             ): F[Unit] = {

    val source = new FileBasedCommandsSource[F](path)


    (source.listExistsFiles() ++ source.subscribeOnPath())
        .through(source.readFile)
        .through(source.processErrors)
        .evalMapChunk { p =>
          queue.offer(Option.apply(p))
        }
        // TODO: delete file on processing complete
        .compile
        .drain
  }
}

