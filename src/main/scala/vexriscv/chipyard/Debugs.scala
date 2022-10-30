package vexriscv.chipyard

import chipsalliance.rocketchip.config.{Config, Field}
import chisel3.{Bool, Bundle, Clock, Input, Output}
import chisel3.experimental.noPrefix
import freechips.rocketchip.devices.debug.{DebugModuleKey, HasPeripheryDebug}
import freechips.rocketchip.diplomacy.LazyModuleImp
import freechips.rocketchip.prci.{ClockSinkNode, ClockSinkParameters}
import freechips.rocketchip.subsystem.{BaseSubsystem, CBUS}

case class CoreInternalJTAGDebugParams
(hasReset: Boolean = false)

object CoreInternalJTAGDebugParams {
  def apply(hasReset: Boolean): CoreInternalJTAGDebugParams = {
    new CoreInternalJTAGDebugParams().copy(
      hasReset = hasReset
    )
  }
}

case object CoreInternalJTAGDebugKey extends Field[Option[CoreInternalJTAGDebugParams]](Some(CoreInternalJTAGDebugParams(false)))

class WithCoreInternalJTAGDebug(hasReset: Boolean = false) extends Config((site, here, up) => {
  case DebugModuleKey => None
  case CoreInternalJTAGDebugKey => up(CoreInternalJTAGDebugKey, site).map(_.copy(hasReset = hasReset))
})

class VexJTAGChipIO extends Bundle {
  val TCK = Input(Clock())
  val TMS = Input(Bool())
  val TDI = Input(Bool())
  val TDO = Output(Bool())
}

trait HasCoreInternalDebug {
  this: BaseSubsystem =>
  private val tlbus = locateTLBusWrapper(CBUS)
  val debugDomainOpt = p(CoreInternalJTAGDebugKey).flatMap { param =>
    val domain = if (param.hasReset) Some(ClockSinkNode(Seq(ClockSinkParameters()))) else None
    if (domain.nonEmpty) {
      domain.get := tlbus.fixedClockNode
      domain
    } else {
      None
    }
  }
  val jtagBundle = p(CoreInternalJTAGDebugKey).map { param =>
    new VexJTAGChipIO
  }
}

trait HasCoreInternalDebugModuleImp extends LazyModuleImp {
  val outer: HasCoreInternalDebug

  val jtag = noPrefix(outer.jtagBundle.map { jtagBundle => {
    val j = IO(jtagBundle)
    import chisel3.util.experimental.BoringUtils._
    addSource(j.TCK, "jtag_TCK")
    addSource(j.TMS, "jtag_TMS")
    addSource(j.TDI, "jtag_TDI")
    addSink(j.TDO, "jtag_TDO")
    j
  }
  })
}