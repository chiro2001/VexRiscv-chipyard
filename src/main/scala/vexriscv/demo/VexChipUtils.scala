package vexriscv.demo

import spinal.core._
import spinal.lib.bus.simple.{PipelinedMemoryBus, PipelinedMemoryBusConfig}
import spinal.lib.misc.HexTools
import spinal.lib.slave

import java.io.File
import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps

object VexChipUtils {
  def readBinaryFile(path: String): Seq[Int] = {
    // println(s"Reading binary file: $path")
    import java.io.FileInputStream
    import java.io.IOException
    try { // create a reader for data file
      val read = new FileInputStream(new File(path))
      // the variable will be used to read one byte at a time
      var byt: Int = 0
      val bytes = new ArrayBuffer[Int]()
      while ( {
        byt = read.read
        byt != -1
      }) bytes.append(byt & 0xFF)
      read.close()
      // println(s"Read done file: $path")
      bytes
    } catch {
      case e: IOException =>
        e.printStackTrace()
        Nil
    }
  }
}

case class VexChipPipelinedMemoryBusRam(onChipRamSize: BigInt, onChipRamBinaryFile: String, pipelinedMemoryBusConfig: PipelinedMemoryBusConfig, bigEndian: Boolean = false) extends Component {
  val io = new Bundle {
    val bus = slave(PipelinedMemoryBus(pipelinedMemoryBusConfig))
  }

  val ram = Mem(Bits(32 bits), onChipRamSize / 4)
  io.bus.rsp.valid := RegNext(io.bus.cmd.fire && !io.bus.cmd.write) init (False)
  io.bus.rsp.data := ram.readWriteSync(
    address = (io.bus.cmd.address >> 2).resized,
    data = io.bus.cmd.data,
    enable = io.bus.cmd.valid,
    write = io.bus.cmd.write,
    mask = io.bus.cmd.mask
  )
  io.bus.cmd.ready := True

  if (onChipRamBinaryFile != null) {
    val wordSize = ram.wordType.getBitsWidth / 8
    val initContent = Array.fill[BigInt](ram.wordCount)(0)
    val fileContent = VexChipUtils.readBinaryFile(onChipRamBinaryFile)
    println(s"mem file size ${fileContent.size / 1024} kB (${fileContent.size} Bytes)")
    val fileWords = fileContent.grouped(wordSize).toList
    (0 until ram.wordCount).zip(fileWords).foreach(x => x._2.zipWithIndex.foreach(i => initContent(x._1) |= (BigInt(i._1) << (i._2 * 8))))
    ram.initBigInt(initContent)
  }
}