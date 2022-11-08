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
import vexriscv.demo.smp.VexRiscvSmpGen

import scala.tools.nsc.io.File

trait VexRiscvCoreSmpIOIRQ
  extends Bundle {
  val timerInterrupt = Input(Bool())
  val externalInterrupt = Input(Bool())
  val softwareInterrupt = Input(Bool())
}

class VexRiscvCoreSmpIO(n: Int) extends Bundle
  with VexRiscvCoreIOBase
  with VexRiscvCoreIODMemPartConfig {
  val timerInterrupt = Input(UInt(n.W))
  val externalInterrupt = Input(UInt(n.W))
  val softwareInterrupt = Input(UInt(n.W))
  val externalSupervisorInterrupt = Input(UInt(n.W))
  val utime = Input(UInt((n * 64).W))
}

class VexCoreSmp(n: Int, moduleName: String = "VexCoreSmp")(implicit p: Parameters)
  extends BlackBox
    with HasBlackBoxPath {
  val io = IO(new VexRiscvCoreSmpIO(n))

  val chipyardDir = System.getProperty("user.dir")
  val vexRiscvVsrcDir = s"$chipyardDir"
  val targetVerilogFile = s"$vexRiscvVsrcDir/$moduleName.v"

  val file = File(targetVerilogFile)
  if (file.exists) {
    require(file.delete(), s"Waring: cannot delete file $file")
  }

  val config = p(VexRiscvConfigKey)
  println(s"VexCoreSmp generate with Config: ${config}")
  VexRiscvSmpGen.run()

  addPath(targetVerilogFile)
}
