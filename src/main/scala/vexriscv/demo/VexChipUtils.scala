package vexriscv.demo

import spinal.core._
import spinal.lib.bus.amba4.axi.{Axi4Shared, Axi4SharedOnChipRam}
import spinal.lib.bus.simple.{PipelinedMemoryBus, PipelinedMemoryBusConfig}
import spinal.lib.misc.HexTools
import spinal.lib.slave
import vexriscv.demo.VexChipUtils.{generateFromBinaryFile, ramInitFromBinaryFile}

import java.io.{File, PrintWriter}
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

  def generateFromBinaryFile(onChipRamBinaryFile: String, targetFile: String = null): String = {
    // TODO: generate_ip
    val targetFileUse = if (targetFile != null) new File(targetFile) else new File(onChipRamBinaryFile + ".dat")
    if (onChipRamBinaryFile != null) {
      // Generate HEX text file
      val wordSize = 4
      val wordCount = ((64 KiB) / wordSize).toInt
      val initContent = Array.fill[BigInt](wordCount)(0)
      val fileContent = VexChipUtils.readBinaryFile(onChipRamBinaryFile)
      println(s"mem file size ${fileContent.size / 1024} kB (${fileContent.size} Bytes)")
      val fileWords = fileContent.grouped(wordSize).toList
      (0 until wordCount).zip(fileWords).foreach(x => x._2.zipWithIndex.foreach(i => initContent(x._1) |= (BigInt(i._1) << (i._2 * 8))))
      // ram.initBigInt(initContent)
      val pw = new PrintWriter(targetFileUse)
      for (word <- initContent) {
        // println(s"${word.toHexString}")
        val w = word.toLong.toHexString
        val filled = Seq.fill(8 - w.length)('0').mkString("") + w
        pw.write(filled + "\n")
      }
      pw.close()
    }
    targetFileUse.getAbsolutePath
  }

  def ramInitFromBinaryFile(ram: Mem[Bits], onChipRamBinaryFile: String) = {
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
}

trait VexChipPipelinedMemoryBus {
  def getBus: PipelinedMemoryBus
}

case class VexChipPipelinedMemoryBusRam
(onChipRamSize: BigInt,
 onChipRamBinaryFile: String,
 pipelinedMemoryBusConfig: PipelinedMemoryBusConfig,
 bigEndian: Boolean = false)
  extends Component
    with VexChipPipelinedMemoryBus {
  val io = new Bundle {
    val bus = slave(PipelinedMemoryBus(pipelinedMemoryBusConfig))
  }

  override def getBus = io.bus

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

  ramInitFromBinaryFile(ram, onChipRamBinaryFile)
}

case class VexChipPipelinedMemoryBusDRM
(onChipRamSize: BigInt,
 onChipRamBinaryFile: String,
 pipelinedMemoryBusConfig: PipelinedMemoryBusConfig,
 bigEndian: Boolean = false)
  extends Component
    with VexChipPipelinedMemoryBus {
  val io = new Bundle {
    val bus = slave(PipelinedMemoryBus(pipelinedMemoryBusConfig))
  }

  require(onChipRamSize == (64 KiB))

  override def getBus = io.bus

  val ram = new drm_32x16384
  io.bus.rsp.valid := RegNext(io.bus.cmd.fire && !io.bus.cmd.write) init (False)
  io.bus.rsp.data := ram.readWriteSync(
    address = (io.bus.cmd.address >> 2).resized,
    data = io.bus.cmd.data,
    enable = io.bus.cmd.valid,
    write = io.bus.cmd.write,
    mask = io.bus.cmd.mask
  )
  io.bus.cmd.ready := True
  generateFromBinaryFile(onChipRamBinaryFile)
}

trait Axi4SharedOnChipRamWithAXIPort {
  def getAXIPort: Axi4Shared

  // def setName(name: String): Axi4SharedOnChipRamWithAXIPort
}

class Axi4SharedOnChipRamMem(dataWidth: Int, byteCount: BigInt, idWidth: Int, arwStage: Boolean = false, onChipRamBinaryFile: String = null)
  extends Axi4SharedOnChipRam(dataWidth, byteCount, idWidth, arwStage)
    with Axi4SharedOnChipRamWithAXIPort {
  override def getAXIPort = io.axi

  override type RefOwnerType = this.type

  ramInitFromBinaryFile(ram, onChipRamBinaryFile)
}

case class Axi4SharedOnChipRamDRM
(dataWidth: Int, byteCount: BigInt, idWidth: Int, arwStage: Boolean = false, onChipRamBinaryFile: String = null)
  extends Component
    with Axi4SharedOnChipRamWithAXIPort {
  val axiConfig = Axi4SharedOnChipRam.getAxiConfig(dataWidth, byteCount, idWidth)

  val io = new Bundle {
    val axi = slave(Axi4Shared(axiConfig))
  }

  override def getAXIPort = io.axi

  val wordCount = byteCount / axiConfig.bytePerWord
  // val ram = Mem(axiConfig.dataType,wordCount.toInt)
  val ram = new drm_32x16384
  generateFromBinaryFile(onChipRamBinaryFile)
  val wordRange = log2Up(wordCount) + log2Up(axiConfig.bytePerWord) - 1 downto log2Up(axiConfig.bytePerWord)

  val arw = if (arwStage) io.axi.arw.s2mPipe().unburstify.m2sPipe() else io.axi.arw.unburstify
  val stage0 = arw.haltWhen(arw.write && !io.axi.writeData.valid)
  io.axi.readRsp.data := ram.readWriteSync(
    address = stage0.addr(axiConfig.wordRange).resized,
    data = io.axi.writeData.data,
    enable = stage0.fire,
    write = stage0.write,
    mask = io.axi.writeData.strb
  )
  io.axi.writeData.ready := arw.valid && arw.write && stage0.ready

  val stage1 = stage0.stage()
  stage1.ready := (io.axi.readRsp.ready && !stage1.write) || ((io.axi.writeRsp.ready || !stage1.last) && stage1.write)

  io.axi.readRsp.valid := stage1.valid && !stage1.write
  io.axi.readRsp.id := stage1.id
  io.axi.readRsp.last := stage1.last
  io.axi.readRsp.setOKAY()
  if (axiConfig.useRUser) io.axi.readRsp.user := stage1.user

  io.axi.writeRsp.valid := stage1.valid && stage1.write && stage1.last
  io.axi.writeRsp.setOKAY()
  io.axi.writeRsp.id := stage1.id
  if (axiConfig.useBUser) io.axi.writeRsp.user := stage1.user

  io.axi.arw.ready.noBackendCombMerge //Verilator perf
}

class drm_32x16384 extends BlackBox {
  val io = new Bundle {
    val wr_data = in Bits (32 bits)
    val wr_addr = in UInt (14 bits)
    val wr_en = in Bool()
    val wr_clk = in Bool()
    val wr_clk_en = in Bool()
    val wr_byte_en = in Bits (4 bits)
    val wr_rst = in Bool()
    val rd_addr = in UInt (14 bits)
    val rd_data = out Bits (32 bits)
    val rd_clk = in Bool()
    val rd_rst = in Bool()
  }
  noIoPrefix()

  def readWriteSync(address: UInt, data: Bits, enable: Bool, write: Bool, mask: Bits): Bits = {
    io.rd_addr := address
    io.wr_addr := address
    io.wr_data := data
    io.wr_clk_en := enable
    io.wr_en := write
    io.wr_byte_en := mask
    io.rd_data
  }

  mapClockDomain(ClockDomain.current, clock = io.wr_clk, reset = io.wr_rst)
  mapClockDomain(ClockDomain.current, clock = io.rd_clk, reset = io.rd_rst)
}