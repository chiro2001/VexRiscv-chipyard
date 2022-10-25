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
import vexriscv.demo.VexAXIConfig
// import vexriscv.demo.{VexAXIConfig, VexAXICore => VexCoreUse}
import vexriscv.demo.{VexAXIConfig, VexAXICore => VexCoreUse}

import scala.tools.nsc.io.File

//   input               io_asyncReset,
//   input               io_axiClk,
//   input               io_coreInterrupt,
//   output              io_iBus_ar_valid,
//   input               io_iBus_ar_ready,
//   output     [31:0]   io_iBus_ar_payload_addr,
//   output     [3:0]    io_iBus_ar_payload_cache,
//   output     [2:0]    io_iBus_ar_payload_prot,
//   input               io_iBus_r_valid,
//   output              io_iBus_r_ready,
//   input      [31:0]   io_iBus_r_payload_data,
//   input      [1:0]    io_iBus_r_payload_resp,
//   input               io_iBus_r_payload_last,
//   output              io_dBus_arw_valid,
//   input               io_dBus_arw_ready,
//   output     [31:0]   io_dBus_arw_payload_addr,
//   output     [2:0]    io_dBus_arw_payload_size,
//   output     [3:0]    io_dBus_arw_payload_cache,
//   output     [2:0]    io_dBus_arw_payload_prot,
//   output              io_dBus_arw_payload_write,
//   output              io_dBus_w_valid,
//   input               io_dBus_w_ready,
//   output     [31:0]   io_dBus_w_payload_data,
//   output     [3:0]    io_dBus_w_payload_strb,
//   output              io_dBus_w_payload_last,
//   input               io_dBus_b_valid,
//   output              io_dBus_b_ready,
//   input      [1:0]    io_dBus_b_payload_resp,
//   input               io_dBus_r_valid,
//   output              io_dBus_r_ready,
//   input      [31:0]   io_dBus_r_payload_data,
//   input      [1:0]    io_dBus_r_payload_resp,
//   input               io_dBus_r_payload_last,
//   input               clk,
//   input               reset

trait VexRiscvCoreIOIMem extends Bundle {
  // Instruction Memory Interface
  val io_iBus_ar_valid = Output(Bool())
  val io_iBus_ar_ready = Input(Bool())
  val io_iBus_ar_payload_addr = Output(UInt((31 + 1).W))
  val io_iBus_ar_payload_len = Output(UInt((7 + 1).W))
  val io_iBus_ar_payload_burst = Output(UInt((1 + 1).W))
  val io_iBus_ar_payload_cache = Output(UInt((3 + 1).W))
  val io_iBus_ar_payload_prot = Output(UInt((2 + 1).W))
  val io_iBus_r_valid = Input(Bool())
  val io_iBus_r_ready = Output(Bool())
  val io_iBus_r_payload_data = Input(UInt((31 + 1).W))
  val io_iBus_r_payload_resp = Input(UInt((1 + 1).W))
  val io_iBus_r_payload_last = Input(Bool())
  //   output              io_iBus_ar_valid,
  //   input               io_iBus_ar_ready,
  //   output     [31:0]   io_iBus_ar_payload_addr,
  //   output     [7:0]    io_iBus_ar_payload_len,
  //   output     [1:0]    io_iBus_ar_payload_burst,
  //   output     [3:0]    io_iBus_ar_payload_cache,
  //   output     [2:0]    io_iBus_ar_payload_prot,
  //   input               io_iBus_r_valid,
  //   output              io_iBus_r_ready,
  //   input      [31:0]   io_iBus_r_payload_data,
  //   input      [1:0]    io_iBus_r_payload_resp,
  //   input               io_iBus_r_payload_last,
}

trait VexRiscvCoreIODMem extends Bundle {
  // Data Memory Interface
  val io_dBus_arw_valid = Output(Bool())
  val io_dBus_arw_ready = Input(Bool())
  val io_dBus_arw_payload_addr = Output(UInt((31 + 1).W))
  val io_dBus_arw_payload_len = Output(UInt((7 + 1).W))
  val io_dBus_arw_payload_size = Output(UInt((2 + 1).W))
  val io_dBus_arw_payload_cache = Output(UInt((3 + 1).W))
  val io_dBus_arw_payload_prot = Output(UInt((2 + 1).W))
  val io_dBus_arw_payload_write = Output(Bool())
  val io_dBus_w_valid = Output(Bool())
  val io_dBus_w_ready = Input(Bool())
  val io_dBus_w_payload_data = Output(UInt((31 + 1).W))
  val io_dBus_w_payload_strb = Output(UInt((3 + 1).W))
  val io_dBus_w_payload_last = Output(Bool())
  val io_dBus_b_valid = Input(Bool())
  val io_dBus_b_ready = Output(Bool())
  val io_dBus_b_payload_resp = Input(UInt((1 + 1).W))
  val io_dBus_r_valid = Input(Bool())
  val io_dBus_r_ready = Output(Bool())
  val io_dBus_r_payload_data = Input(UInt((31 + 1).W))
  val io_dBus_r_payload_resp = Input(UInt((1 + 1).W))
  val io_dBus_r_payload_last = Input(Bool())
  //   output              io_dBus_arw_valid,
  //   input               io_dBus_arw_ready,
  //   output     [31:0]   io_dBus_arw_payload_addr,
  //   output     [7:0]    io_dBus_arw_payload_len,
  //   output     [2:0]    io_dBus_arw_payload_size,
  //   output     [3:0]    io_dBus_arw_payload_cache,
  //   output     [2:0]    io_dBus_arw_payload_prot,
  //   output              io_dBus_arw_payload_write,
  //   output              io_dBus_w_valid,
  //   input               io_dBus_w_ready,
  //   output     [31:0]   io_dBus_w_payload_data,
  //   output     [3:0]    io_dBus_w_payload_strb,
  //   output              io_dBus_w_payload_last,
  //   input               io_dBus_b_valid,
  //   output              io_dBus_b_ready,
  //   input      [1:0]    io_dBus_b_payload_resp,
  //   input               io_dBus_r_valid,
  //   output              io_dBus_r_ready,
  //   input      [31:0]   io_dBus_r_payload_data,
  //   input      [1:0]    io_dBus_r_payload_resp,
  //   input               io_dBus_r_payload_last,
}

trait VexRiscvCoreIOJtag extends Bundle {
  // -- JTAG I/F
  val trst_n = Input(Bool())
  val tck = Input(Bool())
  val tms = Input(Bool())
  val tdi = Input(Bool())
  val tdo = Output(Bool())
  val tdo_en = Output(Bool())
}

trait VexRiscvCoreIOIRQ
  extends Bundle {
  val io_coreInterrupt = Input(Bool())
}

trait VexRiscvCoreIOBase extends Bundle {
  val io_asyncReset = Input(Bool())
  val reset = Input(Bool())
  val clk = Input(Clock()) // System clock
  val io_axiClk = Input(Clock())
}

class VexRiscvCoreIO extends Bundle
  with VexRiscvCoreIOBase
  with VexRiscvCoreIOIRQ
  with VexRiscvCoreIOIMem
  with VexRiscvCoreIODMem

class VexAXICore
  extends BlackBox
    // with HasBlackBoxResource
    with HasBlackBoxPath {
  val io = IO(new VexRiscvCoreIO)

  val chipyardDir = System.getProperty("user.dir")
  val vexRiscvVsrcDir = s"$chipyardDir"
  val targetVerilogFile = s"$vexRiscvVsrcDir/VexAXICore.v"

  val file = File(targetVerilogFile)
  if (file.exists) {
    require(file.delete(), s"Waring: cannot delete file $file")
  }

  val config = SpinalConfig()
  config.generateVerilog({
    val toplevel = new VexCoreUse(VexAXIConfig.default)
    toplevel
  })

  addPath(targetVerilogFile)
}
