package net.c0f3.srv.executor

import fs2.io.file.Path

case class ExecCommand(
                        path: Path,
                        command: List[String]
                      )

object ExecCommand {

  def fromRawString(path: Path, commandText: String): ExecCommand = {
    val lines = commandText.split("\n").map(l => l.trim).toList
    new ExecCommand(
      path = path,
      command = lines
    )
  }

}

class ExecCommandResult(
                         val path: Path,
                         val result: String
                       )

class ProcessingError(
                       val step: String,
                       val ex: Exception
                     )
