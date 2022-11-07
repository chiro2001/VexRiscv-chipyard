//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package vexriscv.chipyard

import chipsalliance.rocketchip.config.Field
import freechips.rocketchip.config.Config
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile._
import vexriscv.demo.VexOnChipConfig

import java.io.File
import sys.process._

/**
 * Create multiple copies of a VexRiscv tile (and thus a core).
 * Override with the default mixins to control all params of the tiles.
 *
 * @param n amount of tiles to duplicate
 */
class WithNVexRiscvCores(n: Int = 1, overrideIdOffset: Option[Int] = None, onChipRAM: Boolean = false) extends Config((site, here, up) => {
  case TilesLocated(InSubsystem) => {
    if (onChipRAM) require(n == 1)
    val prev = up(TilesLocated(InSubsystem), site)
    val idOffset = overrideIdOffset.getOrElse(prev.size)
    (0 until n).map { i =>
      // println(s"VexRiscvCore #$i, onChipRAM=$onChipRAM, resetVector=${site(VexRiscvConfigKey).resetVector}")
      VexRiscvTileAttachParams(
        tileParams = VexRiscvTileParams(hartId = i + idOffset, onChipRAM = onChipRAM),
        crossingParams = RocketCrossingParams()
      )
    } ++ prev
  }
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 4)
  case XLen => 32
})

case object VexRiscvConfigKey extends Field[VexOnChipConfig]

class WithVexDefaultConfig extends Config((site, here, up) => {
  case VexRiscvConfigKey => VexOnChipConfig.default
})

class WithVexConfig(config: VexOnChipConfig) extends Config((site, here, up) => {
  case VexRiscvConfigKey => config
})

@Deprecated
class WithVexICacheSize(iCacheSize: Int = 4 * 1024) extends Config((site, here, up) => {
  case VexRiscvConfigKey => up(VexRiscvConfigKey, site).copy(iCacheSize = iCacheSize)
})

@Deprecated
class WithVexDCacheSize(dCacheSize: Int = 4 * 1024) extends Config((site, here, up) => {
  case VexRiscvConfigKey => up(VexRiscvConfigKey, site).copy(dCacheSize = dCacheSize)
})

@Deprecated
class WithVexOnChipMemSize(onChipMemSize: BigInt = 32 * 1024) extends Config((site, here, up) => {
  case VexRiscvConfigKey => up(VexRiscvConfigKey, site).copy(onChipRamSize = onChipMemSize)
})

@Deprecated
class WithVexOnChipMemFile(binaryFile: String) extends Config((site, here, up) => {
  case VexRiscvConfigKey => up(VexRiscvConfigKey, site).copy(onChipRamBinaryFile = binaryFile)
})

@Deprecated
class WithVexResetVector(resetVector: BigInt = 0x10000L) extends Config((site, here, up) => {
  case VexRiscvConfigKey => up(VexRiscvConfigKey, site).copy(resetVector = resetVector)
})

object BootRoms {
  def onChipCoreMark: String = {
    val binaryFile = new File("./software/coremark/overlay/coremark.perf.bin")
    // val clean = s"make -C ./software/coremark/riscv-coremark/perf clean"
    // require(clean.! == 0 && !binaryFile.exists(), "failed to clean coremark for vex-riscv!")
    val make = s"make -C ./software/coremark/riscv-coremark/perf"
    if (!binaryFile.exists()) require(make.! == 0, "failed to make coremark for vex-riscv!")
    binaryFile.getAbsolutePath
  }
}

class WithVexOnChipCoreMark extends Config((site, here, up) => {
  case VexRiscvConfigKey => {
    up(VexRiscvConfigKey, site).copy(onChipRamBinaryFile = BootRoms.onChipCoreMark)
  }
})