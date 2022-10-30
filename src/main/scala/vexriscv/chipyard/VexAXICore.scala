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

import chisel3._
import chisel3.util._
import spinal.core.SpinalConfig
import vexriscv.demo.{VexAXIConfig, VexAXIJTAGCore, VexInterfaceConfig}
// import vexriscv.demo.{VexAXIConfig, VexAXICore => VexCoreUse}
import vexriscv.demo.{VexAXIConfig, VexAXICore => VexCoreUse}

import scala.tools.nsc.io.File

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
}

trait VexRiscvCoreIODMem extends Bundle {
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
}

trait VexRiscvCoreIOJtag extends Bundle {
  val jtag_tck = Input(Bool())
  val jtag_tms = Input(Bool())
  val jtag_tdi = Input(Bool())
  val jtag_tdo = Output(Bool())
  val debugReset = Input(Bool())
  val debug_resetOut = Output(Bool())
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

class VexRiscvCoreIO extends Bundle
  with VexRiscvCoreIOBase
  with VexRiscvCoreIOIRQ
  with VexRiscvCoreIOIMem
  with VexRiscvCoreIODMem
  // with VexRiscvCoreIOIRvfi
  with VexRiscvCoreIOJtag

class VexAXICore
  extends BlackBox
    with HasBlackBoxPath {
  val io = IO(new VexRiscvCoreIO)

  val chipyardDir = System.getProperty("user.dir")
  val vexRiscvVsrcDir = s"$chipyardDir"
  val targetVerilogFile = s"$vexRiscvVsrcDir/VexAXICore.v"

  val file = File(targetVerilogFile)
  if (file.exists) {
    require(file.delete(), s"Waring: cannot delete file $file")
  }

  // val config = SpinalConfig()
  // config.generateVerilog({
  //   val toplevel = new VexCoreUse(VexAXIConfig.default)
  //   toplevel
  // })

  VexAXIJTAGCore.run()

  addPath(targetVerilogFile)
}
