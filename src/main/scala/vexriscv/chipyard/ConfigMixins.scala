//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package vexriscv.chipyard

import freechips.rocketchip.config.Config
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile._

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
      VexRiscvTileAttachParams(
        tileParams = VexRiscvTileParams(hartId = i + idOffset, trace = true, onChipRAM = onChipRAM),
        crossingParams = RocketCrossingParams()
      )
    } ++ prev
  }
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 32
})

