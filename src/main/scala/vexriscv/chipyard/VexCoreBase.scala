//******************************************************************************
// Copyright (c) 2019 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// VexRiscv Tile Wrapper
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package vexriscv.chipyard

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4.AXI4Bundle
import vexriscv.chipyard.VexCoreBase.processFileContent
import vexriscv.demo.{GenVexOnChip, VexAxiConfig, VexAxiCore, VexOnChipConfig}

import java.io.{File, PrintWriter}
import java.util.Date
import scala.io.Source

trait VexRiscvCoreIOIRvfi extends Bundle {
  val rvfi_valid = Output(Bool())
  val rvfi_order = Output(UInt((63 + 1).W))
  val rvfi_insn = Output(UInt((31 + 1).W))
  val rvfi_trap = Output(Bool())
  val rvfi_halt = Output(Bool())
  val rvfi_intr = Output(Bool())
  val rvfi_pc_rdata = Output(UInt((31 + 1).W))
  val rvfi_pc_wdata = Output(UInt((31 + 1).W))
}

trait VexRiscvCoreIOIMem extends Bundle {
  // Instruction Memory Interface
  val iBus_ar_valid = Output(Bool())
  val iBus_ar_ready = Input(Bool())
  val iBus_ar_payload_addr = Output(UInt((31 + 1).W))
  val iBus_ar_payload_id = Output(Bool())
  val iBus_ar_payload_region = Output(UInt(4.W))
  val iBus_ar_payload_len = Output(UInt((7 + 1).W))
  val iBus_ar_payload_size = Output(UInt(3.W))
  val iBus_ar_payload_burst = Output(UInt((1 + 1).W))
  val iBus_ar_payload_lock = Output(Bool())
  val iBus_ar_payload_cache = Output(UInt((3 + 1).W))
  val iBus_ar_payload_qos = Output(UInt((3 + 1).W))
  val iBus_ar_payload_prot = Output(UInt((2 + 1).W))
  val iBus_r_valid = Input(Bool())
  val iBus_r_ready = Output(Bool())
  val iBus_r_payload_data = Input(UInt((31 + 1).W))
  val iBus_r_payload_id = Input(Bool())
  val iBus_r_payload_resp = Input(UInt((1 + 1).W))
  val iBus_r_payload_last = Input(Bool())
  //   output              iBus_ar_valid,
  //   input               iBus_ar_ready,
  //   output     [31:0]   iBus_ar_payload_addr,
  //   output     [0:0]    iBus_ar_payload_id,
  //   output     [3:0]    iBus_ar_payload_region,
  //   output     [7:0]    iBus_ar_payload_len,
  //   output     [2:0]    iBus_ar_payload_size,
  //   output     [1:0]    iBus_ar_payload_burst,
  //   output     [0:0]    iBus_ar_payload_lock,
  //   output     [3:0]    iBus_ar_payload_cache,
  //   output     [3:0]    iBus_ar_payload_qos,
  //   output     [2:0]    iBus_ar_payload_prot,
  //   input               iBus_r_valid,
  //   output              iBus_r_ready,
  //   input      [31:0]   iBus_r_payload_data,
  //   input      [0:0]    iBus_r_payload_id,
  //   input      [1:0]    iBus_r_payload_resp,
  //   input               iBus_r_payload_last,

  def connectIMem(out: AXI4Bundle) = {
    out.aw := DontCare
    out.w := DontCare
    out.b := DontCare

    iBus_ar_ready := out.ar.ready
    out.ar.valid := iBus_ar_valid
    out.ar.bits.id := 0.U
    out.ar.bits.addr := iBus_ar_payload_addr
    out.ar.bits.len := iBus_ar_payload_len
    out.ar.bits.size := iBus_ar_payload_size
    out.ar.bits.burst := iBus_ar_payload_burst
    out.ar.bits.lock := iBus_ar_payload_lock
    out.ar.bits.cache := iBus_ar_payload_cache
    out.ar.bits.prot := iBus_ar_payload_prot
    out.ar.bits.qos := iBus_ar_payload_qos

    out.r.ready := iBus_r_ready
    iBus_r_valid := out.r.valid
    iBus_r_payload_data := out.r.bits.data
    iBus_r_payload_resp := out.r.bits.resp
    iBus_r_payload_last := out.r.bits.last
  }
}

trait VexRiscvCoreIODMemConnector {
  def connectDMem(out: AXI4Bundle): Unit
}

trait VexRiscvCoreIODMemPartConfig
  extends Bundle
    with VexRiscvCoreIODMemConnector {
  val dBus_arw_valid = Output(Bool())
  val dBus_arw_ready = Input(Bool())
  val dBus_arw_payload_addr = Output(UInt((31 + 1).W))
  val dBus_arw_payload_id = Output(UInt((0 + 1).W))
  val dBus_arw_payload_region = Output(UInt((3 + 1).W))
  val dBus_arw_payload_len = Output(UInt((7 + 1).W))
  val dBus_arw_payload_size = Output(UInt((2 + 1).W))
  val dBus_arw_payload_burst = Output(UInt((1 + 1).W))
  val dBus_arw_payload_lock = Output(UInt((0 + 1).W))
  val dBus_arw_payload_cache = Output(UInt((3 + 1).W))
  val dBus_arw_payload_qos = Output(UInt((3 + 1).W))
  val dBus_arw_payload_prot = Output(UInt((2 + 1).W))
  val dBus_arw_payload_write = Output(Bool())
  val dBus_w_valid = Output(Bool())
  val dBus_w_ready = Input(Bool())
  val dBus_w_payload_data = Output(UInt((31 + 1).W))
  val dBus_w_payload_strb = Output(UInt((3 + 1).W))
  val dBus_w_payload_last = Output(Bool())
  val dBus_b_valid = Input(Bool())
  val dBus_b_ready = Output(Bool())
  val dBus_b_payload_id = Input(UInt((0 + 1).W))
  val dBus_b_payload_resp = Input(UInt((1 + 1).W))
  val dBus_r_valid = Input(Bool())
  val dBus_r_ready = Output(Bool())
  val dBus_r_payload_data = Input(UInt((31 + 1).W))
  val dBus_r_payload_id = Input(UInt((0 + 1).W))
  val dBus_r_payload_resp = Input(UInt((1 + 1).W))
  val dBus_r_payload_last = Input(Bool())

  //   output              dBus_arw_valid,
  //   input               dBus_arw_ready,
  //   output     [31:0]   dBus_arw_payload_addr,
  //   output     [0:0]    dBus_arw_payload_id,
  //   output     [3:0]    dBus_arw_payload_region,
  //   output     [7:0]    dBus_arw_payload_len,
  //   output     [2:0]    dBus_arw_payload_size,
  //   output     [1:0]    dBus_arw_payload_burst,
  //   output     [0:0]    dBus_arw_payload_lock,
  //   output     [3:0]    dBus_arw_payload_cache,
  //   output     [3:0]    dBus_arw_payload_qos,
  //   output     [2:0]    dBus_arw_payload_prot,
  //   output              dBus_arw_payload_write,
  //   output              dBus_w_valid,
  //   input               dBus_w_ready,
  //   output     [31:0]   dBus_w_payload_data,
  //   output     [3:0]    dBus_w_payload_strb,
  //   output              dBus_w_payload_last,
  //   input               dBus_b_valid,
  //   output              dBus_b_ready,
  //   input      [0:0]    dBus_b_payload_id,
  //   input      [1:0]    dBus_b_payload_resp,
  //   input               dBus_r_valid,
  //   output              dBus_r_ready,
  //   input      [31:0]   dBus_r_payload_data,
  //   input      [0:0]    dBus_r_payload_id,
  //   input      [1:0]    dBus_r_payload_resp,
  //   input               dBus_r_payload_last,
  override def connectDMem(out: AXI4Bundle) = {
    out.aw.valid := dBus_arw_valid & dBus_arw_payload_write
    out.aw.bits.id := 0.U
    out.aw.bits.addr := dBus_arw_payload_addr
    out.aw.bits.len := dBus_arw_payload_len
    out.aw.bits.size := dBus_arw_payload_size
    out.aw.bits.burst := "b01".U
    out.aw.bits.lock := "b00".U
    out.aw.bits.cache := dBus_arw_payload_cache
    out.aw.bits.prot := dBus_arw_payload_prot
    out.aw.bits.qos := "b0000".U

    dBus_w_ready := out.w.ready
    out.w.valid := dBus_w_valid
    out.w.bits.data := dBus_w_payload_data
    out.w.bits.strb := dBus_w_payload_strb
    out.w.bits.last := dBus_w_payload_last

    out.b.ready := dBus_b_ready
    dBus_b_valid := out.b.valid
    dBus_b_payload_resp := out.b.bits.resp

    dBus_arw_ready := Mux(dBus_arw_payload_write, out.aw.ready, out.ar.ready)
    out.ar.valid := dBus_arw_valid & !dBus_arw_payload_write
    out.ar.bits.id := 0.U
    out.ar.bits.addr := dBus_arw_payload_addr
    out.ar.bits.len := 0.U
    out.ar.bits.size := dBus_arw_payload_size
    out.ar.bits.burst := "b01".U
    out.ar.bits.lock := "b00".U
    out.ar.bits.cache := dBus_arw_payload_cache
    out.ar.bits.prot := dBus_arw_payload_prot
    out.ar.bits.qos := "b0000".U

    out.r.ready := dBus_r_ready
    dBus_r_valid := out.r.valid
    dBus_r_payload_data := out.r.bits.data
    dBus_r_payload_resp := out.r.bits.resp
    dBus_r_payload_last := out.r.bits.last
  }
}

trait VexRiscvCoreIODMemFullConfig
  extends Bundle
    with VexRiscvCoreIODMemConnector {
  // Data Memory Interface
  val dBus_aw_valid = Output(Bool())
  val dBus_aw_ready = Input(Bool())
  val dBus_aw_payload_addr = Output(UInt((31 + 1).W))
  val dBus_aw_payload_id = Output(UInt((0 + 1).W))
  val dBus_aw_payload_region = Output(UInt((3 + 1).W))
  val dBus_aw_payload_len = Output(UInt((7 + 1).W))
  val dBus_aw_payload_size = Output(UInt((2 + 1).W))
  val dBus_aw_payload_burst = Output(UInt((1 + 1).W))
  val dBus_aw_payload_lock = Output(UInt((0 + 1).W))
  val dBus_aw_payload_cache = Output(UInt((3 + 1).W))
  val dBus_aw_payload_qos = Output(UInt((3 + 1).W))
  val dBus_aw_payload_prot = Output(UInt((2 + 1).W))
  val dBus_w_valid = Output(Bool())
  val dBus_w_ready = Input(Bool())
  val dBus_w_payload_data = Output(UInt((31 + 1).W))
  val dBus_w_payload_strb = Output(UInt((3 + 1).W))
  val dBus_w_payload_last = Output(Bool())
  val dBus_b_valid = Input(Bool())
  val dBus_b_ready = Output(Bool())
  val dBus_b_payload_id = Input(UInt((0 + 1).W))
  val dBus_b_payload_resp = Input(UInt((1 + 1).W))
  val dBus_ar_valid = Output(Bool())
  val dBus_ar_ready = Input(Bool())
  val dBus_ar_payload_addr = Output(UInt((31 + 1).W))
  val dBus_ar_payload_id = Output(UInt((0 + 1).W))
  val dBus_ar_payload_region = Output(UInt((3 + 1).W))
  val dBus_ar_payload_len = Output(UInt((7 + 1).W))
  val dBus_ar_payload_size = Output(UInt((2 + 1).W))
  val dBus_ar_payload_burst = Output(UInt((1 + 1).W))
  val dBus_ar_payload_lock = Output(UInt((0 + 1).W))
  val dBus_ar_payload_cache = Output(UInt((3 + 1).W))
  val dBus_ar_payload_qos = Output(UInt((3 + 1).W))
  val dBus_ar_payload_prot = Output(UInt((2 + 1).W))
  val dBus_r_valid = Input(Bool())
  val dBus_r_ready = Output(Bool())
  val dBus_r_payload_data = Input(UInt((31 + 1).W))
  val dBus_r_payload_id = Input(UInt((0 + 1).W))
  val dBus_r_payload_resp = Input(UInt((1 + 1).W))
  val dBus_r_payload_last = Input(Bool())

  //   output              dBus_aw_valid,
  //   input               dBus_aw_ready,
  //   output     [31:0]   dBus_aw_payload_addr,
  //   output     [0:0]    dBus_aw_payload_id,
  //   output     [3:0]    dBus_aw_payload_region,
  //   output     [7:0]    dBus_aw_payload_len,
  //   output     [2:0]    dBus_aw_payload_size,
  //   output     [1:0]    dBus_aw_payload_burst,
  //   output     [0:0]    dBus_aw_payload_lock,
  //   output     [3:0]    dBus_aw_payload_cache,
  //   output     [3:0]    dBus_aw_payload_qos,
  //   output     [2:0]    dBus_aw_payload_prot,
  //   output              dBus_w_valid,
  //   input               dBus_w_ready,
  //   output     [31:0]   dBus_w_payload_data,
  //   output     [3:0]    dBus_w_payload_strb,
  //   output              dBus_w_payload_last,
  //   input               dBus_b_valid,
  //   output              dBus_b_ready,
  //   input      [0:0]    dBus_b_payload_id,
  //   input      [1:0]    dBus_b_payload_resp,
  //   output              dBus_ar_valid,
  //   input               dBus_ar_ready,
  //   output     [31:0]   dBus_ar_payload_addr,
  //   output     [0:0]    dBus_ar_payload_id,
  //   output     [3:0]    dBus_ar_payload_region,
  //   output     [7:0]    dBus_ar_payload_len,
  //   output     [2:0]    dBus_ar_payload_size,
  //   output     [1:0]    dBus_ar_payload_burst,
  //   output     [0:0]    dBus_ar_payload_lock,
  //   output     [3:0]    dBus_ar_payload_cache,
  //   output     [3:0]    dBus_ar_payload_qos,
  //   output     [2:0]    dBus_ar_payload_prot,
  //   input               dBus_r_valid,
  //   output              dBus_r_ready,
  //   input      [31:0]   dBus_r_payload_data,
  //   input      [0:0]    dBus_r_payload_id,
  //   input      [1:0]    dBus_r_payload_resp,
  //   input               dBus_r_payload_last,
  override def connectDMem(out: AXI4Bundle) = {
    val useCaching = false
    dBus_aw_ready := out.aw.ready
    out.aw.valid := dBus_aw_valid
    out.aw.bits.id := 0.U
    out.aw.bits.addr := dBus_aw_payload_addr
    out.aw.bits.len := dBus_aw_payload_len
    out.aw.bits.size := dBus_aw_payload_size
    out.aw.bits.burst := dBus_aw_payload_burst
    out.aw.bits.lock := dBus_aw_payload_lock
    out.aw.bits.cache := dBus_aw_payload_cache & (if (useCaching) 0xffff.U else 0.U)
    out.aw.bits.prot := dBus_aw_payload_prot
    out.aw.bits.qos := dBus_aw_payload_qos

    dBus_w_ready := out.w.ready
    out.w.valid := dBus_w_valid
    out.w.bits.data := dBus_w_payload_data
    out.w.bits.strb := dBus_w_payload_strb
    out.w.bits.last := dBus_w_payload_last

    out.b.ready := dBus_b_ready
    dBus_b_valid := out.b.valid
    dBus_b_payload_resp := out.b.bits.resp

    dBus_ar_ready := out.ar.ready
    out.ar.valid := dBus_ar_valid
    out.ar.bits.id := dBus_ar_payload_id
    out.ar.bits.addr := dBus_ar_payload_addr
    out.ar.bits.len := dBus_ar_payload_len
    out.ar.bits.size := dBus_ar_payload_size
    out.ar.bits.burst := dBus_ar_payload_burst
    out.ar.bits.lock := dBus_ar_payload_lock
    out.ar.bits.cache := dBus_ar_payload_cache & (if (useCaching) 0xffff.U else 0.U)
    out.ar.bits.prot := dBus_ar_payload_prot
    out.ar.bits.qos := dBus_ar_payload_qos

    out.r.ready := dBus_r_ready
    dBus_r_valid := out.r.valid
    dBus_r_payload_data := out.r.bits.data
    dBus_r_payload_resp := out.r.bits.resp
    dBus_r_payload_last := out.r.bits.last
  }
}

trait VexRiscvCoreIOJtag extends Bundle {
  val jtag_tck = Input(Bool())
  val jtag_tms = Input(Bool())
  val jtag_tdi = Input(Bool())
  val jtag_tdo = Output(Bool())
  val debugReset = Input(Bool())
  // val debug_resetOut = Output(Bool())
}

trait VexRiscvCoreIOIRQ
  extends Bundle {
  val timerInterrupt = Input(Bool())
  val externalInterrupt = Input(Bool())
  val softwareInterrupt = Input(Bool())
}

trait VexRiscvCoreIOBase extends Bundle {
  val reset = Input(Bool())
  val clk = Input(Clock()) // System clock
}

trait VexRiscvCoreIOBasic extends Bundle
  with VexRiscvCoreIOBase
  with VexRiscvCoreIOIRQ
  with VexRiscvCoreIOIRvfi
// with VexRiscvCoreIOJtag

class VexRiscvCoreIOFullAXI extends Bundle
  with VexRiscvCoreIODMemFullConfig
  with VexRiscvCoreIOBasic

class VexRiscvCoreIOPartAXI extends Bundle
  with VexRiscvCoreIODMemPartConfig
  with VexRiscvCoreIOIMem
  with VexRiscvCoreIOBasic

abstract class VexCoreBase
(onChipRAM: Boolean, moduleName: String = "VexCore", hartId: Int = 0, optionalConfig: Option[VexOnChipConfig] = None)
(implicit p: Parameters)
  extends BlackBox
    with HasBlackBoxPath {
  val io: VexRiscvCoreIOBasic with VexRiscvCoreIODMemConnector

  val chipyardDir = System.getProperty("user.dir")
  val vexRiscvVsrcDir = s"$chipyardDir"
  val sourceVerilogFile = s"$vexRiscvVsrcDir/$moduleName.v"
  val targetVerilogFile = s"$vexRiscvVsrcDir/$moduleName.preprocessed.v"

  val file = new File(sourceVerilogFile)
  if (file.exists) {
    require(file.delete(), s"Waring: cannot delete file $file")
  }

  val config = if (optionalConfig.nonEmpty) optionalConfig.get else p(VexRiscvConfigKey).copy(hartId = hartId)
  println(s"VexCore generate with Config: ${config}")
  if (onChipRAM) {
    GenVexOnChip.run(config, name = moduleName)
  } else {
    VexAxiCore.run(VexAxiConfig.default.copy(
      iCacheSize = config.iCacheSize,
      dCacheSize = config.dCacheSize,
      hardwareBreakpointCount = config.hardwareBreakpointCount,
      resetVector = config.resetVector,
      hartId = hartId,
      debug = config.debug,
      freq = config.freq,
      name = moduleName
    ))
  }
  val writer = new PrintWriter(new File(targetVerilogFile))
  val preprocessed = processFileContent(Source.fromFile(sourceVerilogFile).getLines().mkString("\n"), hartId)
  writer.write(preprocessed)
  writer.close()

  val fpgaDir = s"$chipyardDir/fpga"
  val configFileName = s"$fpgaDir/VexConfig-${new Date().toString}.log"
  val configWriter = new PrintWriter(new File(configFileName))
  configWriter.println(s"${config.toString}")
  configWriter.close()

  addPath(targetVerilogFile)
}

class VexAXICorePart(onChopRAM: Boolean, moduleName: String = "VexCore")(implicit p: Parameters)
  extends VexCoreBase(onChopRAM, moduleName = moduleName) {
  override val io = IO(new VexRiscvCoreIOPartAXI)
}

class VexAXICoreFull(onChopRAM: Boolean, moduleName: String = "VexCore", hartId: Int = 0, optionalConfig: Option[VexOnChipConfig] = None)(implicit p: Parameters)
  extends VexCoreBase(onChopRAM, moduleName = moduleName, hartId = hartId, optionalConfig = optionalConfig) {
  override val io = IO(new VexRiscvCoreIOFullAXI with VexRiscvCoreIOIMem)
}

class VexAXICoreFullDBusOnly(onChopRAM: Boolean, moduleName: String = "VexAXICoreFullDBusOnly")(implicit p: Parameters)
  extends VexCoreBase(onChopRAM, moduleName = moduleName) {
  override val io = IO(new VexRiscvCoreIOFullAXI)
}

class VexCore(onChopRAM: Boolean, moduleName: String = "VexCore", hartId: Int = 0, optionalConfig: Option[VexOnChipConfig] = None)(implicit p: Parameters)
  extends VexAXICoreFull(onChopRAM, moduleName = moduleName, hartId = hartId, optionalConfig = optionalConfig)

class VexCore0(onChopRAM: Boolean, moduleName: String = "VexCore0", optionalConfig: Option[VexOnChipConfig] = None)(implicit p: Parameters)
  extends VexAXICoreFull(onChopRAM, moduleName = moduleName, hartId = 0, optionalConfig = optionalConfig)

class VexCore1(onChopRAM: Boolean, moduleName: String = "VexCore1", optionalConfig: Option[VexOnChipConfig] = None)(implicit p: Parameters)
  extends VexAXICoreFull(onChopRAM, moduleName = moduleName, hartId = 1, optionalConfig = optionalConfig)

class VexCore2(onChopRAM: Boolean, moduleName: String = "VexCore2", optionalConfig: Option[VexOnChipConfig] = None)(implicit p: Parameters)
  extends VexAXICoreFull(onChopRAM, moduleName = moduleName, hartId = 2, optionalConfig = optionalConfig)

class VexCore3(onChopRAM: Boolean, moduleName: String = "VexCore3", optionalConfig: Option[VexOnChipConfig] = None)(implicit p: Parameters)
  extends VexAXICoreFull(onChopRAM, moduleName = moduleName, hartId = 3, optionalConfig = optionalConfig)

object VexCoreBase {
  def processFileContent(raw: String, id: Int): String = {
    var content = raw
    val modules = ("module +(\\w+)".r findAllIn content).map(str => str.slice("module ".length, str.length)).toList
    println(s"preprocessed modules: ${modules}")
    for (module <- modules) {
      if (!module.endsWith(s"${id}"))
        content = content.replaceAll(module, s"${module}${id}")
    }
    content
  }

  def main(args: Array[String]): Unit = {
    // println(processFileContent(Source.fromFile("VexCore0.v").getLines().mkString("\n"), 0))
    val sourceVerilogFile = "VexCore0.v"
    val targetVerilogFile = "VexCore0.preprocessed.v"
    val writer = new PrintWriter(new File(targetVerilogFile))
    val preprocessed = processFileContent(Source.fromFile(sourceVerilogFile).getLines().mkString("\n"), 0)
    writer.write(preprocessed)
    writer.close()
  }
}